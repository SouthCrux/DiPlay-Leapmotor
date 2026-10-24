// SPDX-License-Identifier: GPL-3.0-only
#pragma once
namespace {
void stepFunction(lua_State* L, const char* name) {
    lua_getglobal(L, "Offscreen"); lua_getfield(L, -1, name); lua_remove(L, -2);
}
void stepCall(lua_State* L, int arguments, int results) {
    if (lua_pcall(L, arguments, results, 0) != 0) throw std::runtime_error("research step refused");
}
// Shared bounded operations for stdio and the Android JNI embedding.
research_plist::Value stepRequest(lua_State* L, Host& h, uint8_t opcode, const std::string& payload) {
    research_plist::require(payload.size() <= research_plist::MaxBytes);
    const size_t size = payload.size();
    h.timedOut = false; h.deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    lua_settop(L, 0);
    using V = research_plist::Value; V result;
    try {
            switch (opcode) {
            case 0: research_plist::require(size == 0); break;
            case 1:
                stepFunction(L, "enqueueProtocolForResearch"); research_plist::pushData(L, payload); stepCall(L, 1, 0); break;
            case 2:
                research_plist::require(size == 1 && uint8_t(payload[0]) >= 1 && uint8_t(payload[0]) <= 120);
                stepFunction(L, "runTasks"); lua_pushinteger(L, uint8_t(payload[0])); stepCall(L, 1, 0); break;
            case 3:
                research_plist::require(size == 0);
                execute(L, "return require('APL.json').encode({fixture=true,fullUltraUI=false,incompleteBridgeOverride=true,scene=Offscreen.snapshot()})", "@snapshot", 1);
                { size_t count = 0; const char* bytes = lua_tolstring(L, -1, &count);
                    research_plist::require(bytes && count <= research_plist::MaxBytes - 1024);
                    result.kind = V::Data; result.bytes.assign(bytes, count); } break;
            case 4: case 5:
                research_plist::require(size == 1);
                if (opcode == 4) { stepFunction(L, "setSceneLifecycleForResearch"); lua_pushinteger(L, uint8_t(payload[0])); }
                else { research_plist::require(uint8_t(payload[0]) <= 1); stepFunction(L, "setOutputActive"); lua_pushboolean(L, payload[0] != 0); }
                stepCall(L, 1, 0); break;
            case 6:
                research_plist::require(size == 0); stepFunction(L, "takeProtocolForResearch"); stepCall(L, 0, 1);
                // Encode empty arrays explicitly; an empty generic Lua table denotes a dictionary.
                result.kind = V::Array;
                for (int i = 1, count = lua_objlen(L, -1); i <= count; ++i) {
                    research_plist::require(i <= 64); lua_rawgeti(L, -1, i);
                    V item; item.kind = V::Data; item.bytes = research_plist::data(L, -1); lua_pop(L, 1);
                    result.values.push_back(std::move(item));
                }
                break;
            case 7:
                stepFunction(L, "videoFrame"); research_plist::pushData(L, payload); stepCall(L, 1, 1);
                { size_t budget = research_plist::MaxObjects, bytes = research_plist::MaxBytes;
                    std::set<const void*> active;
                    result = research_plist::fromLua(L, -1, 0, budget, bytes, active); } break;
            case 8:
                research_plist::require(size == 1 && (uint8_t(payload[0]) <= 4 || uint8_t(payload[0]) == 255));
                stepFunction(L, "updateSceneLifecycle");
                if (uint8_t(payload[0]) == 255) lua_pushnil(L);
                else lua_pushinteger(L, uint8_t(payload[0]));
                stepCall(L, 1, 0); break;
            case 9:
                research_plist::require(size > 0 && size <= 1024);
                stepFunction(L, "updateBatteryService"); research_plist::pushData(L, payload);
                stepCall(L, 1, 0); break;
            default: research_plist::require(false);
            }
        lua_settop(L, 0); lua_gc(L, LUA_GCSTEP, 1);
        return result;
    } catch (...) { lua_settop(L, 0); throw; }
}
#ifndef DIPLAY_ULTRA_JNI
void stepReply(bool ok, const research_plist::Value& data = {}) {
    using V = research_plist::Value;
    V status; status.kind = V::Bool; status.boolean = ok;
    V response; response.kind = V::Dict; response.keys = {"ok"}; response.values = {status};
    if (data.kind != V::Null) { response.keys.push_back("data"); response.values.push_back(data); }
    auto bytes = research_plist::Writer().write(response); std::string size;
    research_plist::put(size, bytes.size(), 4);
    std::cout.write(size.data(), size.size()); std::cout.write(bytes.data(), bytes.size()); std::cout.flush();
    if (!std::cout) throw std::runtime_error("step output unavailable");
}
void stepStream(lua_State* L, Host& h) {
    stepReply(true);
    for (size_t steps = 0; steps < 4096; ++steps) {
        // Read a bounded request: opcode, big-endian uint32 length, then payload.
        char header[5]; std::cin.read(header, 5);
        if (std::cin.gcount() == 0 && std::cin.eof()) return;
        if (std::cin.gcount() != 5) throw std::runtime_error("truncated step header");
        uint32_t size = 0; for (size_t i = 1; i < 5; ++i) size = (size << 8) | uint8_t(header[i]);
        if (size > research_plist::MaxBytes) throw std::runtime_error("step size limit");
        std::string payload(size, '\0'); std::cin.read(payload.data(), size);
        if (size_t(std::cin.gcount()) != size) throw std::runtime_error("truncated step payload");
        try {
            auto result = stepRequest(L, h, uint8_t(header[0]), payload);
            stepReply(true, result);
            if (header[0] == 0) return;
        } catch (const std::exception&) {
            // A script failure can leave partial state; terminate rather than reuse that session.
            lua_settop(L, 0); stepReply(false); throw std::runtime_error("research step failed; session terminated");
        }
    }
    throw std::runtime_error("research step count limit");
}
#endif
} // namespace
