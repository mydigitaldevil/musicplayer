package com.mydigitaldevil.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mydigitaldevil.musicplayer.library.LocalMedia
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

data class NowPlaying(
    val mediaId: String? = null,
    val uri: Uri? = null,
    val title: String = "",
    val artist: String? = null,
    val artworkData: ByteArray? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val isVideo: Boolean = false,
) {
    val hasMedia: Boolean get() = mediaId != null

    // ByteArray breaks data-class equality; compare by content so StateFlow dedupes properly.
    override fun equals(other: Any?): Boolean = other is NowPlaying &&
        mediaId == other.mediaId && uri == other.uri && title == other.title && artist == other.artist &&
        artworkData.contentEquals(other.artworkData) && isPlaying == other.isPlaying &&
        isBuffering == other.isBuffering && durationMs == other.durationMs && shuffle == other.shuffle &&
        repeatMode == other.repeatMode && hasNext == other.hasNext && hasPrevious == other.hasPrevious &&
        isVideo == other.isVideo

    override fun hashCode(): Int = (mediaId?.hashCode() ?: 0) * 31 + isPlaying.hashCode()
}

/**
 * The UI's single handle on [PlaybackService], via a Media3 [MediaController].
 * Commands issued before the controller is connected are queued and replayed on connect.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var future: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** Idempotent. Called from the activity's onStart. */
    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    // Service was killed; drop the controller so the next connect() rebuilds it.
                    future = null
                    _controller.value = null
                    _nowPlaying.value = NowPlaying()
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run { future = null; return@addListener }
            c.addListener(listener)
            _controller.value = c
            publish(c)
            pending.toList().forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(items: List<MediaItem>, startIndex: Int, startPositionMs: Long = 0) = withController {
        it.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), startPositionMs)
        it.prepare()
        it.play()
    }

    fun playMedia(list: List<LocalMedia>, index: Int) = play(list.map(LocalMedia::toMediaItem), index)

    fun togglePlayPause() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition(); it.play() } }
    fun pause() = withController { it.pause() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun currentPositionMs(): Long = _controller.value?.currentPosition ?: 0L

    /** Position updates for seek bars; collected only while a screen is visible. */
    fun positionTicks(intervalMs: Long = 500): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(currentPositionMs())
            delay(intervalMs)
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = _controller.value
        if (c != null) block(c) else { pending += block; connect() }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val meta = player.mediaMetadata
        _nowPlaying.value = NowPlaying(
            mediaId = item?.mediaId,
            uri = item?.localConfiguration?.uri ?: item?.requestMetadata?.mediaUri,
            title = meta.title?.toString() ?: meta.displayTitle?.toString() ?: "",
            artist = meta.artist?.toString(),
            artworkData = meta.artworkData,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3_000,
            isVideo = LocalMedia.isVideoId(item?.mediaId) ||
                player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
        )
    }
}
