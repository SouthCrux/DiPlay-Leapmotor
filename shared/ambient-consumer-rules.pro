# Entry point launched by app_process from the installed application classpath.
-keep class com.shilapi.xcertplay.hud.BydAmbientLightTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.hud.BydCallPopupTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.transport.IphoneUsbGrantTool {
    public static void main(java.lang.String[]);
}
# Names and fields are the ABI of the optional installed C11 JNI library.
-keep class com.leapmotor.someip.jni.** { *; }
# Stable JNI names for the optional source-built script VM.
-keep class com.shilapi.xcertplay.airplay.UltraNativeBindings { *; }
