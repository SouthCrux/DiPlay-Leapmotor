#!/usr/bin/env python3
"""Prepare source/PNG-only resources from an explicitly selected, digest-pinned archive.

The digest binds the owner's chosen research input; this is NOT Apple signature verification.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import plistlib
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from inspect_accessory import inspect


def write_android_manifest(directory: Path, manifest: dict) -> str:
    """Deterministic bounded-plist input for Android; its digest must be selected out of band."""
    raw = plistlib.dumps(manifest, fmt=plistlib.FMT_BINARY, sort_keys=True)
    if len(raw) > 1024 * 1024:
        raise ValueError('Android resource manifest size limit')
    (directory / 'research-input.plist').write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def prepare(archive: Path, expected_sha256: str, directory: Path) -> dict:
    report = inspect(archive, expected_sha256)
    directory.mkdir(parents=True, exist_ok=False)
    # inspect() has already rejected unsafe/duplicate paths, symlinks and excessive expansion.
    digests = {}
    with zipfile.ZipFile(archive) as stream:
        for entry in stream.infolist():
            if entry.is_dir() or Path(entry.filename).suffix not in (".lua", ".png", ".plist"):
                continue
            raw = stream.read(entry)
            path = directory / entry.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            digests[entry.filename] = hashlib.sha256(raw).hexdigest()
    result = {"archiveSha256": report["archiveSha256"], "sourceModules": len(report["modules"]),
              "bridgeVersionRequired": report["bridgeVersion"], "files": digests,
              "bytecodeExtracted": False, "scriptsExecuted": False, "signatureVerified": False}
    (directory / "research-input.json").write_text(json.dumps(result, indent=2) + "\n")
    write_android_manifest(directory, result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        r = prepare(args.archive, args.expected_sha256, args.directory)
        print(f"Prepared {len(r['files'])} source/image/plist files; no bytecode extracted; no scripts executed.")
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Preparation failed: {error}\n")
