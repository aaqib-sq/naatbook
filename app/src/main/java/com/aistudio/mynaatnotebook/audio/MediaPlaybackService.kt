package com.aistudio.mynaatnotebook.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aistudio.mynaatnotebook.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Media3 session/notification owner for service-owned entry playback. */
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MediaPlaybackService : MediaSessionService() {
    @Inject lateinit var engine: Media3PlaybackEngine
    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var playbackRequests: PlaybackRequestRegistry

    private var mediaSession: MediaSession? = null

    /** Latest startId, so a foreground-start denial can retire exactly
     * the start that failed to promote (audit 05 L9). */
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        val session = MediaSession.Builder(this, engine.player)
            .setSessionActivity(launchIntent())
            .build()
        mediaSession = session

        // This app starts playback with an explicit service command rather than binding an
        // in-process MediaController. onGetSession() is therefore not called automatically.
        // Explicit registration is required for MediaSessionService to observe the player,
        // publish its notification, and promote itself to a foreground service.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.app_name)
            .build()
            .apply { setSmallIcon(R.drawable.ic_stat_naatbook) }
        setMediaNotificationProvider(notificationProvider)
        addSession(session)
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.e(TAG, "System rejected media foreground-service promotion")
                engine.stop()
                // Audit 05 L9: stopSelfResult retires this service only
                // when no newer start has superseded the denied one.
                if (latestStartId != 0) stopSelfResult(latestStartId) else stopSelf()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_PLAY_ENTRY) {
            val request = playbackRequests.consume(intent.getStringExtra(EXTRA_REQUEST_TOKEN))
            if (request == null) {
                // The service must stay exported for Media3 discovery, but raw paths
                // never cross that boundary. An external explicit intent has no valid
                // one-time in-process token and cannot start playback or stop a session.
                Log.w(TAG, "Rejected unauthorized playback request")
                // Audit 05 L10: if nothing of ours is running, this bogus
                // start is the only reason the service exists — retire it
                // instead of lingering. A live session is never touched.
                if (engine.isIdle()) stopSelfResult(startId)
                return result
            }
            try {
                // onCreate has already attached MediaSession listeners, so this state change
                // always raises the foreground notification and lock-screen controls.
                if (request.autoplay) {
                    engine.play(
                        audioPath = request.path,
                        mediaId = "naat:${request.naatId}",
                        title = request.title,
                        artist = request.artist,
                        notifyPreviousOwner = false,
                        startPositionMs = request.startPositionMs
                    )
                } else {
                    // Audit #21 restore: same session, loaded paused at the
                    // saved position; the notification shows it as resumable.
                    engine.preparePaused(
                        audioPath = request.path,
                        mediaId = "naat:${request.naatId}",
                        title = request.title,
                        artist = request.artist,
                        positionMs = request.startPositionMs
                    )
                }
                triggerNotificationUpdate()
            } catch (error: Exception) {
                Log.e(TAG, "Unable to start service-owned playback", error)
                engine.stop()
                stopSelfResult(startId)
            }
        }
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Audit 05 M9: a session that is buffering with playWhenReady set
        // is ongoing playback, not a dead one — isPlaybackOngoing() alone
        // could read it as finished and kill a stream mid-buffer.
        val bufferingToPlay = player != null && player.playWhenReady &&
            player.mediaItemCount > 0 && player.playbackState == Player.STATE_BUFFERING
        if (player != null && (isPlaybackOngoing() || bufferingToPlay) && player.playWhenReady &&
            player.mediaItemCount > 0 && player.playbackState != Player.STATE_ENDED
        ) {
            // Deliberately do not call super: its fallback stops non-registered/non-foreground
            // sessions. A registered ongoing session must survive removal from recents.
            return
        }
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        // A normal controller stop already idles the singleton before shutdown.
        // A preview intentionally outlives this service after entry → preview handoff
        // so it remains notification-free; every non-preview active player is forced
        // idle here to avoid an unowned player/wake resource after service teardown.
        if (PlayerLifetimePolicy.shouldEnsureIdleOnServiceDestroy(
                engineIdle = engine.isIdle(),
                previewActive = playbackController.previewPath.value != null
            )
        ) {
            engine.ensureIdle()
        }
        clearListener()
        mediaSession?.let { session ->
            if (isSessionAdded(session)) removeSession(session)
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun launchIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.aistudio.mynaatnotebook.MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "Media3Service"
        private const val CHANNEL_ID = "naatbook_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PLAY_ENTRY = "com.aistudio.mynaatnotebook.audio.PLAY_ENTRY"
        private const val EXTRA_REQUEST_TOKEN = "requestToken"

        fun playEntry(context: Context, requestToken: String) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_PLAY_ENTRY
                putExtra(EXTRA_REQUEST_TOKEN, requestToken)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.e(TAG, "MediaSessionService start was rejected", error)
                throw error
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MediaPlaybackService::class.java))
            } catch (error: Exception) {
                Log.w(TAG, "MediaSessionService stop failed", error)
            }
        }
    }
}
