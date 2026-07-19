#!/usr/bin/env python3
"""Verify App RuntimeActionCatalog / RuntimeErrorCodes stay aligned with Go ipc SSOT."""

from __future__ import annotations

import re
import sys
from pathlib import Path


def repo_root() -> Path:
    here = Path(__file__).resolve().parent
    if (here.parent / "ClawRuntime").is_dir():
        return here.parent
    return here


def go_actions(text: str) -> set[str]:
    return set(re.findall(r'(?m)^\s*Action\w+\s*=\s*"([^"]+)"', text))


def go_error_codes(text: str) -> set[int]:
    return {int(x) for x in re.findall(r"(?m)^\s*Code\w+\s*=\s*(\d+)", text)}


def kt_actions(text: str) -> set[str]:
    return set(
        re.findall(
            r'(?m)^\s*const val (?!CAPABILITY_)[A-Z0-9_]+\s*=\s*"([a-z0-9_]+)"',
            text,
        )
    )


def kt_error_codes(text: str) -> set[int]:
    return {int(x) for x in re.findall(r"(?m)^\s*const val [A-Z0-9_]+\s*=\s*(\d+)", text)}


def assert_equal(left: set, right: set, label: str) -> None:
    only_left = sorted(left - right, key=str)
    only_right = sorted(right - left, key=str)
    if only_left or only_right:
        print(f"MISMATCH: {label}", file=sys.stderr)
        if only_left:
            print(f"  only in Go: {', '.join(map(str, only_left))}", file=sys.stderr)
        if only_right:
            print(f"  only in Kotlin: {', '.join(map(str, only_right))}", file=sys.stderr)
        raise SystemExit(1)
    print(f"OK: {label} ({len(left)} entries)")


def main() -> int:
    root = repo_root()
    go_actions_path = root / "ClawRuntime/runtime/internal/ipc/actions.go"
    go_errors_path = root / "ClawRuntime/runtime/internal/ipc/errors.go"
    kt_catalog = root / "ClawApp/app/src/main/java/com/clawdroid/app/runtime/RuntimeActionCatalog.kt"
    kt_errors = root / "ClawApp/app/src/main/java/com/clawdroid/app/runtime/RuntimeErrorCodes.kt"

    assert_equal(
        go_actions(go_actions_path.read_text(encoding="utf-8")),
        kt_actions(kt_catalog.read_text(encoding="utf-8")),
        "IPC actions (actions.go vs RuntimeActionCatalog)",
    )
    assert_equal(
        go_error_codes(go_errors_path.read_text(encoding="utf-8")),
        kt_error_codes(kt_errors.read_text(encoding="utf-8")),
        "IPC error codes (errors.go vs RuntimeErrorCodes)",
    )
    print("Runtime catalog check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
