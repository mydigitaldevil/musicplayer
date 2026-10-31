package com.mydigitaldevil.musicplayer.soundboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SoundboardCodecTest {

    @Test
    fun `pinning is capped at three and preserves order`() {
        var pinned = emptyList<String>()
        listOf("a", "b", "c").forEach { id ->
            val (next, result) = SoundboardCodec.togglePinned(pinned, id)
            assertThat(result).isEqualTo(PinResult.PINNED)
            pinned = next
        }
        val (unchanged, result) = SoundboardCodec.togglePinned(pinned, "d")
        assertThat(result).isEqualTo(PinResult.LIMIT_REACHED)
        assertThat(unchanged).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `toggling a pinned pad unpins it`() {
        val (next, result) = SoundboardCodec.togglePinned(listOf("a", "b"), "a")
        assertThat(result).isEqualTo(PinResult.UNPINNED)
        assertThat(next).containsExactly("b")
    }

    @Test
    fun `user pads round-trip and hostile labels cannot break the format`() {
        val pads = listOf(
            Triple("user:1", "My\tlaugh\nline", "/data/rec/1.wav"),
            Triple("user:2", "Echo", "/data/rec/2.m4a"),
        )
        val decoded = SoundboardCodec.decodeUserPads(SoundboardCodec.encodeUserPads(pads))
        assertThat(decoded).containsExactly(
            Triple("user:1", "My laugh line", "/data/rec/1.wav"),
            Triple("user:2", "Echo", "/data/rec/2.m4a"),
        ).inOrder()
    }

    @Test
    fun `corrupt lines are skipped`() {
        val decoded = SoundboardCodec.decodeUserPads("garbage\nuser:1\tOk\t/p.wav\n\t\t\n")
        assertThat(decoded).containsExactly(Triple("user:1", "Ok", "/p.wav"))
    }

    @Test
    fun `pinned ids decode tolerates empty input`() {
        assertThat(SoundboardCodec.decodePinned(null)).isEmpty()
        assertThat(SoundboardCodec.decodePinned("")).isEmpty()
        assertThat(SoundboardCodec.decodePinned("a,,b")).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `labels are trimmed, capped, and never empty`() {
        assertThat(SoundboardCodec.sanitizeLabel("   ")).isEqualTo("Sound")
        assertThat(SoundboardCodec.sanitizeLabel("x".repeat(40))).hasLength(24)
    }
}
