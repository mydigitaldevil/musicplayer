package com.mydigitaldevil.musicplayer.library

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

enum class MediaKind { AUDIO, VIDEO }

/** A song or video found in MediaStore. */
data class LocalMedia(
    val id: Long,
    val kind: MediaKind,
    val contentUri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val durationMs: Long,
    val dateAddedSec: Long,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** Stable id shared by the player queue and the stats database: `audio:12` / `video:7`. */
    val mediaId: String get() = mediaIdOf(kind, id)

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(contentUri)
        // The controller -> session hop may drop localConfiguration; the session rebuilds it from this.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(contentUri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setMediaType(if (kind == MediaKind.VIDEO) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

    companion object {
        fun mediaIdOf(kind: MediaKind, id: Long) = "${kind.name.lowercase()}:$id"
        fun isVideoId(mediaId: String?) = mediaId?.startsWith("video:") == true
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
