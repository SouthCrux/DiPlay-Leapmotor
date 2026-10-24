#!/usr/bin/env python3
"""Build the offscreen host from an explicitly supplied, pinned upstream source archive.

No downloads and no execution of upstream build scripts. Apple resources are not build inputs.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile

LUAU_VERSION = "0.743"
LUAU_SOURCE_SHA256 = "ed14de4375864304295d0d02eca2fe409e3c8f5a018a2e0697cf639f2ae5bcc8"
PARTS = ("Compiler", "Ast", "Bytecode", "VM", "Common")


def build(archive: Path, work: Path, output: Path, ndk: Path | None = None,
          jni: bool = False, java_home: Path | None = None) -> dict:
    if archive.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Upstream archive size limit")
    if hashlib.sha256(archive.read_bytes()).hexdigest() != LUAU_SOURCE_SHA256:
        raise ValueError("Upstream Luau source archive digest mismatch")
    # A fresh tree prevents stale local files or objects from becoming hidden build inputs.
    work.mkdir(parents=True, exist_ok=False)
    source = work / "source"
    source.mkdir()
    with tarfile.open(archive) as stream:
        for entry in stream:
            parts = PurePosixPath(entry.name).parts
            if not parts or ".." in parts or entry.name.startswith("/"):
                raise ValueError("Unsafe upstream source path")
            relative = Path(*parts[1:])
            if not relative.parts or (relative.parts[0] not in PARTS and relative.name != "LICENSE.txt"):
                continue
            if entry.isdir():
                continue
            if not entry.isfile() or entry.size > 8 * 1024 * 1024:
                raise ValueError("Unsupported upstream entry")
            path = source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(stream.extractfile(entry).read())
    if ndk:
        prebuilt = ndk / "toolchains/llvm/prebuilt"
        candidates = list(prebuilt.glob("*/bin/aarch64-linux-android25-clang++"))
        if len(candidates) != 1:
            raise ValueError("Expected one NDK arm64 compiler")
        compiler = str(candidates[0])
        archiver = str(candidates[0].parent / "llvm-ar")
        link_flags = ["-static-libstdc++"]
        target = "android-arm64-api25"
    else:
        compiler = shutil.which("clang++")
        archiver = shutil.which("ar")
        if not compiler or not archiver:
            raise ValueError("clang++ and ar are required")
        link_flags = []
        target = "host"
    includes = [flag for part in PARTS for flag in ("-isystem", str(source / part / "include"))]
    flags = ["-std=c++17", "-O2", "-DNDEBUG", "-fno-math-errno", *includes]
    if jni:
        flags += ["-fPIC", "-DDIPLAY_ULTRA_JNI", "-Wno-unused-function"]
        if not ndk:
            if not java_home or not (java_home / "include/jni.h").is_file():
                raise ValueError("Host JNI build requires --java-home with JNI headers")
            platform = "darwin" if __import__('sys').platform == 'darwin' else "linux"
            flags += ["-I", str(java_home / 'include'), "-I", str(java_home / 'include' / platform)]
        link_flags += ["-shared"]
    libraries = []
    for part in PARTS:
        units = sorted((source / part / "src").glob("*.cpp"))
        if not units:
            raise ValueError(f"No translation units for {part}")
        objects = []
        commands = []
        for unit in units:
            obj = work / f"{part}-{unit.stem}.o"
            objects.append(str(obj))
            commands.append([compiler, *flags, "-c", str(unit), "-o", str(obj)])
        with ThreadPoolExecutor(max_workers=4) as pool:
            # Materialize every result so a compiler failure cannot be lost.
            list(pool.map(lambda args: subprocess.run(args, check=True, capture_output=True), commands))
        library = work / f"{part}.a"
        subprocess.run([archiver, "rcs", str(library), *objects], check=True, capture_output=True)
        libraries.append(str(library))
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([compiler, *flags, "-Wall", "-Wextra", "-Werror", str(Path(__file__).with_name("host.cpp")),
                    *libraries, *link_flags, "-o", str(output)], check=True, capture_output=True)
    result = {"luauVersion": LUAU_VERSION, "upstreamArchiveSha256": LUAU_SOURCE_SHA256,
              "target": target, "binarySha256": hashlib.sha256(output.read_bytes()).hexdigest(),
              "appleResourcesBundled": False, "runtimeIntegratedInApk": False,
              "jniLibrary": jni}
    output.with_suffix(output.suffix + ".build.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-archive", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--android-ndk", type=Path)
    parser.add_argument("--jni", action="store_true", help="Build the embeddable shared library instead of the stdio executable")
    parser.add_argument("--java-home", type=Path, help="JNI headers for desktop integration tests; unnecessary with the NDK")
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.source_archive.resolve(), args.work.resolve(), args.output.resolve(),
                               args.android_ndk, args.jni, args.java_home)))
    except subprocess.CalledProcessError as error:
        parser.exit(1, f"Compiler failed: {error.stderr.decode(errors='replace') if error.stderr else error}\n")
    except (ValueError, OSError, tarfile.TarError) as error:
        parser.exit(1, f"Build failed: {error}\n")
