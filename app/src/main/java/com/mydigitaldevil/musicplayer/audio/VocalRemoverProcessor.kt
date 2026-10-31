package com.mydigitaldevil.musicplayer.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shared control between the karaoke screen and the playback service (same process). */
@Singleton
class KaraokeAudioControl @Inject constructor() {
    private val _vocalRemoval = MutableStateFlow(0f)
    val vocalRemoval: StateFlow<Float> = _vocalRemoval.asStateFlow()

    /** Read on the audio thread; [setVocalRemoval] is the only writer. */
    @Volatile var amount: Float = 0f
        private set

    fun setVocalRemoval(value: Float) {
        amount = value.coerceIn(0f, 1f)
        _vocalRemoval.value = amount
    }
}

/**
 * Offline vocal remover: centre-channel cancellation.
 *
 * Lead vocals are almost always mixed dead centre (equal in L and R), so they live in the
 * mid signal `(L+R)/2`, while most instruments have stereo spread in the side `(L-R)/2`.
 * We remove the mid, except below ~150 Hz, where kick and bass are also centred and would
 * otherwise disappear with the vocal. `amount` blends between original and karaoke.
 *
 * Limits: centre-panned instruments (snare, some keys) are attenuated too, reverb tails of the
 * vocal survive, and mono files can't be processed (nothing to cancel against).
 */
@OptIn(UnstableApi::class)
class VocalRemoverProcessor(private val control: KaraokeAudioControl) : BaseAudioProcessor() {

    private var isFloat = false
    // 2nd-order low-pass (RBJ) on the mid channel.
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!supported) return AudioProcessor.AudioFormat.NOT_SET // inactive: audio passes through untouched
        isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        designLowPass(inputAudioFormat.sampleRate.toFloat(), BASS_KEEP_HZ)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val amount = control.amount
        if (amount <= 0f) {
            z1 = 0f; z2 = 0f
            out.put(inputBuffer)
        } else if (isFloat) {
            while (inputBuffer.remaining() >= 8) {
                process(inputBuffer.getFloat(), inputBuffer.getFloat(), amount) { l, r -> out.putFloat(l); out.putFloat(r) }
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                process(inputBuffer.getShort() / 32768f, inputBuffer.getShort() / 32768f, amount) { l, r ->
                    out.putShort(toPcm16(l)); out.putShort(toPcm16(r))
                }
            }
        }
        out.flip()
    }

    private inline fun process(l: Float, r: Float, amount: Float, emit: (Float, Float) -> Unit) {
        val mid = (l + r) * 0.5f
        val side = (l - r) * 0.5f
        val low = b0 * mid + z1
        z1 = b1 * mid - a1 * low + z2
        z2 = b2 * mid - a2 * low
        val newMid = mid + (low - mid) * amount
        emit(newMid + side, newMid - side)
    }

    private fun designLowPass(sampleRate: Float, freq: Float) {
        val w0 = 2f * PI.toFloat() * freq / sampleRate
        val alpha = sin(w0) / (2f * 0.707f)
        val cosw = cos(w0)
        val a0 = 1f + alpha
        b0 = (1f - cosw) / 2f / a0; b1 = (1f - cosw) / a0; b2 = b0
        a1 = -2f * cosw / a0; a2 = (1f - alpha) / a0
        z1 = 0f; z2 = 0f
    }

    override fun onFlush() { z1 = 0f; z2 = 0f }
    override fun onReset() { z1 = 0f; z2 = 0f }

    private fun toPcm16(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private companion object {
        const val BASS_KEEP_HZ = 150f
    }
}
