// SPDX-License-Identifier: GPL-3.0-only
// Offscreen research host. No phone, graphics device, network or vehicle interfaces.
#include "Luau/Compiler.h"
#include "lua.h"
#include "lualib.h"

#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include "native_plist.hpp"

namespace fs = std::filesystem;
namespace {
constexpr size_t MaxSource = 4 * 1024 * 1024;
constexpr size_t MaxTotalSource = 32 * 1024 * 1024;
constexpr size_t MaxMemory = 128 * 1024 * 1024;
struct Host {
    fs::path root;
    size_t memory = 0;
    size_t sourceBytes = 0;
    size_t loaded = 0;
    bool timedOut = false;
    std::chrono::steady_clock::time_point deadline;
    std::map<std::string, int> modules;
    std::set<std::string> loading;
};
void* allocate(void* context, void* pointer, size_t oldSize, size_t newSize) {
    auto& h = *static_cast<Host*>(context);
    if (!pointer) oldSize = 0; // Luau can use oldSize for a type tag on new allocations.
    if (!newSize) { h.memory -= oldSize; std::free(pointer); return nullptr; }
    if (newSize > MaxMemory - (h.memory - oldSize)) return nullptr;
    void* result = std::realloc(pointer, newSize);
    if (result) h.memory = h.memory - oldSize + newSize;
    return result;
}
Host& host(lua_State* L) { return *static_cast<Host*>(lua_callbacks(L)->userdata); }
std::string compile(const std::string& source) {
    // CP contains frame/lifecycle properties, not constants. Import caching would freeze them.
    static const char* const mutableGlobals[] = {"CP", nullptr};
    Luau::CompileOptions options;
    options.mutableGlobals = mutableGlobals;
    return Luau::compile(source, options);
}
void interrupt(lua_State* L, int gc) {
    if (gc >= 0) return;
    auto& h = host(L);
    if (h.timedOut || std::chrono::steady_clock::now() >= h.deadline) {
        h.timedOut = true;
        luaL_error(L, "runtime CPU deadline exceeded");
    }
}
bool safePath(const std::string& name) {
    if (name.empty() || name.size() > 200 || name.front() == '/' || name.back() == '/') return false;
    for (const unsigned char c : name)
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
              c == '_' || c == '-' || c == '.' || c == '/')) return false;
    for (const auto& part : fs::path(name)) if (part == "." || part == "..") return false;
    return true;
}
std::string read(const fs::path& file) {
    const auto bytes = fs::file_size(file);
    if (bytes > MaxSource) throw std::runtime_error("source/asset exceeds size limit");
    std::ifstream input(file, std::ios::binary);
    if (!input) throw std::runtime_error("source/asset unavailable");
    std::string result(size_t(bytes), '\0');
    input.read(result.data(), std::streamsize(bytes));
    if (!input || input.peek() != EOF) throw std::runtime_error("source/asset changed during read");
    return result;
}
std::string asset(Host& h, const std::string& name) {
    if (!safePath(name)) throw std::runtime_error("unsafe asset path");
    fs::path candidate = h.root;
    for (const auto& part : fs::path(name)) {
        candidate /= part;
        if (fs::is_symlink(fs::symlink_status(candidate))) throw std::runtime_error("asset symlink refused");
    }
    return read(candidate);
}
int rawAsset(lua_State* L) {
    // Copy exception text to a trivial buffer before raising a VM error.
    char error[128] = {};
    try {
        size_t size = 0; const char* name = luaL_checklstring(L, 1, &size);
        auto text = asset(host(L), std::string(name, size));
        lua_pushlstring(L, text.data(), text.size()); return 1;
    } catch (const std::exception&) { std::snprintf(error, sizeof(error), "asset unavailable or refused"); }
    luaL_error(L, "%s", error);
}
int requireSource(lua_State* L) {
    size_t size = 0; const char* text = luaL_checklstring(L, 1, &size);
    // No bytecode inputs, dynamic libraries, relative imports or module filesystem API.
    std::string name(text, size);
    if (!safePath(name) || name.find("..") != std::string::npos || name.find("//") != std::string::npos)
        luaL_error(L, "module name refused");
    for (char& c : name) if (c == '.') c = '/';
    auto& h = host(L);
    auto existing = h.modules.find(name);
    if (existing != h.modules.end()) { lua_getref(L, existing->second); return 1; }
    if (h.loading.size() >= 64 || h.loaded >= 256 || h.loading.count(name)) luaL_error(L, "module limit or dependency cycle");
    char error[1024] = {};
    try {
        auto source = asset(h, name + ".lua");
        if (source.size() > MaxTotalSource - h.sourceBytes) throw std::runtime_error("total source limit");
        h.sourceBytes += source.size();
        auto bytecode = compile(source);
        if (luau_load(L, name.c_str(), bytecode.data(), bytecode.size(), 0) != 0) {
            std::snprintf(error, sizeof(error), "compile %s: %.700s", name.c_str(), lua_tostring(L, -1));
            lua_pop(L, 1);
        }
    } catch (const std::exception&) { std::snprintf(error, sizeof(error), "source module unavailable: %.200s", name.c_str()); }
    if (error[0]) luaL_error(L, "%s", error);
    h.loading.insert(name);
    const int status = lua_pcall(L, 0, 1, 0);
    h.loading.erase(name);
    if (status != 0) lua_error(L);
    if (lua_isnil(L, -1)) luaL_error(L, "module must return a value");
    h.modules.emplace(name, lua_ref(L, -1)); ++h.loaded;
    return 1;
}
int discard(lua_State*) { return 0; }
int monotonicTime(lua_State* L) {
    lua_pushnumber(L, std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count());
    return 1;
}
int compileSource(lua_State* L) {
    size_t size = 0; const char* source = luaL_checklstring(L, 1, &size);
    auto& h = host(L);
    if (size > 64 * 1024 || size > MaxTotalSource - h.sourceBytes) luaL_error(L, "dynamic source limit");
    h.sourceBytes += size;
    // flux creates easing functions dynamically. Compile text ourselves; never accept bytecode.
    auto code = compile(std::string(source, size));
    if (luau_load(L, "=dynamic", code.data(), code.size(), 0) != 0) {
        lua_pushnil(L); lua_insert(L, -2); return 2;
    }
    return 1;
}
void execute(lua_State* L, const std::string& source, const char* name, int results) {
    auto code = compile(source);
    if (luau_load(L, name, code.data(), code.size(), 0) != 0 || lua_pcall(L, 0, results, 0) != 0)
        throw std::runtime_error(lua_tostring(L, -1) ? lua_tostring(L, -1) : "script failed");
}
} // namespace

#include "step_stream.hpp"

namespace {
lua_State* initialize(Host& h, const fs::path& root, const std::string& bridge,
                      const std::string& entrypoint, bool researchOverride) {
    if (bridge.size() > MaxSource || entrypoint.size() > MaxSource) throw std::runtime_error("bootstrap source limit");
    lua_State* L = nullptr;
    try {
        h.root = fs::canonical(root);
        h.deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        L = lua_newstate(allocate, &h);
        if (!L) throw std::runtime_error("VM allocation failed");
        lua_callbacks(L)->userdata = &h;
        lua_callbacks(L)->interrupt = interrupt;
        luaL_openlibs(L);
        research_plist::install(L);
        // Luau's limited debug.traceback/info stay available. No environment or OS API.
        for (const char* name : {"loadstring", "getfenv", "setfenv", "os"}) { lua_pushnil(L); lua_setglobal(L, name); }
        lua_pushcfunction(L, requireSource, "require"); lua_setglobal(L, "require");
        lua_pushcfunction(L, compileSource, "loadstring"); lua_setglobal(L, "loadstring");
        lua_pushcfunction(L, rawAsset, "readAsset"); lua_setglobal(L, "readAsset");
        lua_pushcfunction(L, monotonicTime, "monotonicTime"); lua_setglobal(L, "monotonicTime");
        lua_pushcfunction(L, discard, "print"); lua_setglobal(L, "print");
        execute(L, bridge, "@bridge", 0);
        if (researchOverride) {
            // Local porting aid only. This host has no phone/session endpoint or display output.

            lua_getglobal(L, "CP"); lua_pushstring(L, "1.4.0"); lua_setfield(L, -2, "version"); lua_pop(L, 1);
        }
        luaL_sandbox(L);
        luaL_sandboxthread(L);
        execute(L, entrypoint, "@probe", 1);
        return L;
    } catch (...) { if (L) lua_close(L); throw; }
}
} // namespace

#ifndef DIPLAY_ULTRA_JNI
int main(int argc, char** argv) {
    const bool researchOverride = (argc == 5 || argc == 6) && std::string(argv[4]) == "--research-allow-incomplete-cp14";
    const bool streamMode = argc == 6 && researchOverride && std::string(argv[5]) == "--research-step-stream";
    if (argc != 4 && !(argc == 5 && researchOverride) && !streamMode) {
        std::cerr << "usage: ultra-host RESOURCE_DIRECTORY BRIDGE_SOURCE PROBE_SOURCE [--research-allow-incomplete-cp14 [--research-step-stream]]\n";
        return 2;
    }
    Host h;
    lua_State* L = nullptr;
    try {
        L = initialize(h, argv[1], read(argv[2]), read(argv[3]), researchOverride);
        if (streamMode) {
            lua_settop(L, 0); stepStream(L, h);
            std::cerr << "researchStepStream=true modules=" << h.loaded << " vmBytes=" << h.memory << '\n';
            lua_close(L); return 0;
        }
        if (!lua_isstring(L, -1)) throw std::runtime_error("probe must return a report string");
        size_t size = 0; const char* result = lua_tolstring(L, -1, &size);
        if (size > 1024 * 1024) throw std::runtime_error("report exceeds output limit");
        std::cout.write(result, std::streamsize(size)); std::cout << '\n';
        std::cerr << "modules=" << h.loaded << " sourceBytes=" << h.sourceBytes << " vmBytes=" << h.memory << '\n';
        lua_close(L); return 0;
    } catch (const std::exception& error) {
        std::cerr << "runtime error: " << error.what() << '\n';
        std::cerr << "modules=" << h.loaded << " sourceBytes=" << h.sourceBytes << " vmBytes=" << h.memory << '\n';
        if (L) lua_close(L);
        return 1;
    }
}

#else
#include "jni_host.hpp"
#endif
