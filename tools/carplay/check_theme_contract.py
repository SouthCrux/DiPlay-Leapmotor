#!/usr/bin/env python3
"""Compare a generated negotiation plist with a phone package's JSON metadata.

No scripts are executed and no authenticity, renderer or iPhone acceptance is inferred.
"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re

MAX_BYTES = 4 * 1024 * 1024


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def read_package(path):
    with Path(path).open("rb") as source:
        data = source.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("Package metadata too large")
    return json.loads(data, object_pairs_hook=unique_object), hashlib.sha256(data).hexdigest()


def contract(package, selected_roles=("centerConsole", "cluster")):
    if (not selected_roles or len(set(selected_roles)) != len(selected_roles) or
            any(role not in ("centerConsole", "cluster") for role in selected_roles) or
            "centerConsole" not in selected_roles):
        raise ValueError("Select centerConsole, optionally with cluster")
    displays = package.get("displays")
    if not isinstance(displays, list) or not 1 <= len(displays) <= 16:
        raise ValueError("Invalid display list")
    roles, ids = {}, set()
    for display in displays:
        name, size, density = display.get("id"), display.get("size"), display.get("density")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name) or name in ids:
            raise ValueError("Invalid or duplicate display ID")
        if not isinstance(size, list) or len(size) != 2 or any(type(n) is not int or not 1 <= n <= 8192 for n in size):
            raise ValueError("Invalid display size")
        if size[0] * size[1] > 32_000_000 or type(density) is not int or not 40 <= density <= 1000:
            raise ValueError("Invalid display geometry")
        ids.add(name)
        role = display.get("type")
        if role not in ("cluster", "centerConsole"):
            continue
        if role in roles:
            raise ValueError("Ambiguous display role")
        roles[role] = dict(id=name, width=size[0], height=size[1], densityDpi=density)
    if not set(selected_roles).issubset(roles):
        raise ValueError("Selected display role is missing from package")
    return dict(asset=dict(identifier=package["identifier"], version=package["version"],
                           sdkVersion=package["minimumSDKVersion"]),
                displays={role: roles[role] for role in selected_roles})


def compare(package, negotiation, selected_roles=("centerConsole", "cluster"), compose_center=False):
    expected = contract(package, selected_roles)
    errors = []
    if negotiation.get("asset") != expected["asset"]:
        errors.append("resource descriptor differs from package")
    info = negotiation.get("info", {})
    if "uiSyncInfo" not in info:
        errors.append("UI Sync info absent")
    if "vehicleStateProtocolInfo" in info:
        errors.append("vehicle data unexpectedly present in handshake-only probe")
    streams = info.get("displays", [])
    panels = info.get("displayPanels", [])
    if not isinstance(streams, list) or not isinstance(panels, list):
        raise ValueError("Invalid negotiation display lists")
    names = {d["id"] for d in expected["displays"].values()}
    if len(panels) != len(names) or {p.get("uid") for p in panels} != names:
        errors.append("panel IDs do not match resource displays")
    if len(streams) != len(names):
        errors.append("video stream count differs from selected resource displays")
    for role, stream_type in [("centerConsole", 110), ("cluster", 111)]:
        if role not in expected["displays"]:
            continue
        target = expected["displays"][role]
        matching = [s for s in streams if s.get("type") == stream_type]
        if len(matching) != 1:
            errors.append(f"{role}: missing or duplicate stream role")
            continue
        stream = matching[0]
        if stream.get("uuid") != target["id"]:
            errors.append(f"{role}: video identity differs from resource")
        if [stream.get("widthPixels"), stream.get("heightPixels")] != [target["width"], target["height"]]:
            errors.append(f"{role}: video canvas differs from resource")
        panel = next((p for p in panels if p.get("uid") == target["id"]), None)
        if panel is None:
            continue
        if [panel.get("widthPixels"), panel.get("heightPixels")] != [target["width"], target["height"]]:
            errors.append(f"{role}: panel canvas differs from resource")
        if [s.get("uuid") for s in panel.get("initialVideoStreams", [])] != [target["id"]]:
            errors.append(f"{role}: panel/video binding differs")
        plugins = panel.get("displayPlugins", [])
        if role == "cluster":
            if len(plugins) != 1 or plugins[0].get("uid") != target["id"] or plugins[0].get("videoStreams") != [target["id"]]:
                errors.append("cluster: plug-in/video binding differs")
        else:
            if compose_center:
                if (len(plugins) != 1 or plugins[0].get("uid") != target["id"] or
                        plugins[0].get("videoStreams") != [target["id"]]):
                    errors.append("centerConsole: plug-in/video binding differs")
            elif plugins:
                errors.append("centerConsole: unexpected local-rendering plug-in")
            if panel.get("properties", []):
                errors.append("centerConsole: unexpected instrument capability")
    center = expected["displays"]["centerConsole"]["id"]
    devices = info.get("hidDevices", [])
    if not devices or any(d.get("displayUUID") != center for d in devices):
        errors.append("HID controls target a different display")
    plugin_count = sum(len(panel.get("displayPlugins", [])) for panel in panels)
    topology_warnings = []
    if plugin_count == 0:
        topology_warnings.append("No display plug-in declared: the reference implementation's Ultra trigger is absent")
    return dict(metadataMatches=not errors, mismatches=errors, expected=expected,
                declaredDisplayPluginCount=plugin_count, topologyWarnings=topology_warnings,
                centerCompositionDeclared=compose_center,
                selectedRoles=list(selected_roles), topologyAcceptanceVerified=False,
                rendererVerified=False, phoneAcceptanceVerified=False, signatureVerified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package")
    parser.add_argument("negotiation")
    parser.add_argument("--output")
    parser.add_argument("--center-only", action="store_true",
                        help="Check an explicitly selected central topology; not proof the iPhone permits it")
    parser.add_argument("--compose-center", action="store_true",
                        help="Require the experimental center compositor's plug-in/video binding")
    args = parser.parse_args()
    package, digest = read_package(args.package)
    data = Path(args.negotiation).read_bytes()
    if len(data) > MAX_BYTES:
        raise ValueError("Negotiation too large")
    roles = ("centerConsole",) if args.center_only else ("centerConsole", "cluster")
    result = compare(package, plistlib.loads(data), roles, compose_center=args.compose_center)
    result["packageMetadataSha256"] = digest
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(output)
    else:
        print(output, end="")
    return 0 if result["metadataMatches"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
