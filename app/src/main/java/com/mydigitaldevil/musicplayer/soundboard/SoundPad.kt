package com.mydigitaldevil.musicplayer.soundboard

import androidx.annotation.RawRes

sealed interface PadSource {
    data class Preset(@RawRes val resId: Int) : PadSource
    /** A mic FX recording (or exported mix) in app-private storage. */
    data class Recording(val path: String) : PadSource
}

data class SoundPad(
    val id: String,
    val label: String,
    val source: PadSource,
) {
    val isPreset: Boolean get() = source is PadSource.Preset
}

enum class PinResult { PINNED, UNPINNED, LIMIT_REACHED }

/** Pure helpers behind [SoundboardRepository], kept free of Android types for unit tests. */
internal object SoundboardCodec {

    /** A notification shows at most three actions, so that is the hard cap on quick pads. */
    const val MAX_PINNED = 3

    fun togglePinned(current: List<String>, id: String): Pair<List<String>, PinResult> = when {
        id in current -> (current - id) to PinResult.UNPINNED
        current.size >= MAX_PINNED -> current to PinResult.LIMIT_REACHED
        else -> (current + id) to PinResult.PINNED
    }

    fun encodePinned(ids: List<String>): String = ids.joinToString(",")

    fun decodePinned(raw: String?): List<String> =
        raw?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** One user pad per line: `id<TAB>label<TAB>path`. Labels are sanitised on the way in. */
    fun encodeUserPads(pads: List<Triple<String, String, String>>): String =
        pads.joinToString("\n") { (id, label, path) -> "$id\t${sanitizeLabel(label)}\t$path" }

    fun decodeUserPads(raw: String?): List<Triple<String, String, String>> =
        raw?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 3 && parts.none { it.isBlank() }) Triple(parts[0], parts[1], parts[2]) else null
            }
            ?.toList()
            .orEmpty()

    fun sanitizeLabel(label: String): String =
        label.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(MAX_LABEL_LENGTH).ifEmpty { "Sound" }

    private const val MAX_LABEL_LENGTH = 24
}
