// SPDX-License-Identifier: GPL-3.0-only
// Bounded binary property lists for the disconnected script host. No wire transport.
#pragma once
#include "lua.h"
#include "lualib.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <limits>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

namespace research_plist {
constexpr size_t MaxBytes = 1024 * 1024, MaxObjects = 8192, MaxDepth = 32;
constexpr const char* DataType = "DiPlayResearchPlistData";
struct Value {
    enum Kind { Null, Bool, Integer, Real, Text, Data, Array, Dict } kind = Null;
    bool boolean = false;
    int64_t integer = 0;
    double real = 0;
    std::string bytes;
    std::vector<Value> values;
    std::vector<std::string> keys;
};
inline void require(bool valid) { if (!valid) throw std::runtime_error("binary plist invalid or exceeds limits"); }
inline void put(std::string& out, uint64_t value, size_t width) {
    require(out.size() <= MaxBytes && width <= MaxBytes - out.size());
    for (size_t i = width; i; --i) out.push_back(char(value >> ((i - 1) * 8)));
}
inline std::string utf16(const std::string& text) {
    std::string out;
    for (size_t i = 0; i < text.size();) {
        uint32_t cp = uint8_t(text[i++]);
        unsigned count = 0; uint32_t minimum = 0;
        if (cp >= 0xc2 && cp <= 0xdf) { cp &= 31; count = 1; minimum = 0x80; }
        else if (cp >= 0xe0 && cp <= 0xef) { cp &= 15; count = 2; minimum = 0x800; }
        else if (cp >= 0xf0 && cp <= 0xf4) { cp &= 7; count = 3; minimum = 0x10000; }
        else require(cp < 0x80);
        require(count <= text.size() - i);
        for (unsigned j = 0; j < count; ++j) {
            uint8_t next = uint8_t(text[i++]); require((next & 0xc0) == 0x80); cp = (cp << 6) | (next & 63);
        }
        require(cp >= minimum && cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff));
        if (cp > 0xffff) { cp -= 0x10000; put(out, 0xd800 + (cp >> 10), 2); put(out, 0xdc00 + (cp & 1023), 2); }
        else put(out, cp, 2);
    }
    return out;
}
inline void utf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) out.push_back(char(cp));
    else if (cp < 0x800) { out.push_back(char(0xc0 | (cp >> 6))); out.push_back(char(0x80 | (cp & 63))); }
    else if (cp < 0x10000) { out.push_back(char(0xe0 | (cp >> 12))); out.push_back(char(0x80 | ((cp >> 6) & 63))); out.push_back(char(0x80 | (cp & 63))); }
    else { out.push_back(char(0xf0 | (cp >> 18))); out.push_back(char(0x80 | ((cp >> 12) & 63))); out.push_back(char(0x80 | ((cp >> 6) & 63))); out.push_back(char(0x80 | (cp & 63))); }
}
inline void pushData(lua_State* L, const std::string& bytes) {
    require(bytes.size() <= MaxBytes);
    auto* data = static_cast<unsigned char*>(lua_newuserdata(L, sizeof(size_t) + bytes.size()));
    size_t size = bytes.size(); std::memcpy(data, &size, sizeof(size));
    std::memcpy(data + sizeof(size), bytes.data(), size);
    luaL_getmetatable(L, DataType); lua_setmetatable(L, -2);
}
inline std::string data(lua_State* L, int index) {
    require(lua_type(L, index) == LUA_TUSERDATA && lua_getmetatable(L, index));
    luaL_getmetatable(L, DataType); bool valid = lua_rawequal(L, -1, -2); lua_pop(L, 2); require(valid);
    auto* ptr = static_cast<unsigned char*>(lua_touserdata(L, index));
    size_t size = 0; std::memcpy(&size, ptr, sizeof(size)); require(size <= MaxBytes);
    return std::string(reinterpret_cast<char*>(ptr + sizeof(size)), size);
}
inline Value fromLua(lua_State* L, int index, size_t depth, size_t& budget, size_t& byteBudget, std::set<const void*>& active) {
    require(depth <= MaxDepth && budget > 0); --budget;
    require(lua_checkstack(L, 4));
    index = lua_absindex(L, index);
    Value v;
    switch (lua_type(L, index)) {
    case LUA_TNIL: break;
    case LUA_TBOOLEAN: v.kind = Value::Bool; v.boolean = lua_toboolean(L, index); break;
    case LUA_TNUMBER: {
        double number = lua_tonumber(L, index); require(std::isfinite(number));
        if (std::floor(number) == number && std::abs(number) <= 9007199254740991.0) { v.kind = Value::Integer; v.integer = int64_t(number); }
        else { v.kind = Value::Real; v.real = number; }
        break;
    }
    case LUA_TSTRING: {
        size_t size = 0; const char* text = lua_tolstring(L, index, &size); require(size <= byteBudget); byteBudget -= size;
        v.kind = Value::Text; v.bytes.assign(text, size); break;
    }
    case LUA_TUSERDATA: v.kind = Value::Data; v.bytes = data(L, index); require(v.bytes.size() <= byteBudget); byteBudget -= v.bytes.size(); break;
    case LUA_TTABLE: {
        auto identity = lua_topointer(L, index); require(active.insert(identity).second);
        size_t count = 0, maximum = 0; bool array = true;
        lua_pushnil(L);
        while (lua_next(L, index)) {
            require(++count <= MaxObjects);
            if (lua_type(L, -2) != LUA_TNUMBER) array = false;
            else { double key = lua_tonumber(L, -2); if (key < 1 || key > MaxObjects || key != std::floor(key)) array = false;
                else maximum = std::max(maximum, size_t(key)); }
            lua_pop(L, 1);
        }
        array = array && count > 0 && maximum == count;
        v.kind = array ? Value::Array : Value::Dict;
        if (array) for (size_t i = 1; i <= count; ++i) {
            lua_rawgeti(L, index, int(i)); v.values.push_back(fromLua(L, -1, depth + 1, budget, byteBudget, active)); lua_pop(L, 1);
        }
        else {
            lua_pushnil(L);
            while (lua_next(L, index)) {
                require(lua_type(L, -2) == LUA_TSTRING);
                size_t size = 0; const char* key = lua_tolstring(L, -2, &size); require(size <= byteBudget); byteBudget -= size;
                v.keys.emplace_back(key, size); v.values.push_back(fromLua(L, -1, depth + 1, budget, byteBudget, active)); lua_pop(L, 1);
            }
        }
        active.erase(identity); break;
    }
    default: require(false);
    }
    return v;
}
inline void toLua(lua_State* L, const Value& v) {
    require(lua_checkstack(L, 4));
    switch (v.kind) {
    case Value::Null: lua_pushnil(L); break;
    case Value::Bool: lua_pushboolean(L, v.boolean); break;
    case Value::Integer: require(v.integer >= -9007199254740991LL && v.integer <= 9007199254740991LL); lua_pushnumber(L, double(v.integer)); break;
    case Value::Real: lua_pushnumber(L, v.real); break;
    case Value::Text: lua_pushlstring(L, v.bytes.data(), v.bytes.size()); break;
    case Value::Data: pushData(L, v.bytes); break;
    case Value::Array: case Value::Dict:
        lua_createtable(L, v.kind == Value::Array ? int(v.values.size()) : 0, v.kind == Value::Dict ? int(v.values.size()) : 0);
        for (size_t i = 0; i < v.values.size(); ++i) {
            if (v.kind == Value::Array) { toLua(L, v.values[i]); lua_rawseti(L, -2, int(i + 1)); }
            else { lua_pushlstring(L, v.keys[i].data(), v.keys[i].size()); toLua(L, v.values[i]); lua_rawset(L, -3); }
        }
        break;
    }
}
class Writer {
    std::vector<std::string> objects;
    static void marker(std::string& out, uint8_t type, size_t count) {
        require(count <= MaxBytes);
        if (count < 15) put(out, type | count, 1);
        else { put(out, type | 15, 1); put(out, 0x13, 1); put(out, count, 8); }
    }
    size_t append(const Value& v, size_t depth) {
        require(depth <= MaxDepth && objects.size() < MaxObjects);
        size_t id = objects.size(); objects.emplace_back(); std::string out;
        switch (v.kind) {
        case Value::Null: put(out, 0, 1); break;
        case Value::Bool: put(out, v.boolean ? 9 : 8, 1); break;
        case Value::Integer: put(out, 0x13, 1); put(out, uint64_t(v.integer), 8); break;
        case Value::Real: { uint64_t bits; std::memcpy(&bits, &v.real, 8); put(out, 0x23, 1); put(out, bits, 8); break; }
        case Value::Text: {
            auto bytes = utf16(v.bytes); marker(out, 0x60, bytes.size() / 2); out += bytes; break;
        }
        case Value::Data: marker(out, 0x40, v.bytes.size()); out += v.bytes; break;
        case Value::Array: case Value::Dict: {
            marker(out, v.kind == Value::Array ? 0xa0 : 0xd0, v.values.size());
            if (v.kind == Value::Dict) for (const auto& key : v.keys) { Value k; k.kind = Value::Text; k.bytes = key; put(out, append(k, depth + 1), 2); }
            for (const auto& child : v.values) put(out, append(child, depth + 1), 2);
            break;
        }
        }
        require(out.size() <= MaxBytes); objects[id] = std::move(out); return id;
    }
public:
    std::string write(const Value& v) {
        size_t root = append(v, 0); std::string out = "bplist00"; std::vector<size_t> offsets;
        for (const auto& obj : objects) { offsets.push_back(out.size()); require(obj.size() <= MaxBytes - out.size()); out += obj; }
        size_t table = out.size();
        for (size_t offset : offsets) put(out, offset, 4);
        require(out.size() <= MaxBytes - 32); out.append(6, '\0'); put(out, 4, 1); put(out, 2, 1); put(out, objects.size(), 8); put(out, root, 8); put(out, table, 8);
        require(out.size() <= MaxBytes); return out;
    }
};
class Reader {
    const std::string& bytes;
    size_t table = 0, refs = 0, root = 0, budget = MaxObjects, byteBudget = MaxBytes;
    std::vector<size_t> offsets;
    std::set<size_t> active;
    uint64_t number(size_t at, size_t width, size_t end) const {
        require(width >= 1 && width <= 8 && at <= end && width <= end - at);
        uint64_t result = 0; for (size_t i = 0; i < width; ++i) result = (result << 8) | uint8_t(bytes[at + i]); return result;
    }
    Value read(size_t index, size_t depth) {
        require(depth <= MaxDepth && budget > 0 && index < offsets.size() && active.insert(index).second); --budget;
        size_t at = offsets[index]; auto tag = number(at++, 1, table); auto type = tag >> 4, nibble = tag & 15;
        auto scalar = [&](size_t width) { auto n = number(at, width, table); at += width; return n; };
        auto count = [&]() -> size_t { if (nibble < 15) return size_t(nibble);
            auto m = scalar(1); require((m >> 4) == 1 && (m & 15) <= 3); auto n = scalar(size_t(1) << (m & 15)); require(n <= MaxBytes); return size_t(n); };
        auto extent = [&](size_t n) { require(at <= table && n <= table - at); };
        Value v;
        switch (type) {
        case 0: require(nibble == 0 || nibble == 8 || nibble == 9); if (nibble) { v.kind = Value::Bool; v.boolean = nibble == 9; } break;
        case 1: { require(nibble <= 3); auto n = scalar(size_t(1) << nibble); v.kind = Value::Integer; std::memcpy(&v.integer, &n, 8); break; }
        case 2: {
            require(nibble == 2 || nibble == 3); v.kind = Value::Real;
            if (nibble == 2) { uint32_t n = uint32_t(scalar(4)); float f; std::memcpy(&f, &n, 4); v.real = f; }
            else { uint64_t n = scalar(8); std::memcpy(&v.real, &n, 8); }
            require(std::isfinite(v.real)); break;
        }
        case 4: case 5: case 6: {
            size_t n = count(); require(n <= MaxBytes / (type == 6 ? 2 : 1)); extent(n * (type == 6 ? 2 : 1));
            v.kind = type == 4 ? Value::Data : Value::Text;
            if (type == 6) for (size_t i = 0; i < n; ++i) {
                uint32_t cp = uint32_t(scalar(2));
                if (cp >= 0xd800 && cp <= 0xdbff) { require(++i < n); uint32_t low = uint32_t(scalar(2)); require(low >= 0xdc00 && low <= 0xdfff); cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00; }
                else require(cp < 0xdc00 || cp > 0xdfff);
                utf8(v.bytes, cp);
            }
            else { v.bytes = bytes.substr(at, n); if (type == 5) for (unsigned char c : v.bytes) require(c < 128); }
            require(v.bytes.size() <= byteBudget); byteBudget -= v.bytes.size();
            break;
        }
        case 10: case 13: {
            size_t n = count(); require(n <= MaxObjects); extent(n * refs * (type == 13 ? 2 : 1));
            v.kind = type == 10 ? Value::Array : Value::Dict; std::set<std::string> keys;
            for (size_t i = 0; i < n; ++i) {
                if (type == 13) { auto key = read(size_t(number(at + i * refs, refs, table)), depth + 1);
                    require(key.kind == Value::Text && keys.insert(key.bytes).second); v.keys.push_back(std::move(key.bytes)); }
                v.values.push_back(read(size_t(number(at + (type == 13 ? n + i : i) * refs, refs, table)), depth + 1));
                // Luau tables cannot preserve explicit null array/dictionary entries.
                require(v.values.back().kind != Value::Null);
            }
            break;
        }
        default: require(false);
        }
        active.erase(index); return v;
    }
public:
    explicit Reader(const std::string& input) : bytes(input) {
        require(bytes.size() >= 40 && bytes.size() <= MaxBytes && bytes.compare(0, 8, "bplist00") == 0);
        size_t trailer = bytes.size() - 32; size_t width = size_t(number(trailer + 6, 1, bytes.size()));
        refs = size_t(number(trailer + 7, 1, bytes.size())); require(width >= 1 && width <= 8 && refs >= 1 && refs <= 8);
        auto n = number(trailer + 8, 8, bytes.size()); require(n >= 1 && n <= MaxObjects);
        auto r = number(trailer + 16, 8, bytes.size()); require(r < n); root = size_t(r);
        auto t = number(trailer + 24, 8, bytes.size()); require(t >= 8 && t <= trailer && n * width <= trailer - t); table = size_t(t);
        for (size_t i = 0; i < n; ++i) { auto offset = number(table + i * width, width, trailer); require(offset >= 8 && offset < table); offsets.push_back(size_t(offset)); }
    }
    Value readRoot() { return read(root, 0); }
};
inline int create(lua_State* L) {
    char error[128] = {};
    try { size_t budget = MaxObjects, byteBudget = MaxBytes; std::set<const void*> active; auto v = fromLua(L, 1, 0, budget, byteBudget, active); auto bytes = Writer().write(v); pushData(L, bytes); return 1; }
    catch (const std::exception&) { std::snprintf(error, sizeof(error), "binary plist encoding refused"); }
    luaL_error(L, "%s", error);
}
inline int parse(lua_State* L) {
    const int top = lua_gettop(L);
    try {
        std::string bytes;
        if (lua_type(L, 1) == LUA_TSTRING) { size_t size = 0; const char* text = lua_tolstring(L, 1, &size); require(size <= MaxBytes); bytes.assign(text, size); }
        else bytes = data(L, 1);
        auto v = Reader(bytes).readRoot(); toLua(L, v); return 1;
    } catch (const std::exception&) { lua_settop(L, top); lua_pushnil(L); lua_pushliteral(L, "binary plist parsing refused"); return 2; }
}
inline int length(lua_State* L) { auto* ptr = static_cast<unsigned char*>(luaL_checkudata(L, 1, DataType)); size_t size; std::memcpy(&size, ptr, sizeof(size)); lua_pushinteger(L, int(size)); return 1; }
inline int equal(lua_State* L) {
    try { auto left = data(L, 1); auto right = data(L, 2); lua_pushboolean(L, left == right); return 1; }
    catch (const std::exception&) { lua_pushboolean(L, false); return 1; }
}
inline int rawBytes(lua_State* L) {
    try { auto bytes = data(L, 1); lua_pushlstring(L, bytes.data(), bytes.size()); return 1; }
    catch (const std::exception&) {}
    luaL_error(L, "binary plist data required");
}
inline void install(lua_State* L) {
    luaL_newmetatable(L, DataType); lua_pushcfunction(L, length, "__len"); lua_setfield(L, -2, "__len");
    lua_pushcfunction(L, equal, "__eq"); lua_setfield(L, -2, "__eq");
    lua_pushliteral(L, "protected plist data"); lua_setfield(L, -2, "__metatable"); lua_pop(L, 1);
    lua_pushcfunction(L, create, "createBinaryPlist"); lua_setglobal(L, "createBinaryPlist");
    lua_pushcfunction(L, parse, "parseBinaryPlist"); lua_setglobal(L, "parseBinaryPlist");
    lua_pushcfunction(L, rawBytes, "binaryPlistBytes"); lua_setglobal(L, "binaryPlistBytes");
}
} // namespace research_plist
