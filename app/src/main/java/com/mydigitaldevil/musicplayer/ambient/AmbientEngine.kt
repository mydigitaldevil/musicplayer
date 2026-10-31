package com.mydigitaldevil.musicplayer.ambient

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the [AmbientSynth] mix on its own AudioTrack, alongside (not instead of) the music
 * player. It deliberately requests no audio focus, so it never pauses the song.
 */
@Singleton
class AmbientEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _volumes = MutableStateFlow(AmbientSound.entries.associateWith { if (it == AmbientSound.RAIN) 0.5f else 0f })
    val volumes: StateFlow<Map<AmbientSound, Float>> = _volumes.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Sleep-timer fade, written by AmbientService. */
    @Volatile var master: Float = 1f

    @Volatile private var gains = FloatArray(AmbientSound.entries.size).also { fillGains(it, _volumes.value) }
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setVolume(sound: AmbientSound, volume: Float) {
        _volumes.update { it + (sound to volume.coerceIn(0f, 1f)) }
        gains = FloatArray(AmbientSound.entries.size).also { fillGains(it, _volumes.value) }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        _playing.value = true
        thread = Thread(::renderLoop, "AmbientEngine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        _playing.value = false
    }

    private fun renderLoop() {
        val sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes * 2, FRAMES_PER_WRITE * 8 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING) // latency is irrelevant here
                .build()
        }.getOrElse {
            Log.e(TAG, "AudioTrack unavailable", it)
            running = false
            _playing.value = false
            return
        }

        val synth = AmbientSynth(sampleRate)
        val buffer = FloatArray(FRAMES_PER_WRITE * 2)
        try {
            track.play()
            while (running) {
                synth.render(buffer, FRAMES_PER_WRITE, gains, master)
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    private fun fillGains(target: FloatArray, volumes: Map<AmbientSound, Float>) {
        // Perceptual curve: slider position squared feels linear in loudness.
        AmbientSound.entries.forEach { target[it.ordinal] = (volumes[it] ?: 0f).let { v -> v * v } }
    }

    private companion object {
        const val TAG = "AmbientEngine"
        const val FRAMES_PER_WRITE = 1024
    }
}
