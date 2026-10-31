package com.mydigitaldevil.musicplayer.soundboard

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mydigitaldevil.musicplayer.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.soundboardStore by preferencesDataStore("soundboard")

sealed interface AddPadResult {
    data class Added(val pad: SoundPad) : AddPadResult
    data object TooLong : AddPadResult
    data object Unreadable : AddPadResult
}

/** Bundled presets plus pads the user binds to their own mic FX recordings. */
@Singleton
class SoundboardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val userPadsKey = stringPreferencesKey("user_pads")
    private val pinnedKey = stringPreferencesKey("pinned")
    private val quickPadsKey = booleanPreferencesKey("quick_pads_enabled")

    val presets: List<SoundPad> = listOf(
        preset("rimshot", R.string.pad_rimshot, R.raw.pad_rimshot),
        preset("airhorn", R.string.pad_airhorn, R.raw.pad_airhorn),
        preset("boing", R.string.pad_boing, R.raw.pad_boing),
        preset("buzzer", R.string.pad_buzzer, R.raw.pad_buzzer),
        preset("sad_trombone", R.string.pad_sad_trombone, R.raw.pad_sad_trombone),
        preset("applause", R.string.pad_applause, R.raw.pad_applause),
        preset("crickets", R.string.pad_crickets, R.raw.pad_crickets),
    )

    /** All pads; user pads whose file has been deleted are dropped silently. */
    val pads: Flow<List<SoundPad>> = context.soundboardStore.data.map { prefs ->
        presets + SoundboardCodec.decodeUserPads(prefs[userPadsKey])
            .filter { (_, _, path) -> File(path).exists() }
            .map { (id, label, path) -> SoundPad(id, label, PadSource.Recording(path)) }
    }

    val pinnedIds: Flow<List<String>> =
        context.soundboardStore.data.map { SoundboardCodec.decodePinned(it[pinnedKey]) }

    /** Pinned pads in pin order, skipping any that no longer exist. */
    val pinnedPads: Flow<List<SoundPad>> = combine(pads, pinnedIds) { all, ids ->
        val byId = all.associateBy { it.id }
        ids.mapNotNull { byId[it] }
    }

    val quickPadsEnabled: Flow<Boolean> = context.soundboardStore.data.map { it[quickPadsKey] ?: false }

    suspend fun padById(id: String): SoundPad? = pads.first().firstOrNull { it.id == id }

    suspend fun togglePin(id: String): PinResult {
        var result = PinResult.UNPINNED
        context.soundboardStore.edit { prefs ->
            val (next, r) = SoundboardCodec.togglePinned(SoundboardCodec.decodePinned(prefs[pinnedKey]), id)
            prefs[pinnedKey] = SoundboardCodec.encodePinned(next)
            result = r
        }
        return result
    }

    suspend fun setQuickPadsEnabled(enabled: Boolean) {
        context.soundboardStore.edit { it[quickPadsKey] = enabled }
    }

    /**
     * Binds a recording to a new pad. Clips are capped at [MAX_PAD_DURATION_MS]: pads are drops,
     * and the quick-pad receiver must finish well inside the broadcast time limit.
     */
    suspend fun addRecording(file: File, label: String = file.nameWithoutExtension): AddPadResult {
        val durationMs = withContext(Dispatchers.IO) { durationOf(file) } ?: return AddPadResult.Unreadable
        if (durationMs > MAX_PAD_DURATION_MS) return AddPadResult.TooLong

        val pad = SoundPad("user:${UUID.randomUUID()}", SoundboardCodec.sanitizeLabel(label), PadSource.Recording(file.path))
        context.soundboardStore.edit { prefs ->
            val existing = SoundboardCodec.decodeUserPads(prefs[userPadsKey])
            prefs[userPadsKey] = SoundboardCodec.encodeUserPads(existing + Triple(pad.id, pad.label, file.path))
        }
        return AddPadResult.Added(pad)
    }

    /** Removes a user pad (the recording file itself is kept). Presets cannot be removed. */
    suspend fun remove(id: String) {
        context.soundboardStore.edit { prefs ->
            prefs[userPadsKey] = SoundboardCodec.encodeUserPads(
                SoundboardCodec.decodeUserPads(prefs[userPadsKey]).filterNot { it.first == id },
            )
            prefs[pinnedKey] = SoundboardCodec.encodePinned(
                SoundboardCodec.decodePinned(prefs[pinnedKey]) - id,
            )
        }
    }

    /** Recordings made with Live Mic FX that can be turned into pads, newest first. */
    suspend fun availableRecordings(): List<File> = withContext(Dispatchers.IO) {
        File(context.filesDir, RECORDINGS_DIR)
            .listFiles { f -> f.isFile && f.extension.lowercase() in RECORDING_EXTENSIONS }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
    }

    private fun preset(key: String, label: Int, raw: Int) =
        SoundPad("preset:$key", context.getString(label), PadSource.Preset(raw))

    // Not `use {}`: MediaMetadataRetriever is only AutoCloseable from API 29 (min SDK is 26).
    private fun durationOf(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val MAX_PAD_DURATION_MS = 15_000L
        private const val RECORDINGS_DIR = "recordings"
        private val RECORDING_EXTENSIONS = setOf("wav", "m4a")
    }
}
