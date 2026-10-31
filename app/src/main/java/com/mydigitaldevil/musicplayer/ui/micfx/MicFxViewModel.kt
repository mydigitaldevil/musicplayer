package com.mydigitaldevil.musicplayer.ui.micfx

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mydigitaldevil.musicplayer.ads.RewardUnlockStore
import com.mydigitaldevil.musicplayer.ads.RewardedUnlock
import com.mydigitaldevil.musicplayer.audio.fx.MicFxEngine
import com.mydigitaldevil.musicplayer.audio.fx.MicFxState
import com.mydigitaldevil.musicplayer.audio.fx.RecordingExporter
import com.mydigitaldevil.musicplayer.audio.fx.VoicePreset
import com.mydigitaldevil.musicplayer.playback.PlayerConnection
import com.mydigitaldevil.musicplayer.soundboard.AddPadResult
import com.mydigitaldevil.musicplayer.soundboard.PadSource
import com.mydigitaldevil.musicplayer.soundboard.SoundPad
import com.mydigitaldevil.musicplayer.soundboard.SoundboardPlayer
import com.mydigitaldevil.musicplayer.soundboard.SoundboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject

data class RecordingInfo(
    val file: File,
    val durationMs: Long,
    val hasBeat: Boolean,
    val isMix: Boolean,
)

sealed interface MicFxEvent {
    data class Exported(val file: File) : MicFxEvent
    data object ExportFailed : MicFxEvent
    data object SavedToMusic : MicFxEvent
    data object AddedToSoundboard : MicFxEvent
    data object SoundboardTooLong : MicFxEvent
}

/** Voices behind the EXCLUSIVE_MIC_FILTERS rewarded unlock. */
val EXCLUSIVE_VOICES = setOf(VoicePreset.DEEP_MONSTER, VoicePreset.WHISPER, VoicePreset.ANNOUNCER)

@HiltViewModel
class MicFxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val engine: MicFxEngine,
    private val exporter: RecordingExporter,
    private val player: PlayerConnection,
    private val previewPlayer: SoundboardPlayer,
    private val soundboard: SoundboardRepository,
    unlocks: RewardUnlockStore,
) : ViewModel() {

    val state: StateFlow<MicFxState> = engine.state

    val exclusiveUntil: StateFlow<Long> = unlocks.unlockedUntil(RewardedUnlock.EXCLUSIVE_MIC_FILTERS)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _recordings = MutableStateFlow<List<RecordingInfo>>(emptyList())
    val recordings: StateFlow<List<RecordingInfo>> = _recordings.asStateFlow()

    private val _exporting = MutableStateFlow<File?>(null)
    val exporting: StateFlow<File?> = _exporting.asStateFlow()

    private val _events = MutableSharedFlow<MicFxEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MicFxEvent> = _events.asSharedFlow()

    private var beatAtStart: Pair<Uri, Long>? = null
    private val dir get() = File(context.filesDir, "recordings").apply { mkdirs() }

    init { refresh() }

    fun isExclusiveUnlocked() = exclusiveUntil.value > System.currentTimeMillis()

    /** Title of the song that will be mixed under the recording, if music is playing. */
    fun beatTitle(): String? = player.nowPlaying.value.takeIf { it.isPlaying && !it.isVideo }?.title

    fun startRecording() {
        val np = player.nowPlaying.value
        beatAtStart = np.uri?.takeIf { np.isPlaying && !np.isVideo }?.let { it to player.currentPositionMs() }
        engine.startRecording(dir)
    }

    fun stopRecording() = viewModelScope.launch {
        val file = engine.stopRecording() ?: return@launch
        beatAtStart?.let { (uri, pos) -> withContext(Dispatchers.IO) { sidecar(file).writeText("$uri\n$pos") } }
        beatAtStart = null
        refresh()
    }

    fun export(rec: RecordingInfo) = viewModelScope.launch {
        if (_exporting.value != null) return@launch
        _exporting.value = rec.file
        val beat = withContext(Dispatchers.IO) { readBeat(rec.file) }
            ?.let { (uri, pos) -> RecordingExporter.Beat(uri, pos, rec.durationMs) }
        val out = File(dir, "${rec.file.nameWithoutExtension}_mix.m4a")
        val result = runCatching { exporter.exportM4a(rec.file, beat, out) }
        _exporting.value = null
        refresh()
        _events.emit(result.fold({ MicFxEvent.Exported(it) }, { MicFxEvent.ExportFailed }))
    }

    fun preview(rec: RecordingInfo) {
        previewPlayer.stopAll()
        previewPlayer.play(SoundPad("preview", rec.file.nameWithoutExtension, PadSource.Recording(rec.file.path)))
    }

    fun stopPreview() = previewPlayer.stopAll()

    fun delete(rec: RecordingInfo) = viewModelScope.launch(Dispatchers.IO) {
        rec.file.delete()
        sidecar(rec.file).delete()
        refresh()
    }

    fun addToSoundboard(rec: RecordingInfo) = viewModelScope.launch {
        when (soundboard.addRecording(rec.file)) {
            is AddPadResult.Added -> _events.emit(MicFxEvent.AddedToSoundboard)
            AddPadResult.TooLong -> _events.emit(MicFxEvent.SoundboardTooLong)
            AddPadResult.Unreadable -> _events.emit(MicFxEvent.ExportFailed)
        }
    }

    /** Copies into the shared Music/Background Play folder (Android 10+, no storage permission needed). */
    fun saveToMusic(rec: RecordingInfo) = viewModelScope.launch(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@launch
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, rec.file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, if (rec.file.extension == "m4a") "audio/mp4" else "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Background Play")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@launch
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> rec.file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (ok) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            _events.emit(MicFxEvent.SavedToMusic)
        } else {
            resolver.delete(uri, null, null)
            _events.emit(MicFxEvent.ExportFailed)
        }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _recordings.value = dir.listFiles { f -> f.extension.lowercase() in setOf("wav", "m4a") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { f -> RecordingInfo(f, durationOf(f), sidecar(f).exists(), f.extension.lowercase() == "m4a") }
    }

    override fun onCleared() {
        previewPlayer.stopAll()
        super.onCleared()
    }

    private fun sidecar(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.beat")

    private fun readBeat(file: File): Pair<Uri, Long>? = runCatching {
        val lines = sidecar(file).readLines()
        Uri.parse(lines[0]) to lines[1].toLong()
    }.getOrNull()

    private fun durationOf(file: File): Long = runCatching {
        if (file.extension.lowercase() == "wav") {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24)
                val b = ByteArray(4).also { raf.readFully(it) }
                val sampleRate = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                    ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
                if (sampleRate <= 0) 0L else (file.length() - 44) / 2 * 1000 / sampleRate // 16-bit mono
            }
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        }
    }.getOrDefault(0L)
}
