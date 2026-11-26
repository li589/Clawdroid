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


def named_constants(text: str, prefix: str) -> dict[str, str]:
    return {
        name: value
        for name, value in re.findall(
            rf'(?m)^\s*const val ({prefix}\w+)\s*=\s*"([^"]+)"', text
        )
    }


def go_capabilities(text: str) -> dict[str, str]:
    actions = {
        name: value
        for name, value in re.findall(r'(?m)^\s*(Action\w+)\s*=\s*"([^"]+)"', text)
    }
    capabilities = {
        name: value
        for name, value in re.findall(r'(?m)^\s*(Capability\w+)\s*=\s*"([^"]+)"', text)
    }
    return {
        actions[action]: capabilities[capability]
        for action, capability in re.findall(
            r"(?m)^\s*(Action\w+):\s+(Capability\w+),", text
        )
    }


def kt_capabilities(text: str) -> dict[str, str]:
    constants = named_constants(text, "")
    return {
        constants[action]: constants[capability]
        for action, capability in re.findall(
            r"(?m)^\s*([A-Z][A-Z0-9_]*)\s+to\s+(CAPABILITY_[A-Z0-9_]+)", text
        )
    }


def protocol_actions(text: str) -> set[str]:
    return set(re.findall(r"(?m)^### 8\.\d+ `([a-z0-9_]+)`", text))


def handler_actions(text: str, action_names: dict[str, str]) -> set[str]:
    return {
        action_names[name]
        for name in re.findall(r"(?m)^\s*case ipc\.(Action\w+):", text)
        if name in action_names
    }


def assert_equal(left, right, label: str) -> None:
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


def assert_mapping_equal(left: dict, right: dict, label: str) -> None:
    if left == right:
        print(f"OK: {label} ({len(left)} entries)")
        return
    print(f"MISMATCH: {label}", file=sys.stderr)
    for key in sorted(set(left) | set(right)):
        if left.get(key) != right.get(key):
            print(f"  {key}: Go={left.get(key)!r} Kotlin={right.get(key)!r}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    root = repo_root()
    go_actions_path = root / "ClawRuntime/runtime/internal/ipc/actions.go"
    go_errors_path = root / "ClawRuntime/runtime/internal/ipc/errors.go"
    kt_catalog = root / "ClawApp/app/src/main/java/com/clawdroid/app/runtime/RuntimeActionCatalog.kt"
    kt_errors = root / "ClawApp/app/src/main/java/com/clawdroid/app/runtime/RuntimeErrorCodes.kt"
    handlers = root / "ClawRuntime/runtime/internal/server/handlers.go"
    server = root / "ClawRuntime/runtime/internal/server/server.go"
    protocol = root / "Docs/protocol.md"

    go_action_text = go_actions_path.read_text(encoding="utf-8")
    kt_catalog_text = kt_catalog.read_text(encoding="utf-8")
    actions = go_actions(go_action_text)
    action_names = {
        name: value
        for name, value in re.findall(r'(?m)^\s*(Action\w+)\s*=\s*"([^"]+)"', go_action_text)
    }

    assert_equal(actions, kt_actions(kt_catalog_text), "IPC actions (actions.go vs RuntimeActionCatalog)")
    assert_equal(
        go_error_codes(go_errors_path.read_text(encoding="utf-8")),
        kt_error_codes(kt_errors.read_text(encoding="utf-8")),
        "IPC error codes (errors.go vs RuntimeErrorCodes)",
    )
    assert_mapping_equal(
        go_capabilities(go_action_text),
        kt_capabilities(kt_catalog_text),
        "IPC capability map (Go vs Kotlin)",
    )
    assert_equal(
        actions - {"subscribe_events"},
        handler_actions(handlers.read_text(encoding="utf-8"), action_names),
        "IPC handler dispatch",
    )
    if "ipc.ActionSubscribeEvents" not in server.read_text(encoding="utf-8"):
        raise SystemExit("MISMATCH: subscribe_events is not routed by server loop")
    assert_equal(actions, protocol_actions(protocol.read_text(encoding="utf-8")), "protocol.md action definitions")
    print("Runtime catalog check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
