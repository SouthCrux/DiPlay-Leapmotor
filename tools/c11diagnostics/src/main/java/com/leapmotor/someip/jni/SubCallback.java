package com.leapmotor.someip.jni;

public interface SubCallback {
    void onMessage(int serviceId, int instanceId, int eventId, byte[] payload);
}
