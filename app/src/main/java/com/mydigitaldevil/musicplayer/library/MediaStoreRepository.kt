package com.mydigitaldevil.musicplayer.library

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads songs and videos from MediaStore and re-queries whenever the media database changes. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver = context.contentResolver

    private val audioCollection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private val videoCollection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    fun audio(): Flow<List<LocalMedia>> = observe(audioCollection).mapLatest { queryAudio() }.flowOn(Dispatchers.IO)

    fun video(): Flow<List<LocalMedia>> = observe(videoCollection).mapLatest { queryVideo() }.flowOn(Dispatchers.IO)

    /** Title for a file opened from another app (VIEW intent), where only a URI is known. */
    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
            }
        }.getOrNull()
    }

    private fun observe(uri: Uri): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(uri, true, observer)
        trySend(Unit) // initial load
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate() // a media scan fires many changes; only the latest matters

    private fun queryAudio(): List<LocalMedia> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        // Skip ringtones/notification blips: anything shorter than 10 s.
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val result = mutableListOf<LocalMedia>()
        runCatching {
            resolver.query(audioCollection, projection, selection, arrayOf("10000"), null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += LocalMedia(
                        id = id,
                        kind = MediaKind.AUDIO,
                        contentUri = ContentUris.withAppendedId(audioCollection, id),
                        title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown",
                        artist = c.getString(artistCol)?.takeUnless { it.isBlank() || it == UNKNOWN },
                        album = c.getString(albumCol)?.takeUnless { it.isBlank() || it == UNKNOWN },
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durationCol),
                        dateAddedSec = c.getLong(dateCol),
                    )
                }
            }
        }
        return result.sortedBy { it.title.lowercase() }
    }

    private fun queryVideo(): List<LocalMedia> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val result = mutableListOf<LocalMedia>()
        runCatching {
            resolver.query(videoCollection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += LocalMedia(
                        id = id,
                        kind = MediaKind.VIDEO,
                        contentUri = ContentUris.withAppendedId(videoCollection, id),
                        title = c.getString(titleCol)?.takeIf { it.isNotBlank() }
                            ?: c.getString(nameCol)?.substringBeforeLast('.')
                            ?: "Video",
                        artist = null,
                        album = null,
                        albumId = null,
                        durationMs = c.getLong(durationCol),
                        dateAddedSec = c.getLong(dateCol),
                        width = c.getInt(widthCol),
                        height = c.getInt(heightCol),
                    )
                }
            }
        }
        return result.sortedByDescending { it.dateAddedSec } // newest videos first, like a gallery
    }

    private companion object {
        const val UNKNOWN = "<unknown>"
    }
}
