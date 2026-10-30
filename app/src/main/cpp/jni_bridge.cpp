#include <jni.h>

#include "mic_fx_engine.h"

using micfx::MicFxEngine;

namespace {
inline MicFxEngine* engine(jlong handle) { return reinterpret_cast<MicFxEngine*>(handle); }
}

#define JNI_FN(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_com_mydigitaldevil_musicplayer_audio_fx_NativeMicFx_##name

JNI_FN(jlong, nativeCreate)(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new MicFxEngine());
}

JNI_FN(void, nativeDestroy)(JNIEnv*, jobject, jlong handle) {
    delete engine(handle);
}

JNI_FN(jboolean, nativeStart)(JNIEnv*, jobject, jlong handle, jint inputDeviceId, jint outputDeviceId) {
    return engine(handle)->start(inputDeviceId, outputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, nativeStop)(JNIEnv*, jobject, jlong handle) { engine(handle)->stop(); }

JNI_FN(jboolean, nativeIsRunning)(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, nativeSetVoice)(JNIEnv*, jobject, jlong handle, jint voice) { engine(handle)->setVoice(voice); }

JNI_FN(void, nativeSetAmbience)(JNIEnv*, jobject, jlong handle, jint ambience) {
    engine(handle)->setAmbience(ambience);
}

JNI_FN(void, nativeSetWet)(JNIEnv*, jobject, jlong handle, jfloat wet) { engine(handle)->setWet(wet); }

JNI_FN(void, nativeSetInputGain)(JNIEnv*, jobject, jlong handle, jfloat gain) {
    engine(handle)->setInputGain(gain);
}

JNI_FN(void, nativeSetMonitorEnabled)(JNIEnv*, jobject, jlong handle, jboolean on) {
    engine(handle)->setMonitorEnabled(on == JNI_TRUE);
}

JNI_FN(void, nativeSetRecording)(JNIEnv*, jobject, jlong handle, jboolean on) {
    engine(handle)->setRecording(on == JNI_TRUE);
}

JNI_FN(jint, nativeReadRecorded)(JNIEnv* env, jobject, jlong handle, jfloatArray dst) {
    const jsize len = env->GetArrayLength(dst);
    jfloat* data = env->GetFloatArrayElements(dst, nullptr);
    const jint n = engine(handle)->readRecorded(data, len);
    env->ReleaseFloatArrayElements(dst, data, 0);
    return n;
}

JNI_FN(jint, nativeSampleRate)(JNIEnv*, jobject, jlong handle) { return engine(handle)->sampleRate(); }

JNI_FN(jdouble, nativeOutputLatencyMs)(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->outputLatencyMs();
}
