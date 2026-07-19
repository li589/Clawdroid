#!/usr/bin/env python3
"""Pack a Magisk-compatible module zip (no Zip64, Unix attrs, LF scripts)."""

from __future__ import annotations

import argparse
import os
import stat
import zipfile
from pathlib import Path


SKIP_NAMES = {
    "runtime.generated.yaml",
    "runtime.yaml.example",
}


def should_skip(path: Path, root: Path) -> bool:
    rel = path.relative_to(root).as_posix()
    name = path.name
    if name in SKIP_NAMES:
        return True
    if name.startswith("."):
        return True
    # Never pack Windows junk / empty placeholders
    if rel.startswith("__MACOSX/") or rel.endswith("/.DS_Store"):
        return True
    return False


def unix_mode(path: Path) -> int:
    if path.suffix == ".sh" or path.name in {"update-binary", "customize.sh", "verify.sh"}:
        return 0o755
    if path.name == "clawdroid-runtime" or path.parent.name == "bin":
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


def pack(stage_dir: Path, output_zip: Path) -> None:
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    if output_zip.exists():
        output_zip.unlink()

    files = list_packable_files(stage_dir)
    if not any(p.name == "module.prop" for p in files):
        raise SystemExit(f"module.prop missing under {stage_dir}")
    meta_binary = stage_dir / "META-INF/com/google/android/update-binary"
    meta_script = stage_dir / "META-INF/com/google/android/updater-script"
    # Require real files (not symlinks) for the critical Magisk installer entrypoints.
    if not (meta_binary.is_file() and not meta_binary.is_symlink()) or \
            not (meta_script.is_file() and not meta_script.is_symlink()):
        raise SystemExit("META-INF Magisk installer files missing (update-binary / updater-script)")

    # allowZip64=False keeps Magisk/busybox unzip happy.
    with zipfile.ZipFile(
        output_zip,
        mode="w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=6,
        allowZip64=False,
    ) as zf:
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
            zf.writestr(info, data)

    # Validate structure quickly.
    with zipfile.ZipFile(output_zip, "r") as zf:
        names = zf.namelist()
        if "module.prop" not in names:
            raise SystemExit("packed zip missing root module.prop")
        if "META-INF/com/google/android/update-binary" not in names:
            raise SystemExit("packed zip missing META-INF update-binary")
        bad = zf.testzip()
        if bad is not None:
            raise SystemExit(f"zip CRC failure: {bad}")
        total = sum(i.file_size for i in zf.infolist())
        if total > 0xFFFFFFFF:
            raise SystemExit("zip contents require Zip64; Magisk may reject")

    print(f"Created {output_zip} ({output_zip.stat().st_size} bytes, {len(files)} files)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    pack(args.stage.resolve(), args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
