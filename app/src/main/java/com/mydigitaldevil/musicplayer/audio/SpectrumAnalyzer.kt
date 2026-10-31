package com.mydigitaldevil.musicplayer.audio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Taps the player's decoded PCM (via a TeeAudioProcessor in the playback service) and publishes
 * 32 log-spaced spectrum bands plus a short waveform for the visualizers.
 *
 * Uses no RECORD_AUDIO / `android.media.audiofx.Visualizer`. It does nothing unless a screen has
 * called [acquire], so there's zero cost while visualizers are off-screen.
 *
 * The tap sits before the AudioTrack buffer, so frames lead what you hear by roughly the output
 * latency (~100–200 ms). Smoothing hides most of that.
 */
@OptIn(UnstableApi::class)
@Singleton
class SpectrumAnalyzer @Inject constructor() : TeeAudioProcessor.AudioBufferSink {

    private val _bands = MutableStateFlow(FloatArray(BANDS))
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    private val _waveform = MutableStateFlow(FloatArray(WAVE_POINTS))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    private val users = AtomicInteger(0)

    @Volatile private var sampleRate = 44_100
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    // Playback-thread-only state.
    private val fft = Fft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * Math.PI * it / (FFT_SIZE - 1))).toFloat() }
    private val ring = FloatArray(FFT_SIZE)
    private var ringFill = 0
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val smoothed = FloatArray(BANDS)
    private var lastPublish = 0L

    fun acquire() { users.incrementAndGet() }

    fun release() {
        if (users.decrementAndGet() <= 0) {
            users.set(0)
            smoothed.fill(0f)
            _bands.value = FloatArray(BANDS)
            _waveform.value = FloatArray(WAVE_POINTS)
        }
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        ringFill = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (users.get() == 0) return
        val ch = channels.coerceAtLeast(1)
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        if (!isFloat && encoding != C.ENCODING_PCM_16BIT) return
        val bytesPerFrame = ch * if (isFloat) 4 else 2
        // duplicate() resets byte order to big-endian; ExoPlayer PCM is always native order.
        val buf = buffer.duplicate().order(ByteOrder.nativeOrder())
        while (buf.remaining() >= bytesPerFrame) {
            var sum = 0f
            repeat(ch) { sum += if (isFloat) buf.getFloat() else buf.getShort() / 32768f }
            ring[ringFill++] = sum / ch
            if (ringFill == FFT_SIZE) {
                analyze()
                ringFill = 0
            }
        }
    }

    private fun analyze() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublish < FRAME_INTERVAL_MS) return
        lastPublish = now

        for (i in 0 until FFT_SIZE) { re[i] = ring[i] * window[i]; im[i] = 0f }
        fft.transform(re, im)

        val binHz = sampleRate.toFloat() / FFT_SIZE
        val nyquistBin = FFT_SIZE / 2
        val out = FloatArray(BANDS)
        val logMin = ln(MIN_HZ)
        val logMax = ln(minOf(MAX_HZ, sampleRate / 2f))
        for (b in 0 until BANDS) {
            val lo = exp(logMin + (logMax - logMin) * b / BANDS)
            val hi = exp(logMin + (logMax - logMin) * (b + 1) / BANDS)
            val from = (lo / binHz).toInt().coerceIn(1, nyquistBin - 1)
            val to = (hi / binHz).toInt().coerceIn(from + 1, nyquistBin)
            var peak = 0f
            for (k in from until to) peak = max(peak, sqrt(re[k] * re[k] + im[k] * im[k]))
            // Magnitude -> dBFS (a full-scale sine through a Hann window peaks at N/4), plus up to
            // +9 dB treble tilt so highs don't look dead next to the bass. Then [-60, 0] -> [0, 1].
            val db = 20f * log10(peak / (FFT_SIZE / 4f) + 1e-9f) + 9f * sqrt(b.toFloat() / BANDS)
            val level = ((db + 60f) / 60f).coerceIn(0f, 1f)
            // Fast attack, slow release reads as "musical".
            smoothed[b] = if (level > smoothed[b]) level else smoothed[b] * 0.82f + level * 0.18f
            out[b] = smoothed[b]
        }
        _bands.value = out

        val wave = FloatArray(WAVE_POINTS)
        val stride = FFT_SIZE / WAVE_POINTS
        for (i in 0 until WAVE_POINTS) wave[i] = ring[i * stride].coerceIn(-1f, 1f)
        _waveform.value = wave
    }

    companion object {
        const val BANDS = 32
        const val WAVE_POINTS = 128
        private const val FFT_SIZE = 2048
        private const val FRAME_INTERVAL_MS = 33L // ~30 fps
        private const val MIN_HZ = 40f
        private const val MAX_HZ = 16_000f
    }
}
