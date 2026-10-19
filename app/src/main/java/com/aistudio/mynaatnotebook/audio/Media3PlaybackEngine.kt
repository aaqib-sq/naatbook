package com.aistudio.mynaatnotebook.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime Media3 player shared by in-app previews and MediaSessionService.
 *
 * The player is intentionally not released between sessions: releasing it would make
 * the injected singleton unusable for the next preview/service handoff. Every normal
 * terminal path must instead call [ensureIdle], which stops playback, clears media
 * items, cancels progress work, resets exposed state, and releases Media3-managed
 * focus/wake resources. [release] is reserved for a true process-terminal path.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class Media3PlaybackEngine @Inject constructor(
    @ApplicationContext context: Context
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true // Media3 owns audio-focus request, duck/pause, and resume behavior.
        )
        setHandleAudioBecomingNoisy(true) // Pause when wired/Bluetooth output disconnects.
        setWakeMode(C.WAKE_MODE_LOCAL)
    }

    internal var onSessionStopped: (() -> Unit)? = null

    /**
     * Audit fix: async playback failures (missing/corrupt file) used to be
     * log-only — the tap looked dead to the user. The controller wires this
     * to the shared status reporter so a failure is always announced.
     */
    internal var onPlaybackError: ((PlaybackException) -> Unit)? = null

    /**
     * Audit #21: fired by [ensureIdle] BEFORE state is cleared, while the
     * ending session is still identifiable. Entry-switching via [play] does
     * not go through [ensureIdle], so this means "the session truly ended"
     * (natural end, error, explicit stop, service teardown) — the playback
     * controller uses it to drop the persisted resume point.
     */
    internal var onSessionFinished: (() -> Unit)? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()
    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()
    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()
    private val _hasActiveSession = MutableStateFlow(false)
    val hasActiveSession: StateFlow<Boolean> = _hasActiveSession.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var released = false
    private var clearing = false
    private var playbackGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publishState()

            override fun onPlaybackStateChanged(playbackState: Int) {
                publishState()
                if (playbackState == Player.STATE_READY) startProgressTracking()
                if (Media3TerminalPolicy.shouldStop(playbackState, player.mediaItemCount, clearing)) {
                    scheduleTerminalStop()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Media3 playback failed", error)
                onPlaybackError?.invoke(error)
                scheduleTerminalStop()
            }
        })
    }

    @MainThread
    fun play(
        audioPath: String,
        mediaId: String,
        title: String,
        artist: String?,
        notifyPreviousOwner: Boolean = true
    ) {
        check(!released) { "Playback engine has been released" }
        clear(notifyPreviousOwner)
        playbackGeneration++
        val uri = when {
            audioPath.startsWith("content://") -> Uri.parse(audioPath)
            else -> Uri.fromFile(File(audioPath))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
        player.setMediaItem(item)
        _hasActiveSession.value = true
        _isPreparing.value = true
        player.prepare()
        player.play()
        publishState()
        startProgressTracking()
    }

    /**
     * Audit #21: load an entry PAUSED at [positionMs] — the resume-after-
     * process-death restore. Same session shape as [play] (so the mini
     * player, reader and notification all bind to it), but nothing sounds
     * until the user presses play.
     */
    @MainThread
    fun preparePaused(
        audioPath: String,
        mediaId: String,
        title: String,
        artist: String?,
        positionMs: Long
    ) {
        check(!released) { "Playback engine has been released" }
        clear(notifyOwner = false)
        playbackGeneration++
        val uri = when {
            audioPath.startsWith("content://") -> Uri.parse(audioPath)
            else -> Uri.fromFile(File(audioPath))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
        player.setMediaItem(item)
        _hasActiveSession.value = true
        _isPreparing.value = true
        player.prepare()
        player.seekTo(positionMs.coerceAtLeast(0L))
        publishState()
        startProgressTracking()
    }

    fun pause() {
        if (hasActiveSession()) player.pause()
        publishState()
    }

    fun resume() {
        if (!hasActiveSession()) return
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.play()
        publishState()
        startProgressTracking()
    }

    fun togglePlayPause() = if (player.playWhenReady) pause() else resume()

    fun seekTo(positionMs: Int) {
        if (!hasActiveSession()) return
        player.seekTo(positionMs.coerceAtLeast(0).toLong())
        publishState()
    }

    fun skipForward(seconds: Int = 10) {
        if (!hasActiveSession()) return
        val target = (player.currentPosition + seconds * 1000L)
            .coerceAtMost(player.duration.coerceAtLeast(0))
        player.seekTo(target)
        publishState()
    }

    fun skipBackward(seconds: Int = 10) {
        if (!hasActiveSession()) return
        val target = (player.currentPosition - seconds * 1000L).coerceAtLeast(0)
        player.seekTo(target)
        publishState()
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f))
        publishState()
    }

    fun getPlaybackSpeed(): Float = player.playbackParameters.speed

    fun hasActiveSession(): Boolean = !released && player.mediaItemCount > 0

    fun stop() = ensureIdle()

    /** Idempotently return the process singleton to a resource-safe idle state. */
    fun ensureIdle() {
        if (!released) {
            if (hasActiveSession()) onSessionFinished?.invoke()
            clear(notifyOwner = true)
        }
    }

    internal fun isIdle(): Boolean =
        released || (player.mediaItemCount == 0 && progressJob?.isActive != true && !_isPlaying.value)

    private fun clear(notifyOwner: Boolean) {
        playbackGeneration++ // invalidate every terminal callback queued for the old item
        val hadSession = hasActiveSession()
        try {
            clearing = true
            player.stop()
            player.clearMediaItems()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to clear Media3 player", error)
        } finally {
            clearing = false
            progressJob?.cancel()
            progressJob = null
            _isPlaying.value = false
            _currentPosition.value = 0
            _duration.value = 0
            _isPreparing.value = false
            _hasActiveSession.value = false
            if (hadSession && notifyOwner) onSessionStopped?.invoke()
        }
    }

    /** Never mutate/release MediaSession listeners from inside ExoPlayer's event dispatch. */
    private fun scheduleTerminalStop() {
        val generation = playbackGeneration
        mainHandler.post {
            if (!released && generation == playbackGeneration && hasActiveSession() &&
                Media3TerminalPolicy.shouldStop(player.playbackState, player.mediaItemCount, clearing)
            ) {
                stop()
            }
        }
    }

    fun release() {
        if (released) return
        ensureIdle()
        try {
            player.release()
        } finally {
            released = true
            scope.cancel()
            _isPlaying.value = false
            _currentPosition.value = 0
            _duration.value = 0
            _isPreparing.value = false
            _hasActiveSession.value = false
        }
    }

    private fun publishState() {
        if (released) return
        _isPlaying.value = player.isPlaying
        _isPreparing.value = player.playbackState == Player.STATE_BUFFERING
        _hasActiveSession.value = player.mediaItemCount > 0
        _currentPosition.value = player.currentPosition.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        _duration.value = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }

    private fun startProgressTracking() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive && hasActiveSession()) {
                publishState()
                // Audit 05 M7: a paused player has nothing moving to
                // publish — the 250ms loop used to spin for the whole
                // paused session. Poll at 1s while paused/buffering-idle;
                // resume is picked up within a second, and play state
                // changes also arrive through the player listener.
                delay(if (player.isPlaying) 250 else 1000)
            }
        }
    }

    private companion object {
        const val TAG = "Media3Playback"
    }
}
