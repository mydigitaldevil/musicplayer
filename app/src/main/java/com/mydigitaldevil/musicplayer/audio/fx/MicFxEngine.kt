package com.mydigitaldevil.musicplayer.audio.fx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Ordinals are the native enum values in mic_fx_engine.h; append only. */
enum class VoicePreset { NONE, BABY, CHIPMUNK, ROBOT, MEGAPHONE, DEEP_MONSTER, WHISPER, ANNOUNCER }
enum class AmbiencePreset { NONE, STADIUM, CLUB, BATHROOM, SPACE_DELAY }

enum class HeadphoneRoute(val isLowLatency: Boolean) { WIRED(true), USB(true), BLUETOOTH(false) }

sealed interface MicFxError {
    data object PermissionMissing : MicFxError
    /** Monitoring through the loudspeaker would howl with feedback, so it is refused. */
    data object HeadphonesRequired : MicFxError
    data object HeadphonesDisconnected : MicFxError
    data object StreamFailed : MicFxError
}

data class MicFxState(
    val running: Boolean = false,
    val recording: Boolean = false,
    val voice: VoicePreset = VoicePreset.NONE,
    val ambience: AmbiencePreset = AmbiencePreset.NONE,
    val wet: Float = 0.35f,
    val inputGain: Float = 1f,
    val route: HeadphoneRoute? = null,
    val outputLatencyMs: Double? = null,
    val error: MicFxError? = null,
)

/**
 * Kotlin facade over the native Oboe duplex engine.
 *
 * Owns the safety policy (permission + headphones-only monitoring, auto-stop on unplug) and
 * the recording drain that turns the native lock-free queue into a WAV file.
 */
@Singleton
class MicFxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handle: Long = NativeMicFx.nativeCreate()

    private val _state = MutableStateFlow(MicFxState())
    val state: StateFlow<MicFxState> = _state.asStateFlow()

    private var recordJob: Job? = null
    private var recordFile: File? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (_state.value.running && currentHeadphoneRoute() == null) {
                stop()
                _state.update { it.copy(error = MicFxError.HeadphonesDisconnected) }
            }
        }
    }

    fun start(): Boolean {
        if (_state.value.running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return fail(MicFxError.PermissionMissing)
        val route = currentHeadphoneRoute() ?: return fail(MicFxError.HeadphonesRequired)

        // Device id 0 = let the system route: built-in/headset mic in, current headphones out.
        if (!NativeMicFx.nativeStart(handle, 0, 0)) return fail(MicFxError.StreamFailed)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        val s = _state.value
        NativeMicFx.nativeSetVoice(handle, s.voice.ordinal)
        NativeMicFx.nativeSetAmbience(handle, s.ambience.ordinal)
        NativeMicFx.nativeSetWet(handle, s.wet)
        NativeMicFx.nativeSetInputGain(handle, s.inputGain)
        NativeMicFx.nativeSetMonitorEnabled(handle, true)
        _state.update {
            it.copy(
                running = true,
                route = route,
                outputLatencyMs = NativeMicFx.nativeOutputLatencyMs(handle).takeIf { ms -> ms >= 0 },
                error = null,
            )
        }
        return true
    }

    fun stop() {
        if (_state.value.recording) recordJob?.cancel()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        NativeMicFx.nativeSetRecording(handle, false)
        NativeMicFx.nativeStop(handle)
        _state.update { it.copy(running = false, recording = false, route = null, outputLatencyMs = null) }
    }

    fun setVoice(preset: VoicePreset) {
        NativeMicFx.nativeSetVoice(handle, preset.ordinal)
        _state.update { it.copy(voice = preset) }
    }

    fun setAmbience(preset: AmbiencePreset) {
        NativeMicFx.nativeSetAmbience(handle, preset.ordinal)
        _state.update { it.copy(ambience = preset) }
    }

    fun setWet(wet: Float) {
        val v = wet.coerceIn(0f, 1f)
        NativeMicFx.nativeSetWet(handle, v)
        _state.update { it.copy(wet = v) }
    }

    fun setInputGain(gain: Float) {
        val v = gain.coerceIn(0f, 4f)
        NativeMicFx.nativeSetInputGain(handle, v)
        _state.update { it.copy(inputGain = v) }
    }

    /**
     * Starts capturing the post-FX signal to a WAV in app storage. The background beat is not
     * captured here (it plays through ExoPlayer); [RecordingExporter] mixes it in offline
     * using the player position saved at record start.
     */
    fun startRecording(outputDir: File = File(context.filesDir, "recordings")): Boolean {
        if (!_state.value.running || _state.value.recording) return false
        outputDir.mkdirs()
        val file = File(outputDir, "mic_${System.currentTimeMillis()}.wav")
        val sampleRate = NativeMicFx.nativeSampleRate(handle)
        recordFile = file
        NativeMicFx.nativeSetRecording(handle, true)
        recordJob = scope.launch(Dispatchers.IO) {
            WavWriter(file, sampleRate).use { wav ->
                val block = FloatArray(4096)
                try {
                    while (isActive) {
                        val n = NativeMicFx.nativeReadRecorded(handle, block)
                        if (n > 0) wav.write(block, n) else delay(DRAIN_INTERVAL_MS)
                    }
                } finally {
                    // Flush what is still queued after recording was switched off.
                    NativeMicFx.nativeSetRecording(handle, false)
                    while (true) {
                        val n = NativeMicFx.nativeReadRecorded(handle, block)
                        if (n <= 0) break
                        wav.write(block, n)
                    }
                }
            }
        }
        _state.update { it.copy(recording = true) }
        return true
    }

    /** Stops recording and returns the finished WAV, or null if nothing was recording. */
    suspend fun stopRecording(): File? {
        val job = recordJob ?: return null
        job.cancel()
        withContext(Dispatchers.IO) { job.join() }
        recordJob = null
        _state.update { it.copy(recording = false) }
        return recordFile.also { recordFile = null }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun fail(error: MicFxError): Boolean {
        _state.update { it.copy(error = error) }
        return false
    }

    private fun currentHeadphoneRoute(): HeadphoneRoute? {
        val routes = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { d ->
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> HeadphoneRoute.WIRED
                AudioDeviceInfo.TYPE_USB_HEADSET -> HeadphoneRoute.USB
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> HeadphoneRoute.BLUETOOTH
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                ) HeadphoneRoute.BLUETOOTH else null
            }
        }
        // Prefer a wired route when both are connected; it is the one with usable latency.
        return routes.minByOrNull { it.ordinal }
    }

    private companion object {
        const val DRAIN_INTERVAL_MS = 10L
    }
}
