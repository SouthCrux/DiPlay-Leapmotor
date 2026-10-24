#!/usr/bin/env python3
"""Offline, explicit YUV420 hypothesis preview. Never connects to a camera or vehicle.

Supports tightly packed 8-bit NV12/NV21/I420/YV12 only. Output is a PNG preview,
not proof of the physical camera format, dimensions, colorimetry or channel.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import uuid
import zlib

FORMATS = ("NV12", "NV21", "I420", "YV12")
MATRICES = {"BT601": (0.299, 0.114), "BT709": (0.2126, 0.0722)}
CAMERAS = {"AVM_FRONT": 2, "AVM_BACK": 3, "AVM_LEFT": 4, "AVM_RIGHT": 5}
MAX_RAW_BYTES = 16 * 1024 * 1024
MAX_PIXELS = 4_194_304


def geometry(width, height):
    if (type(width) is not int or type(height) is not int or
            not 2 <= width <= 4096 or not 2 <= height <= 4096 or
            width % 2 or height % 2 or width * height > MAX_PIXELS):
        raise ValueError("Unsupported geometry")
    return width * height


def read_bounded(path, limit):
    with Path(path).open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Input limit exceeded")
    return data


def capture_provenance(raw, manifest_bytes=None, synthetic=False):
    """Never follows rawFile or copies unbounded/private fields from the manifest."""
    digest = hashlib.sha256(raw).hexdigest()
    if synthetic:
        if manifest_bytes is not None:
            raise ValueError("Ambiguous provenance")
        return dict(inputKind="DECLARED_SYNTHETIC", rawSha256=digest)
    if manifest_bytes is None or len(manifest_bytes) > 65536:
        raise ValueError("Capture manifest required")
    manifest = json.loads(manifest_bytes)
    if (not isinstance(manifest, dict) or type(manifest.get("schemaVersion")) is not int or
            manifest["schemaVersion"] != 1 or manifest.get("status") != "FRAME_BYTES_CAPTURED" or
            manifest.get("captureOrigin") != "OEM_SDK_CALLBACK" or
            manifest.get("encodedImageAvailable") is not False or
            manifest.get("requestedConversion") != "YUV420" or
            type(manifest.get("frameBytes")) is not int or manifest["frameBytes"] != len(raw)):
        raise ValueError("Invalid capture manifest")
    sha = manifest.get("sha256")
    if not isinstance(sha, str) or re.fullmatch(r"[0-9a-f]{64}", sha) is None or sha != digest:
        raise ValueError("Capture hash mismatch")
    camera = manifest.get("camera")
    if (not isinstance(camera, str) or camera not in CAMERAS or
            type(manifest.get("cameraIdRequested")) is not int or
            manifest["cameraIdRequested"] != CAMERAS[camera]):
        raise ValueError("Unknown camera selection")
    capture_id = manifest.get("captureId")
    if not isinstance(capture_id, str) or len(capture_id) != 36 or uuid.UUID(capture_id).version != 4:
        raise ValueError("Invalid capture identity")
    return dict(inputKind="SDK_RAW_FORMAT_UNVERIFIED", rawSha256=digest,
                manifestSha256=hashlib.sha256(manifest_bytes).hexdigest(),
                captureId=str(uuid.UUID(capture_id)), cameraRequested=camera,
                cameraIdRequested=CAMERAS[camera])


def decode_tight_yuv420(raw, width, height, pixel_format, matrix, quantization):
    """Nearest-neighbour chroma reconstruction; inverse Kr/Kb, fixed point with 16 fraction bits.
    No headers, tiling, padding, orientation changes, range detection or color profile inference.
    """
    pixels = geometry(width, height)
    if (type(raw) is not bytes or len(raw) != pixels * 3 // 2 or
            pixel_format not in FORMATS or matrix not in MATRICES or quantization not in ("limited", "full")):
        raise ValueError("Unsupported payload or conversion parameters")
    kr, kb = MATRICES[matrix]
    kg = 1 - kr - kb
    ys, cs, offset = (255 / 219, 255 / 224, 16) if quantization == "limited" else (1, 1, 0)
    scale = 65536
    yt = [round((i - offset) * ys * scale) for i in range(256)]
    rv = [round((i - 128) * cs * 2 * (1 - kr) * scale) for i in range(256)]
    bu = [round((i - 128) * cs * 2 * (1 - kb) * scale) for i in range(256)]
    gu = [round(-(i - 128) * cs * 2 * kb * (1 - kb) / kg * scale) for i in range(256)]
    gv = [round(-(i - 128) * cs * 2 * kr * (1 - kr) / kg * scale) for i in range(256)]
    plane = pixels // 4
    rgb = bytearray(pixels * 3)
    # Each chroma pair applies to one 2x2 block; plane order is explicit.
    for cy in range(height // 2):
        for cx in range(width // 2):
            index = cy * (width // 2) + cx
            if pixel_format in ("NV12", "NV21"):
                first, second = raw[pixels + 2 * index:pixels + 2 * index + 2]
            else:
                first, second = raw[pixels + index], raw[pixels + plane + index]
            u, v = (first, second) if pixel_format in ("NV12", "I420") else (second, first)
            radd, gadd, badd = rv[v], gu[u] + gv[v], bu[u]
            for dy in (0, 1):
                start = (2 * cy + dy) * width + 2 * cx
                for pos in (start, start + 1):
                    y = yt[raw[pos]]
                    base = pos * 3
                    rgb[base] = min(255, max(0, (y + radd + 32768) >> 16))
                    rgb[base + 1] = min(255, max(0, (y + gadd + 32768) >> 16))
                    rgb[base + 2] = min(255, max(0, (y + badd + 32768) >> 16))
    return bytes(rgb)


def png_preview(rgb, width, height, synthetic=False):
    pixels = geometry(width, height)
    if type(rgb) is not bytes or len(rgb) != pixels * 3:
        raise ValueError("RGB size mismatch")
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    stride = width * 3
    scanlines = b"".join(b"\0" + rgb[y * stride:(y + 1) * stride] for y in range(height))
    label = b"SYNTHETIC DECODER TEST; NOT A VEHICLE IMAGE" if synthetic else b"UNVERIFIED CAMERA FORMAT PREVIEW"
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) +
            chunk(b"tEXt", b"Description\0" + label) + chunk(b"IDAT", zlib.compress(scanlines, 6)) + chunk(b"IEND", b""))


def convert(raw_path, output_directory, width, height, pixel_format, matrix, quantization,
            manifest_path=None, synthetic=False):
    geometry(width, height)
    if (manifest_path is None and not synthetic) or (manifest_path is not None and synthetic):
        raise ValueError("Choose a manifest or declared synthetic input")
    raw = read_bounded(raw_path, MAX_RAW_BYTES)
    manifest = read_bounded(manifest_path, 65536) if manifest_path is not None else None
    source = capture_provenance(raw, manifest, synthetic)
    rgb = decode_tight_yuv420(raw, width, height, pixel_format, matrix, quantization)
    png = png_preview(rgb, width, height, synthetic)
    filename = "preview-synthetic.png" if synthetic else "preview-unverified.png"
    report = dict(schemaVersion=1, **source, previewFile=filename, previewSha256=hashlib.sha256(png).hexdigest(),
                  previewWidth=width, previewHeight=height, pixelFormatAssumed=pixel_format,
                  matrixAssumed=matrix, quantizationAssumed=quantization, packingAssumed="TIGHT_CONTIGUOUS_8_BIT",
                  chromaReconstruction="NEAREST_NEIGHBOUR_2X2", rawBytes=len(raw),
                  pngEncoded=True, physicalFormatVerified=False, actualGeometryVerified=False,
                  actualChannelVerified=False, colorimetryVerified=False)
    encoded_report = json.dumps(report, indent=2, allow_nan=False) + "\n"
    directory = Path(output_directory)
    directory.mkdir()  # Atomic exclusive directory creation; never overwrite a previous preview.
    created = []
    try:
        image_path = directory / filename
        with image_path.open("xb") as stream:
            created.append(image_path)
            stream.write(png)
        report_path = directory / "decode.json"
        with report_path.open("x", encoding="utf-8") as stream:
            created.append(report_path)
            stream.write(encoded_report)
    except OSError:
        for path in created:
            try:
                path.unlink()
            except OSError:
                pass
        try:
            directory.rmdir()
        except OSError:
            pass
        raise
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--manifest", help="SDK capture JSON; rawFile is never followed")
    source.add_argument("--synthetic", action="store_true", help="Label invented test data explicitly")
    parser.add_argument("--width", required=True, type=int)
    parser.add_argument("--height", required=True, type=int)
    parser.add_argument("--pixel-format", required=True, choices=FORMATS)
    parser.add_argument("--matrix", required=True, choices=MATRICES)
    parser.add_argument("--range", required=True, choices=("limited", "full"), dest="quantization")
    parser.add_argument("--output-dir", required=True, help="New directory only; parent must exist")
    args = parser.parse_args()
    try:
        convert(args.raw, args.output_dir, args.width, args.height, args.pixel_format,
                args.matrix, args.quantization, args.manifest, args.synthetic)
    except (OSError, ValueError, TypeError, OverflowError, RecursionError, UnicodeError):
        parser.exit(1, "Preview rejected: check explicit layout/geometry, capture identity/hash and new output directory.\n")
    print("PNG preview saved; physical camera format and colorimetry remain unverified.")


if __name__ == "__main__":
    main()
