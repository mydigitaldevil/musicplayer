package com.mydigitaldevil.musicplayer.library

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Album-art / video-frame thumbnails straight from MediaStore, with an in-memory LRU cache.
 * Android 10+ uses `loadThumbnail` (system-cached, fast); older versions fall back to the
 * legacy album-art and video-thumbnail tables.
 */
@Singleton
class ThumbnailLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val misses = mutableSetOf<String>()

    suspend fun load(media: LocalMedia, sizePx: Int): ImageBitmap? {
        val key = "${media.mediaId}@$sizePx"
        cache.get(key)?.let { return it.asImageBitmap() }
        if (synchronized(misses) { key in misses }) return null
        val bitmap = withContext(Dispatchers.IO) { runCatching { decode(media, sizePx) }.getOrNull() }
        if (bitmap == null) synchronized(misses) { misses += key } else cache.put(key, bitmap)
        return bitmap?.asImageBitmap()
    }

    private fun decode(media: LocalMedia, sizePx: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return resolver.loadThumbnail(media.contentUri, Size(sizePx, sizePx), null)
        }
        return when (media.kind) {
            MediaKind.VIDEO -> {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(resolver, media.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            }
            MediaKind.AUDIO -> {
                val albumId = media.albumId ?: return null
                val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0) return null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= sizePx) sample *= 2
                resolver.openInputStream(artUri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }
    }
}

val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader> { error("ThumbnailLoader not provided") }

@Composable
fun MediaThumbnail(media: LocalMedia, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val loader = LocalThumbnailLoader.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, media.mediaId, sizePx) { value = loader.load(media, sizePx) }
    val shapeModifier = modifier.size(size).clip(MaterialTheme.shapes.small)
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = shapeModifier)
    } else {
        Box(shapeModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(
                if (media.kind == MediaKind.VIDEO) Icons.Filled.Movie else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
