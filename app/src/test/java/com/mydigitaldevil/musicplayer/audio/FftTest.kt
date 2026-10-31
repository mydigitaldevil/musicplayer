package com.mydigitaldevil.musicplayer.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

class FftTest {

    @Test
    fun `pure tone peaks at its bin`() {
        val n = 1024
        val bin = 37
        val re = FloatArray(n) { sin(2 * PI * bin * it / n).toFloat() }
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        val mags = FloatArray(n / 2) { hypot(re[it], im[it]) }
        assertThat(mags.indices.maxBy { mags[it] }).isEqualTo(bin)
        // A unit sine has magnitude N/2 at its bin.
        assertThat(mags[bin].toDouble()).isWithin(0.5).of(n / 2.0)
    }

    @Test
    fun `dc input lands in bin zero only`() {
        val n = 256
        val re = FloatArray(n) { 1f }
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        assertThat(re[0].toDouble()).isWithin(1e-3).of(n.toDouble())
        for (k in 1 until n) assertThat(hypot(re[k], im[k]).toDouble()).isLessThan(1e-3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non power of two is rejected`() {
        Fft(1000)
    }
}
