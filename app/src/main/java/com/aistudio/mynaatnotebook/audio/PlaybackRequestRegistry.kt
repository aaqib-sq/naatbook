package com.aistudio.mynaatnotebook.audio

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Private, one-time playback request consumed only inside this app process. */
data class PlaybackRequest(
    val path: String,
    val naatId: Int,
    val title: String,
    val artist: String?,
    /** Audit #21: resume point for a restored (paused) session. */
    val startPositionMs: Long = 0L,
    /** Audit #21: false = prepare paused at [startPositionMs], do not play. */
    val autoplay: Boolean = true
)

/**
 * Keeps raw file paths and metadata out of the exported service intent.
 *
 * The MediaSessionService remains exported for Media3 discovery, but an external
 * app cannot manufacture a valid playback command: the service accepts only a
 * random, one-time token registered by [PlaybackController] in this process.
 */
@Singleton
class PlaybackRequestRegistry @Inject constructor() {
    private data class PendingRequest(
        val request: PlaybackRequest,
        val expiresAtMs: Long
    )

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    fun register(request: PlaybackRequest): String {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        pending[token] = PendingRequest(request, System.currentTimeMillis() + TOKEN_TTL_MS)
        return token
    }

    fun consume(token: String?): PlaybackRequest? {
        purgeExpired()
        return token?.let(pending::remove)?.request
    }

    fun discard(token: String) {
        pending.remove(token)
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        pending.forEach { (token, request) ->
            if (request.expiresAtMs <= now) pending.remove(token, request)
        }
    }

    private companion object {
        const val TOKEN_TTL_MS = 60_000L
    }
}
