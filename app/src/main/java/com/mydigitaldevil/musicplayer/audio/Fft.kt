package com.mydigitaldevil.musicplayer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 FFT. [re] and [im] must have the same power-of-two length. */
internal class Fft(private val n: Int) {
    init {
        require(n > 1 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    private val cosTable = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { -sin(2 * PI * it / n).toFloat() }
    private val bitReverse = IntArray(n).also { table ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val l = j + half
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
