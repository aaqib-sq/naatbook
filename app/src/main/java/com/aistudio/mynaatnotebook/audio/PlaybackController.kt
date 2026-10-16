package com.aistudio.mynaatnotebook.audio

import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.viewmodel.StatusMessage
import com.aistudio.mynaatnotebook.viewmodel.StatusReporter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aistudio.mynaatnotebook.data.NaatEntity
import com.aistudio.mynaatnotebook.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of the Media3 session-owned entry shown by the global mini-player. */
data class NowPlaying(
    val naatId: Int,
    val title: String,
    val poet: String?,
    val audioPath: String,
    val titleFontOverride: String? = null
)

/** Process-wide ownership boundary around the shared Media3 player. */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: Media3PlaybackEngine,
    private val playbackRequests: PlaybackRequestRegistry,
    private val statusReporter: StatusReporter,
    private val settingsStore: SettingsStore
) {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()
    private val _previewPath = MutableStateFlow<String?>(null)
    val previewPath: StateFlow<String?> = _previewPath.asStateFlow()
    private val serviceStopHandler = Handler(Looper.getMainLooper())
    private val serviceStopGate = PlaybackServiceStopGate()
    private var pendingServiceStop: Runnable? = null

    val isPlaying = engine.isPlaying
    val currentPosition = engine.currentPosition
    val duration = engine.duration
    val isPreparing = engine.isPreparing
    val hasActiveSession = engine.hasActiveSession

    // Declared before the init blocks: init restores the persisted playback
    // speed on timerScope, so the scope must be initialized first.
    private val timerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    init {
        engine.onSessionStopped = {
            val wasEntrySession = _nowPlaying.value != null
            _nowPlaying.value = null
            _previewPath.value = null
            if (wasEntrySession) scheduleServiceShutdown()
        }
        // Async failures (file deleted outside the app, corrupt audio) must
        // reach the user — previously they only hit the log and the controls
        // silently reverted, so the play tap looked dead.
        engine.onPlaybackError = {
            statusReporter.show(R.string.status_playback_failed)
        }
        // Restore the persisted playback speed (if any) onto the fresh player.
        // The engine is process-lifetime, so in-process track changes keep the
        // speed; only a restart rebuilds the player at 1.0x.
        timerScope.launch {
            val stored = settingsStore.playbackSpeed.first().coerceIn(0.5f, 2.0f)
            _playbackSpeed.value = stored
            engine.setPlaybackSpeed(stored)
        }
    }

    private fun cancelPendingServiceShutdown() {
        serviceStopGate.cancelPending()
        pendingServiceStop?.let(serviceStopHandler::removeCallbacks)
        pendingServiceStop = null
    }

    private fun scheduleServiceShutdown() {
        pendingServiceStop?.let(serviceStopHandler::removeCallbacks)
        val token = serviceStopGate.schedule()
        val runnable = Runnable {
            pendingServiceStop = null
            // A newer entry request invalidates this runnable before it can tear
            // down the service that owns the new session.
            if (serviceStopGate.isCurrent(token) && _nowPlaying.value == null) {
                MediaPlaybackService.stop(context)
            }
        }
        pendingServiceStop = runnable
        serviceStopHandler.postDelayed(runnable, SERVICE_STOP_GRACE_MS)
    }

    fun playEntry(naat: NaatEntity) {
        val path = naat.audioPath ?: return
        playEntry(naat, path)
    }

    fun playEntry(naat: NaatEntity, path: String) {
        // Invalidate a delayed stop from the previous entry before requesting the
        // service again. This closes the rapid stop → play race.
        cancelPendingServiceShutdown()
        // The service creates MediaSession before replacing the current item. Keeping an
        // existing service alive avoids a stop/start race between consecutive entry requests.
        _previewPath.value = null
        _nowPlaying.value = NowPlaying(naat.id, naat.title, naat.poet, path, naat.titleFontOverride)
        // Audit #21: the new entry becomes the resume point from position 0;
        // the saver below then keeps it current while it plays.
        lastSavedPositionMs = 0L
        timerScope.launch { settingsStore.setLastPlayback(naat.id, 0L) }
        val requestToken = playbackRequests.register(
            PlaybackRequest(path, naat.id, naat.title, naat.poet)
        )
        try {
            MediaPlaybackService.playEntry(context, requestToken)
        } catch (error: Exception) {
            playbackRequests.discard(requestToken)
            _nowPlaying.value = null
            timerScope.launch { settingsStore.clearLastPlayback() }
            engine.stop()
            MediaPlaybackService.stop(context)
            Log.e("PlaybackController", "Unable to start background playback", error)
            statusReporter.show(R.string.status_playback_failed)
        }
    }

    /**
     * Audit #21: re-create the last entry session PAUSED at its saved
     * position after a process death. Same service route as [playEntry], so
     * the session gets its notification and lock-screen controls; nothing
     * plays until the user taps play.
     */
    fun restoreEntry(naat: NaatEntity, positionMs: Long) {
        val path = naat.audioPath ?: return
        cancelPendingServiceShutdown()
        _previewPath.value = null
        _nowPlaying.value = NowPlaying(naat.id, naat.title, naat.poet, path, naat.titleFontOverride)
        lastSavedPositionMs = positionMs
        val requestToken = playbackRequests.register(
            PlaybackRequest(
                path, naat.id, naat.title, naat.poet,
                startPositionMs = positionMs, autoplay = false
            )
        )
        try {
            MediaPlaybackService.playEntry(context, requestToken)
        } catch (error: Exception) {
            playbackRequests.discard(requestToken)
            _nowPlaying.value = null
            Log.e("PlaybackController", "Unable to restore playback session", error)
        }
    }

    fun playPreview(path: String) {
        engine.play(
            audioPath = path,
            mediaId = "preview:${path.hashCode()}",
            title = "Audio preview",
            artist = null
        )
        if (!engine.hasActiveSession()) return
        _nowPlaying.value = null
        _previewPath.value = path
    }

    fun ownsEntry(naatId: Int): Boolean =
        _nowPlaying.value?.naatId == naatId && engine.hasActiveSession()

    fun ownsPreview(path: String): Boolean =
        _previewPath.value == path && engine.hasActiveSession()

    fun hasActiveSession(): Boolean = engine.hasActiveSession()
    fun pause() {
        engine.pause()
        // Audit #21: pin the exact pause point as the resume position.
        val np = _nowPlaying.value
        if (np != null && _previewPath.value == null) {
            val pos = engine.currentPosition.value.toLong()
            lastSavedPositionMs = pos
            timerScope.launch { settingsStore.setLastPlayback(np.naatId, pos) }
        }
    }
    fun resume() = engine.resume()
    fun togglePlayPause() = engine.togglePlayPause()
    fun seekTo(positionMs: Int) = engine.seekTo(positionMs)
    fun skipForward(seconds: Int = 10) = engine.skipForward(seconds)
    fun skipBackward(seconds: Int = 10) = engine.skipBackward(seconds)
    fun stop() = engine.stop()

    // --- Playback speed ---
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _playbackSpeed.value = clamped
        engine.setPlaybackSpeed(clamped)
        // Persist the choice so it survives app restarts (stage-2 UX audit).
        timerScope.launch { settingsStore.setPlaybackSpeed(clamped) }
    }

    fun cyclePlaybackSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val current = _playbackSpeed.value
        val nextIndex = (speeds.indexOfFirst { it > current + 0.01f }.takeIf { it >= 0 }
            ?: 0)
        setPlaybackSpeed(speeds[nextIndex])
    }

    // --- Sleep timer ---
    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    /** Remaining millis, or null when the timer is off. */
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()
    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    // --- Audit #21: resume-after-process-death persistence ---
    private var lastSavedPositionMs = -1L

    init {
        // A session that truly ended (finished, errored, stopped, service
        // torn down) leaves nothing to resume. Entry switches do not fire
        // this — they save their own fresh point in playEntry.
        engine.onSessionFinished = {
            if (_nowPlaying.value != null) {
                timerScope.launch { settingsStore.clearLastPlayback() }
            }
        }
        // While an entry session runs, keep its position saved (at most
        // every ~5 s of movement, including seeks) so a system kill loses
        // only a few seconds. Preview playback never touches the point.
        timerScope.launch {
            engine.currentPosition.collect { positionMs ->
                val np = _nowPlaying.value ?: return@collect
                if (_previewPath.value != null) return@collect
                val pos = positionMs.toLong()
                if (lastSavedPositionMs < 0L ||
                    kotlin.math.abs(pos - lastSavedPositionMs) >= 5_000L
                ) {
                    lastSavedPositionMs = pos
                    settingsStore.setLastPlayback(np.naatId, pos)
                }
            }
        }
    }

    /**
     * Starts a sleep timer that pauses playback after [minutes].
     * Any existing timer is replaced.
     */
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        _sleepTimerRemainingMs.value = minutes * 60_000L
        sleepTimerJob = timerScope.launch {
            var remaining = minutes * 60_000L
            while (remaining > 0) {
                kotlinx.coroutines.delay(1_000L)
                remaining -= 1_000L
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0)
            }
            _sleepTimerRemainingMs.value = null
            // Audit #13: if the user already stopped playback themselves,
            // the timer finishing is not news — pause() is harmless either
            // way, but the "timer done" toast used to fire into silence.
            if (engine.hasActiveSession.value) {
                pause()
                statusReporter.show(R.string.player_sleep_timer_done)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = null
    }

    /** Closing/backgrounding the editor cannot stop a service-owned entry. */
    fun stopPreview() {
        if (_previewPath.value != null) engine.stop()
    }

    private companion object {
        const val SERVICE_STOP_GRACE_MS = 250L
    }
}
