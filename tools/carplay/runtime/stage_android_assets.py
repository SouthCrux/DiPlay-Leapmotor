#!/usr/bin/env python3
"""Stage an explicitly selected reference package for an opt-in Android debug build.

No network, credential discovery, signature claim, or automatic package selection. The
result is application-controlled development input, not code signed by the phone.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import plistlib
import re
import shutil
import stat
import tempfile

HERE = Path(__file__).resolve().parent
PREFIX = Path('ultra/reference-center')
MANIFEST = 'research-input.plist'
MAX_FILE = 4 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
SHA256 = re.compile(r'[0-9a-f]{64}')
SAFE_PATH = re.compile(r'[A-Za-z0-9_+./-]{1,200}')
REQUIRED = {'main.lua', 'r/PackageData.lua', 'LayoutController.lua', 'Workspace.lua', 'Info.plist'}
PROFILE = dict(profile='reference-center-wallpaper-v1', assetId='44C9FA31EC',
               assetVersion=985, sdkVersion='19.0', panelId='Center_Display',
               layoutId='9E19BC4E-5251-5821-A0BA-26479A79F722', width=1920,
               height=720, densityDpi=157)


@dataclass(frozen=True)
class ResourceIdentity:
    archive_sha256: str
    manifest_sha256: str
    source_modules: int = 193


REFERENCE = ResourceIdentity(
    'ddf1317ef051f8ac251c9d892b5ea0651a75e14dd59e0f5ac2ff13b37729d504',
    'a76a3daf759b480e7c165d29fad162a79efaaf3f795ec55baf2cc3d7b7c49796')


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _root(path: Path) -> Path:
    if path.is_symlink() or not path.is_dir():
        raise ValueError('Expected an existing, non-symbolic directory')
    return path.resolve(strict=True)


def _name(name: object) -> str:
    if (not isinstance(name, str) or not SAFE_PATH.fullmatch(name) or
            any(part in ('', '.', '..') for part in name.split('/')) or
            not name.endswith(('.lua', '.png', '.plist')) or
            any(part.startswith('research-input.') for part in name.split('/'))):
        raise ValueError('Unsafe or non-source resource path')
    return name


def _read(root: Path, name: str, limit: int) -> bytes:
    path = root
    parts = name.split('/')
    for index, part in enumerate(parts):
        path /= part
        mode = path.lstat().st_mode
        if not (stat.S_ISREG(mode) if index == len(parts) - 1 else stat.S_ISDIR(mode)):
            raise ValueError('Resource path is symbolic or not a regular file')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError('Expected a regular resource')
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Resource size limit exceeded')
    return data


def _plist(data: bytes) -> dict:
    if not data.startswith(b'bplist00'):
        raise ValueError('Expected binary property list')
    try:
        value = plistlib.loads(data)
    except Exception as failure:
        raise ValueError('Malformed property list') from failure
    if not isinstance(value, dict):
        raise ValueError('Expected property-list dictionary')
    return value


def _resources(source: Path, expected: ResourceIdentity) -> dict[str, bytes]:
    """Check the pinned binary manifest before parsing or opening any listed resource."""
    root = _root(source)
    if (not SHA256.fullmatch(expected.archive_sha256) or
            not SHA256.fullmatch(expected.manifest_sha256) or
            type(expected.source_modules) is not int or not 1 <= expected.source_modules <= 4096):
        raise ValueError('Invalid selected identity')
    manifest_bytes = _read(root, MANIFEST, 1024 * 1024)
    if _sha(manifest_bytes) != expected.manifest_sha256:
        raise ValueError('Selected manifest digest differs')
    manifest = _plist(manifest_bytes)
    if (manifest.get('archiveSha256') != expected.archive_sha256 or
            type(manifest.get('sourceModules')) is not int or
            manifest['sourceModules'] != expected.source_modules or
            manifest.get('bridgeVersionRequired') != '1.4.0' or
            any(manifest.get(key) is not False for key in
                ('bytecodeExtracted', 'scriptsExecuted', 'signatureVerified'))):
        raise ValueError('Selected source preparation differs')
    files = manifest.get('files')
    if not isinstance(files, dict) or not 1 <= len(files) <= 4096:
        raise ValueError('Invalid source inventory')
    for name, digest in files.items():
        _name(name)
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ValueError('Invalid source digest')
    if not REQUIRED <= files.keys() or sum(name.endswith('.lua') for name in files) != expected.source_modules:
        raise ValueError('Original source controllers or module count differ')
    contents = {MANIFEST: manifest_bytes}
    total = 0
    for name, digest in files.items():
        data = _read(root, name, MAX_FILE)
        total += len(data)
        if total > MAX_TOTAL or _sha(data) != digest:
            raise ValueError('Source resource size or digest differs')
        contents[name] = data
    info = _plist(contents['Info.plist'])
    if (info.get('CFBundleIdentifier') != PROFILE['assetId'] or
            type(info.get('AccessoryContentVersion')) is not int or
            info['AccessoryContentVersion'] != PROFILE['assetVersion'] or
            info.get('CFBundleVersion') != str(PROFILE['assetVersion']) or
            info.get('MinimumSDKVersion') != PROFILE['sdkVersion']):
        raise ValueError('Selected resource package metadata differs')
    return contents


def _code() -> dict[str, bytes]:
    return {name: _read(HERE, name, MAX_FILE)
            for name in ('bridge.luau', 'center_wallpaper_entry.luau')}


def _selection(code: dict[str, bytes]) -> dict:
    return dict(PROFILE, bridgeSha256=_sha(code['bridge.luau']),
                entrySha256=_sha(code['center_wallpaper_entry.luau']))


def _exact_tree(root: Path, files: set[str]) -> None:
    wanted_dirs = set()
    for name in files:
        wanted_dirs.update(parent.as_posix() for parent in Path(name).parents if parent != Path('.'))
    seen_files, seen_dirs = set(), set()
    for directory, dirs, names in os.walk(root, followlinks=False):
        for name in dirs:
            path = Path(directory, name)
            if path.is_symlink():
                raise ValueError('Symbolic staged directory')
            seen_dirs.add(path.relative_to(root).as_posix())
        for name in names:
            path = Path(directory, name)
            if not stat.S_ISREG(path.lstat().st_mode):
                raise ValueError('Non-regular staged resource')
            seen_files.add(path.relative_to(root).as_posix())
    if seen_files != files or seen_dirs != wanted_dirs:
        raise ValueError('Staged asset inventory has missing or extra entries')


def verify(directory: Path, *, expected: ResourceIdentity = REFERENCE) -> dict:
    root = _root(directory)
    package = root / PREFIX
    # Check every package ancestor before reading its manifest or descriptor.
    for path in (root / 'ultra', package, package / 'resources'):
        if path.is_symlink() or not path.is_dir():
            raise ValueError('Missing or symbolic staged asset prefix')
    resources = _resources(package / 'resources', expected)
    code = _code()
    selection = _plist(_read(package, 'selection.plist', 64 * 1024))
    if selection != _selection(code):
        raise ValueError('Staged profile or source code identity differs')
    for name, data in code.items():
        if _read(package, name, MAX_FILE) != data:
            raise ValueError('Staged source differs from current repository')
    names = {str(PREFIX / 'resources' / name) for name in resources}
    names.update(str(PREFIX / name) for name in (*code, 'selection.plist'))
    _exact_tree(root, names)
    return dict(profile=PROFILE['profile'], files=len(names), sourceModules=expected.source_modules,
                signatureVerified=False, phoneAcceptanceVerified=False)


def stage(source: Path, output: Path, *, expected: ResourceIdentity = REFERENCE) -> dict:
    if output.exists() or output.is_symlink():
        raise ValueError('Output must be a fresh directory')
    parent = _root(output.parent)
    destination = parent / output.name
    resources = _resources(source, expected)
    code = _code()
    temporary = Path(tempfile.mkdtemp(prefix='.ultra-stage-', dir=parent))
    try:
        package = temporary / PREFIX
        for name, data in resources.items():
            path = package / 'resources' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        for name, data in code.items():
            (package / name).write_bytes(data)
        (package / 'selection.plist').write_bytes(plistlib.dumps(_selection(code), fmt=plistlib.FMT_BINARY))
        result = verify(temporary, expected=expected)
        if destination.exists() or destination.is_symlink():
            raise ValueError('Output appeared during staging')
        temporary.rename(destination)
        return result
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    staging = commands.add_parser('stage')
    staging.add_argument('--source', type=Path, required=True)
    staging.add_argument('--output', type=Path, required=True)
    checking = commands.add_parser('verify')
    checking.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = (stage(args.source, args.output) if args.command == 'stage' else verify(args.directory))
    except (ValueError, OSError) as failure:
        parser.exit(1, f'{failure}\n')
    print(f"Verified {result['files']} development assets; phone acceptance unverified.")


if __name__ == '__main__':
    main()
