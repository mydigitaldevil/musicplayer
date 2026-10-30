package com.mydigitaldevil.musicplayer.audio.fx

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exports a mic recording to AAC `.m4a`, optionally mixed with the backing track that was
 * playing while it was recorded. Uses Media3 Transformer, so encoding is hardware-accelerated
 * and nothing is decoded into memory at once.
 */
@OptIn(UnstableApi::class)
@Singleton
class RecordingExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Beat(val uri: Uri, val startPositionMs: Long, val durationMs: Long)

    suspend fun exportM4a(vocalWav: File, beat: Beat?, output: File): File = withContext(Dispatchers.Main) {
        val vocal = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(vocalWav))).build()
        val sequences = buildList {
            add(EditedMediaItemSequence(vocal))
            if (beat != null) {
                val beatItem = MediaItem.Builder()
                    .setUri(beat.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(beat.startPositionMs)
                            .setEndPositionMs(beat.startPositionMs + beat.durationMs)
                            .build(),
                    )
                    .build()
                add(EditedMediaItemSequence(EditedMediaItem.Builder(beatItem).setRemoveVideo(true).build()))
            }
        }
        val composition = Composition.Builder(sequences).build()

        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        cont.resume(output)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        output.delete()
                        cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(composition, output.absolutePath)
        }
    }
}
