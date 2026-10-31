package com.mydigitaldevil.musicplayer.ambient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/** Ordinals index [AmbientSynth.render]'s gain array; append only. */
enum class AmbientSound { RAIN, BONFIRE, CAFE, RIVER, WHITE_NOISE, BINAURAL }

/**
 * Procedural ambience generator. Every sound is synthesised live, so there are no audio assets
 * and no audible loop points, however long it runs. Pure Kotlin with no Android types, so it
 * runs in unit tests.
 */
class AmbientSynth(private val sampleRate: Int) {

    private val sr = sampleRate.toFloat()
    private val rain = Rain()
    private val fire = Bonfire()
    private val cafe = Cafe()
    private val river = River()
    private val white = Stereo { Rng(it) }
    private val binaural = Binaural()
    private val smoothed = FloatArray(AmbientSound.entries.size)

    /**
     * Renders [frames] interleaved stereo frames into [out] (size >= frames * 2).
     * [gains] are per-sound targets in 0..1; [master] scales everything (sleep-timer fade).
     */
    fun render(out: FloatArray, frames: Int, gains: FloatArray, master: Float) {
        val n = AmbientSound.entries.size
        for (f in 0 until frames) {
            // ~20 ms one-pole smoothing so slider moves never click.
            for (i in 0 until n) smoothed[i] += (gains[i] * TRIM[i] - smoothed[i]) * GAIN_SMOOTHING
            var l = 0f
            var r = 0f
            if (smoothed[0] > SILENT) { rain.next(); l += rain.l * smoothed[0]; r += rain.r * smoothed[0] }
            if (smoothed[1] > SILENT) { fire.next(); l += fire.l * smoothed[1]; r += fire.r * smoothed[1] }
            if (smoothed[2] > SILENT) { cafe.next(); l += cafe.l * smoothed[2]; r += cafe.r * smoothed[2] }
            if (smoothed[3] > SILENT) { river.next(); l += river.l * smoothed[3]; r += river.r * smoothed[3] }
            if (smoothed[4] > SILENT) {
                l += white.left.next() * 0.25f * smoothed[4]; r += white.right.next() * 0.25f * smoothed[4]
            }
            if (smoothed[5] > SILENT) { binaural.next(); l += binaural.l * smoothed[5]; r += binaural.r * smoothed[5] }
            out[2 * f] = tanh(l * master)
            out[2 * f + 1] = tanh(r * master)
        }
    }

    // ---- Building blocks --------------------------------------------------------------------

    private class Rng(seed: Int) {
        private var s = seed or 1
        /** Uniform in [-1, 1). */
        fun next(): Float {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            return s * (1f / Int.MAX_VALUE.toFloat())
        }
        /** Uniform in [0, 1). */
        fun unit(): Float = (next() + 1f) * 0.5f
    }

    private class Stereo<T>(factory: (Int) -> T) {
        val left = factory(0x1234567)
        val right = factory(0x7654321)
    }

    /** Paul Kellet's economy pink-noise filter. */
    private class Pink(seed: Int) {
        private val rng = Rng(seed)
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
        fun next(): Float {
            val w = rng.next()
            b0 = 0.99765f * b0 + w * 0.0990460f
            b1 = 0.96300f * b1 + w * 0.2965164f
            b2 = 0.57000f * b2 + w * 1.0526913f
            return (b0 + b1 + b2 + w * 0.1848f) * 0.25f
        }
    }

    private class Brown(seed: Int) {
        private val rng = Rng(seed)
        private var last = 0f
        fun next(): Float {
            last = (last + 0.02f * rng.next()) / 1.02f
            return last * 3.5f
        }
    }

    private inner class OnePole(cutoffHz: Float) {
        private var a = 0f
        private var z = 0f
        init { setCutoff(cutoffHz) }
        fun setCutoff(hz: Float) { a = exp(-2f * PI.toFloat() * hz / sr) }
        fun lp(x: Float): Float { z = (1f - a) * x + a * z; return z }
        fun hp(x: Float): Float = x - lp(x)
    }

    private inner class BandPass(freq: Float, q: Float) {
        private var b0 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f
        init { set(freq, q) }
        fun set(freq: Float, q: Float) {
            val w0 = 2f * PI.toFloat() * freq.coerceIn(20f, sr * 0.45f) / sr
            val alpha = sin(w0) / (2f * q)
            val a0 = 1f + alpha
            b0 = alpha / a0; b2 = -alpha / a0
            a1 = -2f * cos(w0) / a0; a2 = (1f - alpha) / a0
        }
        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = -a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    /** A short decaying event (drop, crackle, clink) with its own pan. */
    private class Burst {
        var env = 0f
        var decay = 0f
        var panL = 0.5f
        var panR = 0.5f
        val active get() = env > 1e-4f
    }

    private fun trigger(pool: Array<Burst>, rng: Rng, amp: Float, decaySec: Float) {
        val b = pool.firstOrNull { !it.active } ?: return
        b.env = amp
        b.decay = exp(-1f / (decaySec * sr))
        val pan = rng.unit()
        b.panL = cos(pan * PI.toFloat() / 2)
        b.panR = sin(pan * PI.toFloat() / 2)
    }

    // ---- Sounds -----------------------------------------------------------------------------

    private inner class Rain {
        var l = 0f; var r = 0f
        private val pink = Stereo { Pink(it) }
        private val hpL = OnePole(450f); private val hpR = OnePole(450f)
        private val dropFilter = BandPass(3_200f, 1.2f)
        private val drops = Array(24) { Burst() }
        private val rng = Rng(42)
        fun next() {
            l = hpL.hp(pink.left.next()) * 0.9f
            r = hpR.hp(pink.right.next()) * 0.9f
            if (rng.unit() < 40f / sr) trigger(drops, rng, 0.2f + 0.5f * rng.unit(), 0.004f + 0.01f * rng.unit())
            var dl = 0f; var dr = 0f
            for (d in drops) if (d.active) {
                val s = d.env * rng.next()
                dl += s * d.panL; dr += s * d.panR
                d.env *= d.decay
            }
            val drop = dropFilter.process(dl + dr)
            l += dl * 0.4f + drop * 0.6f
            r += dr * 0.4f + drop * 0.6f
        }
    }

    private inner class Bonfire {
        var l = 0f; var r = 0f
        private val rumble = Stereo { Brown(it) }
        private val lpL = OnePole(260f); private val lpR = OnePole(260f)
        private val crackles = Array(16) { Burst() }
        private val crackleHp = OnePole(900f)
        private val rng = Rng(7)
        private val flicker = Brown(99)
        fun next() {
            val swell = 0.7f + 0.3f * abs(flicker.next())
            l = lpL.lp(rumble.left.next()) * 0.9f * swell
            r = lpR.lp(rumble.right.next()) * 0.9f * swell
            if (rng.unit() < 7f / sr) {
                // Crackles come in little clusters.
                repeat(1 + (rng.unit() * 3).toInt()) { trigger(crackles, rng, 0.3f + 0.7f * rng.unit(), 0.001f + 0.005f * rng.unit()) }
            }
            var cl = 0f; var cr = 0f
            for (c in crackles) if (c.active) {
                val s = c.env * rng.next()
                cl += s * c.panL; cr += s * c.panR
                c.env *= c.decay
            }
            val hp = crackleHp.hp(cl + cr)
            l += cl * 0.3f + hp * 0.5f
            r += cr * 0.3f + hp * 0.5f
        }
    }

    private inner class Cafe {
        var l = 0f; var r = 0f
        private val voices = Array(4) { i -> Pink(100 + i) }
        private val formants = Array(4) { i -> BandPass(if (i % 2 == 0) 520f else 1_150f, 0.9f) }
        private val syllablePhase = FloatArray(4)
        private val syllableRate = FloatArray(4) { 3f + it * 0.7f }
        private val voicePan = floatArrayOf(0.2f, 0.8f, 0.4f, 0.65f)
        private val rng = Rng(2024)
        private val clinks = Array(4) { Burst() }
        private val clinkPhase = FloatArray(4)
        private val clinkFreq = FloatArray(4)
        fun next() {
            l = 0f; r = 0f
            for (i in voices.indices) {
                syllablePhase[i] += syllableRate[i] / sr
                if (syllablePhase[i] >= 1f) {
                    syllablePhase[i] -= 1f
                    syllableRate[i] = 2.5f + 3f * rng.unit() // irregular speech rhythm
                }
                val env = sin(PI.toFloat() * syllablePhase[i]).let { it * it }
                val v = formants[i].process(voices[i].next()) * env * 1.6f
                l += v * (1f - voicePan[i]); r += v * voicePan[i]
            }
            if (rng.unit() < 0.3f / sr) {
                val slot = clinks.indexOfFirst { !it.active }
                if (slot >= 0) {
                    clinkFreq[slot] = 2_200f + 1_800f * rng.unit()
                    trigger(clinks, rng, 0.12f, 0.12f)
                }
            }
            for (i in clinks.indices) {
                val c = clinks[i]
                if (!c.active) continue
                clinkPhase[i] += clinkFreq[i] / sr
                val tone = sin(2f * PI.toFloat() * clinkPhase[i]) + 0.5f * sin(2f * PI.toFloat() * clinkPhase[i] * 2.76f)
                l += tone * c.env * c.panL; r += tone * c.env * c.panR
                c.env *= c.decay
            }
            // Slight cross-feed so the murmur sounds like one room, not two speakers.
            val ml = l
            l = ml * 0.8f + r * 0.2f
            r = r * 0.8f + ml * 0.2f
        }
    }

    private inner class River {
        var l = 0f; var r = 0f
        private val water = Stereo { Brown(it) }
        private val sparkle = Stereo { Pink(it) }
        private val bpL = BandPass(600f, 0.7f); private val bpR = BandPass(650f, 0.7f)
        private val sparkleHp = OnePole(1_800f)
        private val drift = Brown(555)
        private var counter = 0
        fun next() {
            if (++counter >= 64) {
                // Slowly wandering resonance gives the "gurgle".
                counter = 0
                val d = drift.next()
                bpL.set(600f + 300f * d, 0.7f)
                bpR.set(650f - 280f * d, 0.7f)
            }
            l = bpL.process(water.left.next()) * 2.2f + sparkleHp.hp(sparkle.left.next()) * 0.15f
            r = bpR.process(water.right.next()) * 2.2f + sparkle.right.next() * 0.06f
        }
    }

    /** 200 Hz left / 210 Hz right gives a 10 Hz (alpha) beat. Only works on headphones. */
    private inner class Binaural {
        var l = 0f; var r = 0f
        private var pl = 0f
        private var pr = 0f
        fun next() {
            pl += 200f / sr; if (pl >= 1f) pl -= 1f
            pr += 210f / sr; if (pr >= 1f) pr -= 1f
            l = sin(2f * PI.toFloat() * pl) * 0.22f
            r = sin(2f * PI.toFloat() * pr) * 0.22f
        }
    }

    private companion object {
        /**
         * Per-sound loudness trims (RAIN, BONFIRE, CAFE, RIVER, WHITE_NOISE, BINAURAL) so the same
         * slider position sounds about equally loud for every sound (~0.16 RMS each, measured).
         */
        val TRIM = floatArrayOf(0.65f, 1.4f, 0.9f, 0.75f, 1.1f, 1.05f)
        const val GAIN_SMOOTHING = 0.0012f
        const val SILENT = 1e-4f
    }
}
