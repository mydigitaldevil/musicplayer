package com.mydigitaldevil.musicplayer.audio.fx

/** Raw JNI surface of `libmicfx.so`. Use [MicFxEngine]; nothing else should call this. */
internal object NativeMicFx {
    init {
        System.loadLibrary("micfx")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeStart(handle: Long, inputDeviceId: Int, outputDeviceId: Int): Boolean
    external fun nativeStop(handle: Long)
    external fun nativeIsRunning(handle: Long): Boolean
    external fun nativeSetVoice(handle: Long, voice: Int)
    external fun nativeSetAmbience(handle: Long, ambience: Int)
    external fun nativeSetWet(handle: Long, wet: Float)
    external fun nativeSetInputGain(handle: Long, gain: Float)
    external fun nativeSetMonitorEnabled(handle: Long, on: Boolean)
    external fun nativeSetRecording(handle: Long, on: Boolean)
    external fun nativeReadRecorded(handle: Long, dst: FloatArray): Int
    external fun nativeSampleRate(handle: Long): Int
    external fun nativeOutputLatencyMs(handle: Long): Double
}
