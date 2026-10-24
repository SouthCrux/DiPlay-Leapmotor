#!/usr/bin/env python3
"""Inspect a CarPlay accessory archive without extracting or executing its code.

Reports lexical CP references, Lua module dependencies and wrapper method calls.
These observations establish call sites, not native semantics or Apple wire IDs.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

MAX_ARCHIVE = 128 * 1024 * 1024
MAX_ENTRY = 16 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
MAX_ENTRIES = 10000
LONG_OPEN = re.compile(r"\[(=*)\[")


def tokens(source: str) -> list[tuple[str, str, int]]:
    """Small Lua lexer: comments and string contents cannot become fake CP calls."""
    result = []
    at, line = 0, 1
    while at < len(source):
        start, first_line = at, line
        comment = source.startswith("--", at)
        if comment:
            at += 2
        long = LONG_OPEN.match(source, at)
        if long:
            begin = at + len(long[0])
            end = source.find("]" + long[1] + "]", begin)
            if end < 0:
                raise ValueError("Unterminated Lua long string/comment")
            at = end + 2 + len(long[1])
            if not comment:
                result.append(("string", source[begin:end], first_line))
        elif comment:
            end = source.find("\n", at)
            at = len(source) if end < 0 else end
        elif source[at] in "\"'":
            quote = source[at]
            at += 1
            value = []
            while at < len(source) and source[at] != quote:
                if source[at] == "\\":
                    at += 1
                    if at >= len(source):
                        raise ValueError("Unterminated Lua escape")
                value.append(source[at])
                at += 1
            if at >= len(source):
                raise ValueError("Unterminated Lua string")
            at += 1
            result.append(("string", "".join(value), first_line))
        elif source[at].isalpha() or source[at] == "_":
            at += 1
            while at < len(source) and (source[at].isalnum() or source[at] == "_"):
                at += 1
            result.append(("name", source[start:at], first_line))
        else:
            at += 1
            if not source[start].isspace():
                result.append(("symbol", source[start], first_line))
        line += source[start:at].count("\n")
    return result


def source_report(source: str) -> dict:
    items = tokens(source)
    refs, requires, methods, properties = [], [], [], []
    for i, (kind, value, line) in enumerate(items):
        if kind != "name":
            continue
        if value == "CP" and (i == 0 or items[i - 1][1] not in (".", ":")):
            chain, end = ["CP"], i + 1
            while end + 1 < len(items) and items[end][1] == "." and items[end + 1][0] == "name":
                chain.append(items[end + 1][1])
                end += 2
            if len(chain) > 1:
                refs.append({"symbol": ".".join(chain), "line": line,
                             "call": end < len(items) and items[end][1] == "("})
        if value == "require":
            end = i + 1
            if end < len(items) and items[end][1] == "(":
                end += 1
            if end < len(items) and items[end][0] == "string":
                requires.append({"module": items[end][1], "line": line})
        if value == "_nativeLayer" and i + 2 < len(items):
            separator, name = items[i + 1], items[i + 2]
            if name[0] == "name" and separator[1] in (".", ":"):
                target = methods if separator[1] == ":" else properties
                target.append({"name": name[1], "line": line})
    return {"cpReferences": refs, "requires": requires,
            "nativeLayerMethods": methods, "nativeLayerProperties": properties}


def inspect(path: Path, expected_sha256: str | None = None) -> dict:
    if path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("Archive exceeds size limit")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if expected_sha256 is not None and digest != expected_sha256.lower():
        raise ValueError("Archive digest does not match the supplied expected digest")
    modules, counts, all_refs, seen, sources = {}, Counter(), Counter(), set(), {}
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ENTRIES:
            raise ValueError("Archive has too many entries")
        total = 0
        for entry in entries:
            name = entry.filename
            parts = PurePosixPath(name).parts
            if not name or name.startswith("/") or "\\" in name or "\x00" in name or ".." in parts:
                raise ValueError("Unsafe archive path")
            canonical = "/".join(parts)
            if canonical in seen:
                raise ValueError("Duplicate archive path")
            seen.add(canonical)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode) or entry.flag_bits & 1:
                raise ValueError("Symlinks and encrypted archive entries are unsupported")
            if entry.is_dir():
                continue
            total += entry.file_size
            if entry.file_size > MAX_ENTRY or total > MAX_TOTAL:
                raise ValueError("Archive expansion exceeds limit")
            extension = PurePosixPath(name).suffix.lower()
            counts[extension] += 1
            if extension == ".lua":
                # Read at most the declared bounded size; CRC is checked by ZipFile.
                with archive.open(entry) as stream:
                    raw = stream.read(MAX_ENTRY + 1)
                if len(raw) != entry.file_size or len(raw) > MAX_ENTRY:
                    raise ValueError("Invalid Lua entry size")
                source = raw.decode("utf-8-sig", errors="strict")
                report = source_report(source)
                report["sha256"] = hashlib.sha256(raw).hexdigest()
                modules[name] = report
                sources[name] = source
                all_refs.update(ref["symbol"] for ref in report["cpReferences"])
    module_names = {name[:-4].replace("/", ".") for name in modules}
    missing = sorted({req["module"] for report in modules.values() for req in report["requires"]
                      if req["module"] not in module_names})
    bridge = sources.get("APL/BridgeVersion.lua", "")
    version = re.search(r'semver\s*=\s*[\"\']([^\"\']+)', bridge)
    return {"archiveSha256": digest, "archiveBytes": path.stat().st_size,
            "uncompressedBytes": total, "entryTypes": dict(sorted(counts.items())),
            "bridgeVersion": version[1] if version else None,
            "cpRootSymbols": sorted({name.split(".")[1] for name in all_refs}),
            "cpReferences": dict(sorted(all_refs.items())), "externalModules": missing,
            "modules": modules, "scriptsExecuted": False, "signatureVerified": False,
            "wireTypeMappingsRecovered": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-sha256")
    args = parser.parse_args()
    try:
        report = inspect(args.archive, args.expected_sha256)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Inspection failed: {error}\n")
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n")
    print(f"Inspected {len(report['modules'])} Lua modules; {len(report['cpRootSymbols'])} CP root symbols; no scripts executed.")


if __name__ == "__main__":
    main()
