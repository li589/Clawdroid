from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import pack_magisk_zip as packer


# Hand-maintained module contract from the files consumed by Magisk, service.sh,
# and verify.sh. Do not derive this list from the production validator.
REQUIRED_MODULE_FILES = (
    "module.prop",
    "service.sh",
    "post-fs-data.sh",
    "customize.sh",
    "verify.sh",
    "sepolicy.rule",
    "config/runtime.yaml",
    "bin/clawdroid-runtime",
    "bin/clawdroid-runtime.build.json",
    "webroot/index.html",
    "webroot/status.json",
    "webroot/verify.json",
    "webroot/verify.txt",
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
)

# Independent fixture encoder for the documented trailer contract:
# raw binary | canonical JSON | SHA-256(raw binary | JSON) | uint64 BE length | magic.
TRAILER_MAGIC = b"CLAWDROID_RUNTIME_BUILD_METADATA_V1"


def fixture_trailer(raw_binary: bytes, metadata: dict) -> bytes:
    payload = json.dumps(metadata, sort_keys=True, separators=(",", ":")).encode("utf-8")
    digest = hashlib.sha256(raw_binary + payload).digest()
    return raw_binary + payload + digest + len(payload).to_bytes(8, "big") + TRAILER_MAGIC


class PackMagiskZipTest(unittest.TestCase):
    def make_stage(self, root: Path) -> Path:
        stage = root / "stage"
        for relative in REQUIRED_MODULE_FILES:
            path = stage / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture\n")

        binary = stage / "bin/clawdroid-runtime"
        raw_binary = b"android-runtime-fixture"
        metadata = {
            "schema_version": 1,
            "revision": "a" * 40,
            "source_sha256": "b" * 64,
            "binary_sha256": hashlib.sha256(raw_binary).hexdigest(),
            "goos": "android",
            "goarch": "arm64",
            "cgo_enabled": "0",
        }
        binary.write_bytes(fixture_trailer(raw_binary, metadata))
        (stage / "bin/clawdroid-runtime.build.json").write_text(
            json.dumps(metadata), encoding="utf-8"
        )
        return stage

    def make_embedded_stage(self, root: Path) -> Path:
        stage = self.make_stage(root)
        raw_binary = b"android-runtime-fixture"
        metadata = {
            "schema_version": 1,
            "revision": "a" * 40,
            "source_sha256": "b" * 64,
            "binary_sha256": hashlib.sha256(raw_binary).hexdigest(),
            "goos": "android",
            "goarch": "arm64",
            "cgo_enabled": "0",
        }
        binary = stage / "bin/clawdroid-runtime"
        binary.write_bytes(fixture_trailer(raw_binary, metadata))
        # The old verifier trusted this value. Keeping it internally consistent
        # makes the red tests demonstrate sidecar trust rather than a hash typo.
        sidecar = dict(metadata)
        sidecar["binary_sha256"] = hashlib.sha256(binary.read_bytes()).hexdigest()
        (stage / "bin/clawdroid-runtime.build.json").write_text(
            json.dumps(sidecar), encoding="utf-8"
        )
        return stage

    def test_missing_critical_file_fails_without_replacing_old_output(self) -> None:
        for relative in REQUIRED_MODULE_FILES:
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                stage = self.make_stage(root)
                (stage / relative).unlink()
                output = root / "ClawRuntime-magisk.zip"
                output.write_bytes(b"previous-good-zip")

                with self.assertRaises((SystemExit, ValueError)):
                    packer.pack(stage, output)

                self.assertEqual(output.read_bytes(), b"previous-good-zip")
                self.assertEqual(list(root.glob("*.tmp")), [])

    def test_crc_failure_keeps_previous_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = self.make_stage(root)
            output = root / "ClawRuntime-magisk.zip"
            output.write_bytes(b"previous-good-zip")

            with mock.patch.object(zipfile.ZipFile, "testzip", return_value="service.sh"):
                with self.assertRaises((SystemExit, ValueError)):
                    packer.pack(stage, output)

            self.assertEqual(output.read_bytes(), b"previous-good-zip")
            self.assertEqual(list(root.glob("*.tmp")), [])

    def test_atomic_replace_failure_keeps_previous_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = self.make_stage(root)
            output = root / "ClawRuntime-magisk.zip"
            output.write_bytes(b"previous-good-zip")

            with mock.patch.object(packer.os, "replace", side_effect=OSError("replace failed")):
                with self.assertRaises(OSError):
                    packer.pack(stage, output)

            self.assertEqual(output.read_bytes(), b"previous-good-zip")
            self.assertEqual(list(root.glob("*.tmp")), [])

    def test_success_packs_required_files_with_valid_crc_and_unix_modes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = self.make_stage(root)
            (stage / "service.sh").write_bytes(b"#!/system/bin/sh\r\necho ok\r\n")
            (stage / "config/runtime.generated.yaml").write_bytes(b"must not ship\n")
            (stage / "config/runtime.yaml.example").write_bytes(b"must not ship\n")
            output = root / "nested/ClawRuntime-magisk.zip"

            packer.pack(stage, output)

            with zipfile.ZipFile(output) as archive:
                names = set(archive.namelist())
                self.assertTrue(set(REQUIRED_MODULE_FILES).issubset(names))
                self.assertNotIn("config/runtime.generated.yaml", names)
                self.assertNotIn("config/runtime.yaml.example", names)
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.read("service.sh"), b"#!/system/bin/sh\necho ok\n")
                service_mode = archive.getinfo("service.sh").external_attr >> 16
                binary_mode = archive.getinfo("bin/clawdroid-runtime").external_attr >> 16
                self.assertEqual(service_mode & 0o777, 0o755)
                self.assertEqual(binary_mode & 0o777, 0o755)
            self.assertEqual(list(output.parent.glob("*.tmp")), [])

    def test_tampered_binary_metadata_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = self.make_stage(root)
            (stage / "bin/clawdroid-runtime").write_bytes(b"replaced-after-build")
            output = root / "ClawRuntime-magisk.zip"

            with self.assertRaises((SystemExit, ValueError)):
                packer.pack(stage, output)

            self.assertFalse(output.exists())

    def test_missing_trailer_is_rejected_even_with_valid_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stage = self.make_stage(Path(tmp))
            (stage / "bin/clawdroid-runtime").write_bytes(b"android-runtime-fixture")

            with self.assertRaises(packer.PackError):
                packer.verify_build_metadata(stage)

    def test_sidecar_rewrite_cannot_authenticate_binary_without_trailer(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stage = self.make_stage(Path(tmp))
            (stage / "bin/clawdroid-runtime").write_bytes(b"android-runtime-fixture")
            sidecar_path = stage / "bin/clawdroid-runtime.build.json"
            metadata = json.loads(sidecar_path.read_text(encoding="utf-8"))
            metadata.update(
                revision="c" * 40,
                source_sha256="d" * 64,
                binary_sha256=hashlib.sha256(b"android-runtime-fixture").hexdigest(),
            )
            sidecar_path.write_text(json.dumps(metadata), encoding="utf-8")

            with self.assertRaises(packer.PackError):
                packer.verify_build_metadata(stage)

    def test_embedded_metadata_is_authoritative_over_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stage = self.make_embedded_stage(Path(tmp))
            sidecar_path = stage / "bin/clawdroid-runtime.build.json"
            metadata = json.loads(sidecar_path.read_text(encoding="utf-8"))
            metadata.update(revision="c" * 40, source_sha256="d" * 64)
            sidecar_path.write_text(json.dumps(metadata), encoding="utf-8")

            verified = packer.verify_build_metadata(stage)

            self.assertEqual(verified["revision"], "a" * 40)
            self.assertEqual(verified["source_sha256"], "b" * 64)

    def test_valid_embedded_metadata_does_not_require_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stage = self.make_embedded_stage(Path(tmp))
            (stage / "bin/clawdroid-runtime.build.json").unlink()

            try:
                verified = packer.verify_build_metadata(stage)
            except packer.PackError as exc:
                self.fail(f"embedded metadata should verify without a sidecar: {exc}")
            self.assertEqual(verified["revision"], "a" * 40)

    def test_corrupted_trailer_is_rejected(self) -> None:
        corruptions = (
            ("magic", -1),
            ("length", -len(TRAILER_MAGIC) - 8),
            ("digest", -len(TRAILER_MAGIC) - 8 - 32),
            ("payload", 0),
        )
        for name, offset in corruptions:
            with self.subTest(part=name), tempfile.TemporaryDirectory() as tmp:
                stage = self.make_embedded_stage(Path(tmp))
                binary_path = stage / "bin/clawdroid-runtime"
                damaged = bytearray(binary_path.read_bytes())
                damaged[offset] ^= 0x01
                binary_path.write_bytes(damaged)
                # Keep the legacy sidecar plausible so only trailer validation
                # can reject the corrupted binary.
                sidecar_path = stage / "bin/clawdroid-runtime.build.json"
                sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
                sidecar["binary_sha256"] = hashlib.sha256(damaged).hexdigest()
                sidecar_path.write_text(json.dumps(sidecar), encoding="utf-8")

                with self.assertRaises(packer.PackError):
                    packer.verify_build_metadata(stage)

    def test_pack_rejects_stale_sources_when_runtime_dir_is_supplied(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = self.make_embedded_stage(root)
            runtime_dir = root / "runtime"
            runtime_dir.mkdir()
            (runtime_dir / "main.go").write_text("package main\n", encoding="utf-8")
            output = root / "ClawRuntime-magisk.zip"
            argv = [
                "pack_magisk_zip.py",
                "--stage",
                str(stage),
                "--output",
                str(output),
                "--runtime-dir",
                str(runtime_dir),
            ]

            with mock.patch.object(sys, "argv", argv):
                with self.assertRaises(SystemExit) as raised:
                    packer.main()

            self.assertNotEqual(raised.exception.code, 0)
            self.assertFalse(output.exists())

    def test_write_build_metadata_embeds_verifiable_trailer(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module_dir = root / "module"
            binary = module_dir / "bin/clawdroid-runtime"
            binary.parent.mkdir(parents=True)
            binary.write_bytes(b"new-runtime")
            runtime_dir = root / "runtime"
            runtime_dir.mkdir()
            (runtime_dir / "main.go").write_text("package main\n", encoding="utf-8")

            packer.write_build_metadata(
                binary,
                runtime_dir,
                revision="a" * 40,
                goos="android",
                goarch="arm64",
            )

            self.assertTrue(binary.read_bytes().endswith(TRAILER_MAGIC))
            binary.with_name(binary.name + ".build.json").unlink()
            try:
                verified = packer.verify_build_metadata(module_dir)
            except packer.PackError as exc:
                self.fail(f"embedded metadata should verify without a sidecar: {exc}")
            self.assertEqual(verified["binary_sha256"], hashlib.sha256(b"new-runtime").hexdigest())

    def test_write_metadata_cli_refuses_to_authenticate_old_binary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            binary = root / "clawdroid-runtime"
            binary.write_bytes(b"old-runtime-without-provenance")
            runtime_dir = root / "runtime"
            runtime_dir.mkdir()
            (runtime_dir / "main.go").write_text("package main\n", encoding="utf-8")
            argv = [
                "pack_magisk_zip.py",
                "--write-build-metadata",
                "--binary",
                str(binary),
                "--runtime-dir",
                str(runtime_dir),
                "--revision",
                "a" * 40,
                "--goarch",
                "arm64",
            ]

            with mock.patch.object(sys, "argv", argv):
                with self.assertRaises(SystemExit) as raised:
                    packer.main()

            self.assertNotEqual(raised.exception.code, 0)
            self.assertEqual(binary.read_bytes(), b"old-runtime-without-provenance")

    def test_embed_metadata_cli_creates_verifiable_trailer(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            binary = root / "clawdroid-runtime"
            binary.write_bytes(b"new-runtime")
            runtime_dir = root / "runtime"
            runtime_dir.mkdir()
            (runtime_dir / "main.go").write_text("package main\n", encoding="utf-8")
            argv = [
                "pack_magisk_zip.py",
                "--embed-build-metadata",
                "--binary",
                str(binary),
                "--runtime-dir",
                str(runtime_dir),
                "--revision",
                "a" * 40,
                "--goarch",
                "arm64",
            ]

            with mock.patch.object(sys, "argv", argv):
                try:
                    result = packer.main()
                except SystemExit as exc:
                    self.fail(f"embedding fresh build metadata should succeed: {exc}")
            self.assertEqual(result, 0)
            self.assertTrue(binary.read_bytes().endswith(TRAILER_MAGIC))


if __name__ == "__main__":
    unittest.main()
