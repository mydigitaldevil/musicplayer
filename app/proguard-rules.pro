# JNI entry points of the native mic FX engine must keep their names.
-keepclasseswithmembernames class com.mydigitaldevil.musicplayer.audio.fx.NativeMicFx {
    native <methods>;
}
