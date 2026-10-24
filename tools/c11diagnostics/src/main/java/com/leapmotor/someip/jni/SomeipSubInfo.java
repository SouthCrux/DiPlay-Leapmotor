package com.leapmotor.someip.jni;

/** Field names/types are part of the installed JNI ABI; package identity remains SouthCrux. */
public final class SomeipSubInfo {
    public int serviceId;
    public int instanceId;
    public int methodId = 65535;
    public int[] groupIds;
    public int[] eventIds;
}
