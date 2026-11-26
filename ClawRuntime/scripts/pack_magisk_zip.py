#!/usr/bin/env python3
"""Pack and verify a Magisk-compatible module zip (no Zip64, Unix attrs, LF scripts)."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path


SKIP_NAMES = {
    "runtime.generated.yaml",
    "runtime.yaml.example",
}

# Files consumed by the Magisk installer, service.sh, verify.sh, or the App.
# Keep this an explicit contract rather than accepting whatever happens to exist.
REQUIRED_FILES = (
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

BUILD_METADATA_NAME = "clawdroid-runtime.build.json"
BUILD_METADATA_MAGIC = b"CLAWDROID_RUNTIME_BUILD_METADATA_V1"
_TRAILER_DIGEST_BYTES = 32
_TRAILER_LENGTH_BYTES = 8
_MAX_METADATA_BYTES = 1024 * 1024
_HEX_64 = re.compile(r"^[0-9a-f]{64}$")
_REVISION = re.compile(r"^[0-9a-f]{40}(?:[0-9a-f]{24})?$")


class PackError(ValueError):
    """The staged module or generated archive failed a release invariant."""


def should_skip(path: Path, root: Path) -> bool:
    rel = path.relative_to(root).as_posix()
    name = path.name
    if name in SKIP_NAMES:
        return True
    if name.startswith("."):
        return True
    # Never pack Windows junk / empty placeholders.
    if rel.startswith("__MACOSX/") or rel.endswith("/.DS_Store"):
        return True
    return False


def unix_mode(path: Path) -> int:
    if path.suffix == ".sh" or path.name in {"update-binary", "customize.sh", "verify.sh"}:
        return 0o755
    if path.name == "clawdroid-runtime":
        return 0o755
    if path.name == "updater-script":
        return 0o644
    return 0o644


def list_packable_files(stage_dir: Path) -> list[Path]:
    """List real files under stage_dir without following symlinks.

    rglob on Python <3.13 follows symlinked directories during recursion,
    which could leak content from outside staging into the signed Magisk
    zip. os.walk(followlinks=False) is the safe alternative.
    """
    files: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(stage_dir, followlinks=False):
        # Prune symlinked subdirs so we neither descend into them nor
        # list their contents (defense-in-depth even though followlinks=False
        # already prevents descent).
        dirnames[:] = [d for d in dirnames if not (Path(dirpath) / d).is_symlink()]
        for filename in filenames:
            p = Path(dirpath) / filename
            if p.is_symlink():
                continue
            if not p.is_file():
                continue
            if should_skip(p, stage_dir):
                continue
            files.append(p)
    files.sort(key=lambda p: p.relative_to(stage_dir).as_posix())
    return files


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def runtime_source_fingerprint(runtime_dir: Path) -> str:
    """Hash the complete Go source tree so stale binaries cannot be repacked."""
    if not runtime_dir.is_dir():
        raise PackError(f"Runtime source directory missing: {runtime_dir}")
    digest = hashlib.sha256()
    for path in sorted(p for p in runtime_dir.rglob("*") if p.is_file() and not p.is_symlink()):
        relative = path.relative_to(runtime_dir).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(_sha256_file(path).encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def _validate_build_metadata(
    metadata: object,
    actual_binary_sha256: str,
    *,
    runtime_dir: Path | None,
    expected_revision: str | None,
    expected_goarch: str | None,
) -> dict:
    if not isinstance(metadata, dict) or metadata.get("schema_version") != 1:
        raise PackError("Unsupported or malformed embedded Runtime build metadata")

    revision = str(metadata.get("revision", "")).lower()
    source_sha256 = str(metadata.get("source_sha256", "")).lower()
    binary_sha256 = str(metadata.get("binary_sha256", "")).lower()
    if not _REVISION.fullmatch(revision):
        raise PackError("Embedded build metadata does not identify a Git source revision")
    if expected_revision is not None and revision != expected_revision.lower():
        raise PackError(
            "ClawRuntime binary was built from a different Git revision; rebuild before packaging"
        )
    if not _HEX_64.fullmatch(source_sha256):
        raise PackError("Embedded build metadata has an invalid source fingerprint")
    if not _HEX_64.fullmatch(binary_sha256):
        raise PackError("Embedded build metadata has an invalid binary SHA-256")
    if actual_binary_sha256 != binary_sha256:
        raise PackError("ClawRuntime binary SHA-256 does not match its embedded build metadata")
    if metadata.get("goos") != "android":
        raise PackError("ClawRuntime binary metadata does not target Android")
    if metadata.get("goarch") not in {"arm64", "arm", "amd64"}:
        raise PackError("ClawRuntime binary metadata has an invalid target architecture")
    if expected_goarch is not None and metadata.get("goarch") != expected_goarch:
        raise PackError(
            f"ClawRuntime binary targets {metadata.get('goarch')}, expected {expected_goarch}"
        )
    if str(metadata.get("cgo_enabled")) != "0":
        raise PackError("ClawRuntime binary metadata must have CGO disabled")
    if runtime_dir is not None:
        actual_source_sha256 = runtime_source_fingerprint(runtime_dir)
        if actual_source_sha256 != source_sha256:
            raise PackError(
                "Runtime sources changed since the binary was built; rebuild before packaging"
            )
    return metadata


def read_binary_build_metadata(
    binary_path: Path,
    *,
    runtime_dir: Path | None = None,
    expected_revision: str | None = None,
    expected_goarch: str | None = None,
) -> dict:
    """Read and authenticate the metadata trailer appended to a Runtime binary."""
    if not binary_path.is_file() or binary_path.is_symlink():
        raise PackError(f"ClawRuntime binary missing: {binary_path}")

    footer_bytes = _TRAILER_DIGEST_BYTES + _TRAILER_LENGTH_BYTES + len(BUILD_METADATA_MAGIC)
    try:
        with binary_path.open("rb") as stream:
            size = os.fstat(stream.fileno()).st_size
            if size <= footer_bytes:
                raise PackError("ClawRuntime binary is missing its embedded build metadata trailer")

            stream.seek(-len(BUILD_METADATA_MAGIC), os.SEEK_END)
            if stream.read(len(BUILD_METADATA_MAGIC)) != BUILD_METADATA_MAGIC:
                raise PackError("ClawRuntime binary has an invalid build metadata trailer magic")

            stream.seek(-len(BUILD_METADATA_MAGIC) - _TRAILER_LENGTH_BYTES, os.SEEK_END)
            metadata_length = int.from_bytes(stream.read(_TRAILER_LENGTH_BYTES), "big")
            if metadata_length <= 0 or metadata_length > _MAX_METADATA_BYTES:
                raise PackError("ClawRuntime binary has an invalid build metadata length")
            payload_end = size - footer_bytes
            payload_start = payload_end - metadata_length
            if payload_start < 0:
                raise PackError("ClawRuntime binary has a truncated build metadata trailer")

            stream.seek(payload_end)
            expected_digest = stream.read(_TRAILER_DIGEST_BYTES)
            stream.seek(payload_start)
            payload = stream.read(metadata_length)
            if len(payload) != metadata_length or len(expected_digest) != _TRAILER_DIGEST_BYTES:
                raise PackError("ClawRuntime binary has a truncated build metadata trailer")

            binary_digest = hashlib.sha256()
            trailer_digest = hashlib.sha256()
            stream.seek(0)
            remaining = payload_start
            while remaining:
                chunk = stream.read(min(1024 * 1024, remaining))
                if not chunk:
                    raise PackError("ClawRuntime binary changed while reading build metadata")
                binary_digest.update(chunk)
                trailer_digest.update(chunk)
                remaining -= len(chunk)
            trailer_digest.update(payload)
            if trailer_digest.digest() != expected_digest:
                raise PackError("ClawRuntime binary build metadata trailer hash mismatch")
    except OSError as exc:
        raise PackError(f"Cannot read embedded build metadata from {binary_path}: {exc}") from exc

    try:
        metadata = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise PackError(f"Cannot decode embedded Runtime build metadata: {exc}") from exc
    return _validate_build_metadata(
        metadata,
        binary_digest.hexdigest(),
        runtime_dir=runtime_dir,
        expected_revision=expected_revision,
        expected_goarch=expected_goarch,
    )


def verify_build_metadata(
    module_dir: Path,
    *,
    runtime_dir: Path | None = None,
    expected_revision: str | None = None,
    expected_goarch: str | None = None,
) -> dict:
    return read_binary_build_metadata(
        module_dir / "bin/clawdroid-runtime",
        runtime_dir=runtime_dir,
        expected_revision=expected_revision,
        expected_goarch=expected_goarch,
    )


def _atomic_write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def write_metadata_sidecar(binary_path: Path, metadata: dict | None = None) -> Path:
    """Write the optional human-readable copy; never use it as verification input."""
    if metadata is None:
        metadata = read_binary_build_metadata(binary_path)
    metadata_path = binary_path.with_name(binary_path.name + ".build.json")
    payload = (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode("utf-8")
    _atomic_write_bytes(metadata_path, payload)
    return metadata_path


def write_build_metadata(
    binary_path: Path,
    runtime_dir: Path,
    *,
    revision: str,
    goos: str,
    goarch: str,
    cgo_enabled: str = "0",
) -> Path:
    """Append provenance to a freshly built binary, verify it, then write a sidecar."""
    if not binary_path.is_file() or binary_path.is_symlink():
        raise PackError(f"ClawRuntime binary missing: {binary_path}")
    with binary_path.open("rb") as stream:
        if os.fstat(stream.fileno()).st_size >= len(BUILD_METADATA_MAGIC):
            stream.seek(-len(BUILD_METADATA_MAGIC), os.SEEK_END)
            if stream.read(len(BUILD_METADATA_MAGIC)) == BUILD_METADATA_MAGIC:
                raise PackError(
                    "ClawRuntime binary already has embedded build metadata; rebuild instead of rewriting provenance"
                )

    raw_binary = binary_path.read_bytes()
    metadata = {
        "schema_version": 1,
        "revision": revision.lower(),
        "source_sha256": runtime_source_fingerprint(runtime_dir),
        "binary_sha256": hashlib.sha256(raw_binary).hexdigest(),
        "goos": goos,
        "goarch": goarch,
        "cgo_enabled": cgo_enabled,
        "built_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    payload = json.dumps(metadata, sort_keys=True, separators=(",", ":")).encode("utf-8")
    digest = hashlib.sha256(raw_binary + payload).digest()
    trailer = (
        raw_binary
        + payload
        + digest
        + len(payload).to_bytes(_TRAILER_LENGTH_BYTES, "big")
        + BUILD_METADATA_MAGIC
    )

    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{binary_path.name}.", suffix=".tmp", dir=binary_path.parent
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(trailer)
        read_binary_build_metadata(
            temporary_path,
            runtime_dir=runtime_dir,
            expected_revision=revision,
            expected_goarch=goarch,
        )
        os.replace(temporary_path, binary_path)
    finally:
        temporary_path.unlink(missing_ok=True)
    return write_metadata_sidecar(binary_path, metadata)


def _validate_zip(archive: zipfile.ZipFile, expected_names: set[str]) -> None:
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise PackError("packed zip contains duplicate entries")
    missing = expected_names - set(names)
    if missing:
        raise PackError(f"packed zip missing required files: {', '.join(sorted(missing))}")
    bad = archive.testzip()
    if bad is not None:
        raise PackError(f"zip CRC failure: {bad}")
    total = sum(info.file_size for info in archive.infolist())
    if total > 0xFFFFFFFF:
        raise PackError("zip contents require Zip64; Magisk may reject")


def pack(stage_dir: Path, output_zip: Path) -> None:
    files = list_packable_files(stage_dir)
    expected_names = {path.relative_to(stage_dir).as_posix() for path in files}
    missing = set(REQUIRED_FILES) - expected_names
    if missing:
        raise PackError(f"required module files missing under {stage_dir}: {', '.join(sorted(missing))}")
    verify_build_metadata(stage_dir)

    output_zip.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{output_zip.name}.", suffix=".tmp", dir=output_zip.parent
    )
    os.close(fd)
    temporary_zip = Path(temporary_name)
    try:
        # allowZip64=False keeps Magisk/busybox unzip happy.
        with zipfile.ZipFile(
            temporary_zip,
            mode="w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=6,
            allowZip64=False,
        ) as archive:
            for path in files:
                arcname = path.relative_to(stage_dir).as_posix()
                data = path.read_bytes()
                # Normalize shell / prop text to LF for Magisk extractors.
                if path.suffix in {".sh", ".prop", ".rule"} or path.name in {
                    "update-binary",
                    "updater-script",
                    "customize.sh",
                }:
                    data = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
                info = zipfile.ZipInfo(filename=arcname)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3  # Unix
                info.external_attr = (unix_mode(path) & 0xFFFF) << 16
                archive.writestr(info, data)

        with zipfile.ZipFile(temporary_zip, "r") as archive:
            _validate_zip(archive, expected_names)
        os.replace(temporary_zip, output_zip)
    finally:
        temporary_zip.unlink(missing_ok=True)

    print(f"Created {output_zip} ({output_zip.stat().st_size} bytes, {len(files)} files)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--embed-build-metadata", action="store_true")
    parser.add_argument("--write-build-metadata", action="store_true")
    parser.add_argument("--verify-build", action="store_true")
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--module-dir", type=Path)
    parser.add_argument("--runtime-dir", type=Path)
    parser.add_argument("--revision")
    parser.add_argument("--goos", default="android")
    parser.add_argument("--goarch", default="arm64")
    parser.add_argument("--cgo-enabled", default="0")
    parser.add_argument("--expected-revision")
    parser.add_argument("--expected-goarch")
    args = parser.parse_args()

    try:
        if args.embed_build_metadata:
            if not (args.binary and args.runtime_dir and args.revision and args.goarch):
                parser.error("--embed-build-metadata requires --binary, --runtime-dir, --revision, and --goarch")
            metadata_path = write_build_metadata(
                args.binary.resolve(),
                args.runtime_dir.resolve(),
                revision=args.revision,
                goos=args.goos,
                goarch=args.goarch,
                cgo_enabled=args.cgo_enabled,
            )
            print(f"Embedded and wrote {metadata_path}")
        elif args.write_build_metadata:
            if not args.binary:
                parser.error("--write-build-metadata requires --binary")
            metadata_path = write_metadata_sidecar(args.binary.resolve())
            print(f"Wrote human-readable sidecar {metadata_path}")
        elif args.verify_build:
            if not args.module_dir:
                parser.error("--verify-build requires --module-dir")
            verify_build_metadata(
                args.module_dir.resolve(),
                runtime_dir=args.runtime_dir.resolve() if args.runtime_dir else None,
                expected_revision=args.expected_revision,
                expected_goarch=args.expected_goarch,
            )
            print("Embedded Runtime build metadata verified")
        else:
            if not (args.stage and args.output):
                parser.error("packing requires --stage and --output")
            stage_dir = args.stage.resolve()
            if args.runtime_dir or args.expected_revision or args.expected_goarch:
                verify_build_metadata(
                    stage_dir,
                    runtime_dir=args.runtime_dir.resolve() if args.runtime_dir else None,
                    expected_revision=args.expected_revision,
                    expected_goarch=args.expected_goarch,
                )
            pack(stage_dir, args.output.resolve())
    except (PackError, OSError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"error: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
