// SPDX-License-Identifier: GPL-3.0-only
// In-process embedding; no sockets or Android surfaces. Calls are serialized per VM.
#pragma once
#include <jni.h>
#include <memory>
#include <mutex>
namespace {
struct NativeSession {
    Host host;
    lua_State* vm = nullptr;
    std::mutex mutex;
    size_t requests = 0;
    bool sessionOwned = false;
    ~NativeSession() { if (vm) lua_close(vm); }
};
std::mutex sessionMutex;
std::map<jlong, std::shared_ptr<NativeSession>> sessions;
jlong nextSession = 1;
void jniFailure(JNIEnv* env) {
    if (!env->ExceptionCheck()) {
        auto type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, "Ultra native session refused or terminated");
    }
}
std::string jniBytes(JNIEnv* env, jbyteArray input, size_t max) {
    if (!input) throw std::runtime_error("missing input");
    const auto size = env->GetArrayLength(input);
    if (size < 0 || size_t(size) > max) throw std::runtime_error("input size limit");
    std::string value(size_t(size), '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(value.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("input unavailable");
    return value;
}
std::shared_ptr<NativeSession> lookup(jlong id) {
    std::lock_guard<std::mutex> guard(sessionMutex);
    const auto found = sessions.find(id);
    if (found == sessions.end()) throw std::runtime_error("session closed");
    return found->second;
}
void retire(jlong id, const std::shared_ptr<NativeSession>& session) {
    // Caller holds the VM lock. Never retain a partially changed VM after failure.
    if (session->vm) { lua_close(session->vm); session->vm = nullptr; }
    std::lock_guard<std::mutex> guard(sessionMutex);
    sessions.erase(id);
}
jlong createNativeSession(JNIEnv* env, jbyteArray root, jbyteArray bridge, jbyteArray entrypoint,
                          jboolean researchOverride, bool sessionOwned) {
    try {
        auto path = jniBytes(env, root, 4096);
        if (path.empty() || path.find('\0') != std::string::npos || path.front() != '/') throw std::runtime_error("root refused");
        auto bridgeSource = jniBytes(env, bridge, MaxSource);
        auto entrySource = jniBytes(env, entrypoint, MaxSource);
        // Reserve under the registry lock so concurrent creates cannot exceed the ceiling.
        std::lock_guard<std::mutex> guard(sessionMutex);
        if (sessions.size() >= 4 || nextSession == std::numeric_limits<jlong>::max()) throw std::runtime_error("session limit");
        auto session = std::make_shared<NativeSession>();
        session->sessionOwned = sessionOwned;
        session->vm = initialize(session->host, path, bridgeSource, entrySource, researchOverride);
        lua_settop(session->vm, 0);
        const auto id = nextSession++;
        sessions.emplace(id, std::move(session));
        return id;
    } catch (...) { jniFailure(env); return 0; }
}
} // namespace
extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_airplay_UltraNativeBindings_create(
    JNIEnv* env, jclass, jbyteArray root, jbyteArray bridge, jbyteArray entrypoint, jboolean researchOverride) {
    return createNativeSession(env, root, bridge, entrypoint, researchOverride, false);
}
// A distinct ABI entry prevents an older library from silently ignoring the requested lifetime.
extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_airplay_UltraNativeBindings_createSessionOwned(
    JNIEnv* env, jclass, jbyteArray root, jbyteArray bridge, jbyteArray entrypoint, jboolean researchOverride) {
    return createNativeSession(env, root, bridge, entrypoint, researchOverride, true);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_airplay_UltraNativeBindings_step(
    JNIEnv* env, jclass, jlong id, jint opcode, jbyteArray bytes) {
    std::shared_ptr<NativeSession> session;
    try { session = lookup(id); } catch (...) { jniFailure(env); return nullptr; }
    std::lock_guard<std::mutex> guard(session->mutex);
    try {
        if (!session->vm || opcode < 0 || opcode > 9 ||
            (!session->sessionOwned && ++session->requests > 4096)) throw std::runtime_error("step refused");
        auto payload = jniBytes(env, bytes, research_plist::MaxBytes);
        auto data = stepRequest(session->vm, session->host, uint8_t(opcode), payload);
        using V = research_plist::Value;
        V response; response.kind = V::Dict; response.keys = {"ok"};
        V ok; ok.kind = V::Bool; ok.boolean = true; response.values = {ok};
        if (data.kind != V::Null) { response.keys.push_back("data"); response.values.push_back(std::move(data)); }
        const auto encoded = research_plist::Writer().write(response);
        auto result = env->NewByteArray(jsize(encoded.size()));
        if (!result) throw std::runtime_error("response allocation");
        env->SetByteArrayRegion(result, 0, jsize(encoded.size()), reinterpret_cast<const jbyte*>(encoded.data()));
        if (env->ExceptionCheck()) throw std::runtime_error("response unavailable");
        if (opcode == 0) retire(id, session);
        return result;
    } catch (...) { retire(id, session); jniFailure(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_airplay_UltraNativeBindings_destroy(JNIEnv*, jclass, jlong id) {
    std::shared_ptr<NativeSession> session;
    try { session = lookup(id); } catch (...) { return; }
    std::lock_guard<std::mutex> guard(session->mutex);
    retire(id, session);
}
