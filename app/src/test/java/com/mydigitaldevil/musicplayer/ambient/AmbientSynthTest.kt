package com.mydigitaldevil.musicplayer.ambient

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AmbientSynthTest {

    private val sampleRate = 48_000
    private val frames = 1024

    private fun render(gains: FloatArray, master: Float = 1f, seconds: Int = 3): FloatArray {
        val synth = AmbientSynth(sampleRate)
        val out = FloatArray(frames * 2)
        val all = ArrayList<Float>()
        repeat(sampleRate * seconds / frames) {
            synth.render(out, frames, gains, master)
            out.forEach(all::add)
        }
        return all.toFloatArray()
    }

    private fun rms(x: FloatArray, from: Int = x.size / 3) =
        sqrt((from until x.size).sumOf { (x[it] * x[it]).toDouble() } / (x.size - from))

    @Test
    fun `all gains zero is silent`() {
        val out = render(FloatArray(AmbientSound.entries.size))
        assertThat(out.maxOf { abs(it) }).isEqualTo(0f)
    }

    @Test
    fun `every sound is audible, finite and bounded on its own`() {
        AmbientSound.entries.forEach { sound ->
            val gains = FloatArray(AmbientSound.entries.size).also { it[sound.ordinal] = 1f }
            val out = render(gains)
            assertThat(out.all { it.isFinite() }).isTrue()
            assertThat(out.maxOf { abs(it) }).isAtMost(1f)
            assertWithMessage("rms of $sound").that(rms(out)).isGreaterThan(0.01)
        }
    }

    @Test
    fun `everything at full volume never clips past full scale`() {
        val out = render(FloatArray(AmbientSound.entries.size) { 1f })
        assertThat(out.all { it.isFinite() && abs(it) <= 1f }).isTrue()
    }

    @Test
    fun `master zero silences the mix`() {
        val out = render(FloatArray(AmbientSound.entries.size) { 1f }, master = 0f)
        assertThat(out.maxOf { abs(it) }).isEqualTo(0f)
    }
}
