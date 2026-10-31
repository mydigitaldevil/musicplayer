package com.mydigitaldevil.musicplayer.stats

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mydigitaldevil.musicplayer.library.LocalMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns player events into [PlayEventEntity] rows. Lives inside the playback service, so stats are
 * recorded for background and lock-screen listening too, not only while the UI is open.
 */
class PlayEventLogger(
    private val player: Player,
    private val dao: StatsDao,
    private val scope: CoroutineScope,
) : Player.Listener {

    private class Session(
        val item: MediaItem,
        val startedAtWall: Long,
        val repeated: Boolean,
        var listenedMs: Long = 0,
        var playingSince: Long? = null,
    )

    private var session: Session? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val finishedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        close(completed = finishedNaturally, previousDurationMs = lastDurationMs)
        if (mediaItem != null) open(mediaItem, repeated = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val s = session ?: player.currentMediaItem?.let { open(it, repeated = false) } ?: return
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) {
            if (s.playingSince == null) s.playingSince = now
        } else {
            s.playingSince?.let { s.listenedMs += now - it }
            s.playingSince = null
        }
        if (player.duration > 0) lastDurationMs = player.duration
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (player.duration > 0) lastDurationMs = player.duration
        if (playbackState == Player.STATE_ENDED) close(completed = true, previousDurationMs = lastDurationMs)
    }

    /** Call from the service's onDestroy so the final item isn't lost. */
    fun flush() = close(completed = false, previousDurationMs = lastDurationMs)

    private var lastDurationMs = 0L

    private fun open(item: MediaItem, repeated: Boolean): Session =
        Session(item, System.currentTimeMillis(), repeated).also {
            session = it
            lastDurationMs = 0
            if (player.isPlaying) it.playingSince = SystemClock.elapsedRealtime()
        }

    private fun close(completed: Boolean, previousDurationMs: Long) {
        val s = session ?: return
        session = null
        s.playingSince?.let { s.listenedMs += SystemClock.elapsedRealtime() - it }
        if (s.listenedMs < MIN_LOGGED_MS) return // accidental taps and instant skips aren't plays

        val duration = previousDurationMs.coerceAtLeast(0)
        val event = PlayEventEntity(
            mediaId = s.item.mediaId,
            title = s.item.mediaMetadata.title?.toString() ?: "Unknown",
            artist = s.item.mediaMetadata.artist?.toString(),
            isVideo = LocalMedia.isVideoId(s.item.mediaId),
            startedAt = s.startedAtWall,
            listenedMs = s.listenedMs,
            durationMs = duration,
            skipped = !completed && (duration <= 0 || s.listenedMs < duration / 2),
            completed = completed,
            repeated = s.repeated,
        )
        scope.launch { dao.insert(event) }
    }

    private companion object {
        const val MIN_LOGGED_MS = 5_000L
    }
}
