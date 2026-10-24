package com.leapmotor.someip.jni;

/** JNI declarations for the firmware's public library, not a bundled OEM implementation. */
public final class VSomeipSubNative {
    static { System.loadLibrary("someip.leapmotor"); }

    public native boolean init(String clientName);
    public native void addInfo(SomeipSubInfo info);
    public native void setCallback(SubCallback callback);
    public native void start();
}
