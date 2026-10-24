import binascii
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import uuid
import zlib

from camera_frame import capture_provenance, convert, decode_tight_yuv420, png_preview, read_bounded


def sample(pixel_format):
    # Invented 4x2 raster: two red columns, two blue columns. Not vehicle data.
    y = bytes((81, 81, 41, 41) * 2)
    return y + {"NV12": bytes((90, 240, 240, 110)), "NV21": bytes((240, 90, 110, 240)),
                "I420": bytes((90, 240, 240, 110)), "YV12": bytes((240, 110, 90, 240))}[pixel_format]


def manifest(raw):
    # Synthetic contract fixture matching the diagnostic app's JSON schema.
    return dict(schemaVersion=1, status="FRAME_BYTES_CAPTURED", captureOrigin="OEM_SDK_CALLBACK",
                encodedImageAvailable=False, requestedConversion="YUV420", frameBytes=len(raw),
                sha256=hashlib.sha256(raw).hexdigest(), camera="AVM_BACK", cameraIdRequested=3,
                captureId=str(uuid.uuid4()), requestedWidth=1920, requestedHeight=1536,
                rawFile="../../private-file-not-to-be-opened", privateField="do-not-copy")


def read_png(data):
    # Independent structural reader: verifies every CRC and extracts unfiltered RGB scanlines.
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise AssertionError("PNG signature")
    offset = 8
    chunks = []
    while offset < len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + size]
        crc = struct.unpack_from(">I", data, offset + 8 + size)[0]
        if crc != binascii.crc32(kind + payload) & 0xffffffff:
            raise AssertionError("PNG CRC")
        chunks.append((kind, payload)); offset += 12 + size
    if chunks[0][0] != b"IHDR" or chunks[-1] != (b"IEND", b""):
        raise AssertionError("PNG chunk order")
    width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", chunks[0][1])
    if (depth, color, compression, filtering, interlace) != (8, 2, 0, 0, 0):
        raise AssertionError("PNG RGB format")
    scanlines = zlib.decompress(b"".join(payload for kind, payload in chunks if kind == b"IDAT"))
    stride = 3 * width + 1
    if len(scanlines) != stride * height or any(scanlines[row * stride] != 0 for row in range(height)):
        raise AssertionError("PNG scanlines")
    rgb = b"".join(scanlines[row * stride + 1:(row + 1) * stride] for row in range(height))
    return width, height, rgb, dict(chunks)


class CameraFrameTest(unittest.TestCase):
    def test_four_plane_orders_produce_same_known_red_and_blue_macroblocks(self):
        # Four different chroma cells distinguish planar and semi-planar addressing.
        y = bytes((81, 81, 41, 41) * 2 + (145, 145, 235, 235) * 2)
        chroma = {"NV12": bytes((90, 240, 240, 110, 54, 34, 128, 128)),
                  "NV21": bytes((240, 90, 110, 240, 34, 54, 128, 128)),
                  "I420": bytes((90, 240, 54, 128, 240, 110, 34, 128)),
                  "YV12": bytes((240, 110, 34, 128, 90, 240, 54, 128))}
        results = [decode_tight_yuv420(y + chroma[fmt], 4, 4, fmt, "BT601", "limited")
                   for fmt in ("NV12", "NV21", "I420", "YV12")]
        self.assertTrue(all(rgb == results[0] for rgb in results))
        expected = bytes((255, 0, 0, 255, 0, 0, 0, 0, 255, 0, 0, 255) * 2 +
                         (0, 255, 0, 0, 255, 0, 255, 255, 255, 255, 255, 255) * 2)
        self.assertTrue(all(abs(a - b) <= 1 for a, b in zip(expected, results[0])))
        self.assertNotEqual(results[0], decode_tight_yuv420(y + chroma["NV12"], 4, 4, "NV21", "BT601", "limited"))

    def test_explicit_range_and_matrix_are_applied_without_auto_detection(self):
        for quantization, black, white in (("limited", 16, 235), ("full", 0, 255)):
            raw = bytes((black, white, black, white, 128, 128))
            for matrix in ("BT601", "BT709"):
                self.assertEqual(bytes((0, 0, 0, 255, 255, 255) * 2),
                                 decode_tight_yuv420(raw, 2, 2, "NV12", matrix, quantization))
        self.assertNotEqual(decode_tight_yuv420(sample("NV12"), 4, 2, "NV12", "BT601", "limited"),
                            decode_tight_yuv420(sample("NV12"), 4, 2, "NV12", "BT709", "limited"))

    def test_padding_truncation_odd_or_excessive_geometry_and_unknown_parameters_fail(self):
        cases = [(sample("NV12")[:-1], 4, 2, "NV12", "BT601", "limited"),
                 (sample("NV12") + b"padding", 4, 2, "NV12", "BT601", "limited"),
                 (b"", 3, 2, "NV12", "BT601", "limited"), (b"", 4096, 4096, "NV12", "BT601", "limited"),
                 (b"", True, 2, "NV12", "BT601", "limited"),
                 (sample("NV12"), 4, 2, "AUTO", "BT601", "limited"),
                 (sample("NV12"), 4, 2, "NV12", "AUTO", "limited"),
                 (sample("NV12"), 4, 2, "NV12", "BT601", "AUTO")]
        for values in cases:
            with self.subTest(values=values[1:]), self.assertRaises(ValueError):
                decode_tight_yuv420(*values)

    def test_png_crc_dimensions_rgb_and_unverified_or_synthetic_labels(self):
        rgb = decode_tight_yuv420(sample("NV12"), 4, 2, "NV12", "BT601", "limited")
        for synthetic in (False, True):
            width, height, decoded, chunks = read_png(png_preview(rgb, 4, 2, synthetic))
            self.assertEqual((4, 2), (width, height)); self.assertEqual(rgb, decoded)
            self.assertIn(b"SYNTHETIC" if synthetic else b"UNVERIFIED", chunks[b"tEXt"])
            self.assertNotIn(b"sRGB", chunks)  # No claim of calibrated color profile.

    def test_manifest_hash_identity_and_camera_are_required_without_copying_private_fields(self):
        raw = sample("NV12")
        valid = manifest(raw)
        source = capture_provenance(raw, json.dumps(valid).encode())
        self.assertEqual("SDK_RAW_FORMAT_UNVERIFIED", source["inputKind"])
        self.assertNotIn("do-not-copy", json.dumps(source))
        self.assertNotIn("private-file", json.dumps(source))
        for key, wrong in (("sha256", "0" * 64), ("status", "SDK_UNAVAILABLE"), ("frameBytes", 1),
                           ("camera", "FACE"), ("cameraIdRequested", 1), ("captureId", "bad"),
                           ("encodedImageAvailable", True), ("requestedConversion", "UNKNOWN")):
            changed = dict(valid, **{key: wrong})
            with self.subTest(key=key), self.assertRaises(ValueError):
                capture_provenance(raw, json.dumps(changed).encode())

    def test_cli_checks_manifest_then_writes_new_bundle_without_overwriting_or_following_rawfile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "renamed.raw"; raw.write_bytes(sample("NV12"))
            metadata = root / "capture.json"; metadata.write_text(json.dumps(manifest(raw.read_bytes())))
            output = root / "preview"
            command = [sys.executable, str(Path(__file__).with_name("camera_frame.py")), "--raw", str(raw),
                       "--manifest", str(metadata), "--width", "4", "--height", "2", "--pixel-format", "NV12",
                       "--matrix", "BT601", "--range", "limited", "--output-dir", str(output)]
            self.assertEqual(0, subprocess.run(command, capture_output=True, timeout=10).returncode)
            report = json.loads((output / "decode.json").read_text())
            png = (output / report["previewFile"]).read_bytes()
            self.assertEqual(hashlib.sha256(png).hexdigest(), report["previewSha256"])
            self.assertFalse(report["physicalFormatVerified"]); self.assertFalse(report["actualGeometryVerified"])
            self.assertFalse(report["actualChannelVerified"]); self.assertFalse(report["colorimetryVerified"])
            self.assertEqual((4, 2), read_png(png)[:2])
            before = {p.name: p.read_bytes() for p in output.iterdir()}
            self.assertNotEqual(0, subprocess.run(command, capture_output=True, timeout=10).returncode)
            self.assertEqual(before, {p.name: p.read_bytes() for p in output.iterdir()})

    def test_failed_manifest_or_payload_creates_no_output_and_synthetic_provenance_is_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "sample.raw"; raw.write_bytes(sample("NV12"))
            metadata = root / "capture.json"; metadata.write_text(json.dumps(dict(manifest(raw.read_bytes()), sha256="0" * 64)))
            output = root / "rejected"
            with self.assertRaises(ValueError):
                convert(raw, output, 4, 2, "NV12", "BT601", "limited", metadata)
            self.assertFalse(output.exists())
            result = convert(raw, output, 4, 2, "NV12", "BT601", "limited", synthetic=True)
            self.assertEqual("DECLARED_SYNTHETIC", result["inputKind"])
            self.assertIn(b"SYNTHETIC", read_png((output / result["previewFile"]).read_bytes())[3][b"tEXt"])
            with self.assertRaises(ValueError):
                convert(raw, root / "missing", 4, 2, "NV12", "BT601", "limited")
            with self.assertRaises(ValueError):
                convert(raw, root / "ambiguous", 4, 2, "NV12", "BT601", "limited", metadata, True)

    def test_bounded_reads_and_large_or_deep_manifests_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "bounded"); path.write_bytes(b"x" * 9)
            with self.assertRaises(ValueError):
                read_bounded(path, 8)
        with self.assertRaises(ValueError):
            capture_provenance(b"", b" " * 65537)
        with self.assertRaises((ValueError, RecursionError)):
            capture_provenance(b"", b"[" * 2000 + b"]" * 2000)


if __name__ == "__main__":
    unittest.main()
