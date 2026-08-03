#!/usr/bin/env python3
"""P0 reliability + gap-fill smoke via phone MCP (adb forward 8765)."""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8765/mcp"
TOKEN = "p0-test-token-clawdroid-8765"
OUT = Path(r"d:\temp_desktop\Proj\Clawdroid\debug-artifacts\p0")
OUT.mkdir(parents=True, exist_ok=True)
_id = 0
rows: list[dict] = []


def rpc(method: str, params: dict | None = None, timeout: float = 90) -> dict:
    global _id
    _id += 1
    req = urllib.request.Request(
        BASE,
        data=json.dumps({"jsonrpc": "2.0", "id": _id, "method": method, "params": params or {}}).encode(),
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def call(label: str, name: str, args: dict, *, timeout: float = 90) -> tuple[bool, str]:
    try:
        d = rpc("tools/call", {"name": name, "arguments": args}, timeout=timeout)
    except Exception as e:
        rows.append({"label": label, "ok": False, "detail": str(e)})
        print(f"[FAIL] {label}: {e}")
        return False, str(e)
    res = d.get("result") or {}
    text = "\n".join(c.get("text", "") for c in res.get("content") or [] if c.get("type") == "text")
    ok = not res.get("isError")
    detail = text[:500]
    rows.append({"label": label, "ok": ok, "detail": detail})
    print(f"[{'PASS' if ok else 'FAIL'}] {label}")
    print(" ", detail.replace("\n", " | ")[:240])
    return ok, text


def wait_mcp(max_sec: int = 30) -> bool:
    deadline = time.time() + max_sec
    while time.time() < deadline:
        try:
            rpc("initialize", {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "s8", "version": "1"},
            }, timeout=5)
            return True
        except Exception:
            time.sleep(1)
    return False


if not wait_mcp():
    print("MCP not reachable on 8765 — open App settings and enable phone MCP server")
    raise SystemExit(2)

call("8.1 get_capabilities Ready", "get_capabilities", {})
call("8.1 get_runtime_status", "get_runtime_status", {})
call("8.4 shell sleep5 timeout8s", "execute_shell_limited", {"command": "sleep 5", "timeout_ms": 8000}, timeout=30)
call("file_list /sdcard", "file_list", {"path": "/sdcard", "limit": 8})
call("camera_capture", "camera_capture", {"facing": "back"}, timeout=60)
call("camera_record", "camera_record", {"duration_ms": 1000}, timeout=60)
call("download example.com", "download_start", {"url": "https://example.com", "threads": 1, "resume": False})
time.sleep(2)
# soft: task tools
ok, text = call("task_submit ping-ish", "task_submit", {
    "steps": json.dumps([{"action": "ping", "params": {}}]),
})
# parse task id if present
import re
m = re.search(r"task_id[=:]?\s*([A-Za-z0-9_-]+)", text or "")
if not m:
    m = re.search(r'"id"\s*:\s*"([^"]+)"', text or "")
if m:
    tid = m.group(1)
    call("task_wait", "task_wait", {"task_id": tid, "timeout_ms": 15000}, timeout=30)
else:
    rows.append({"label": "task_wait", "ok": False, "detail": "no task_id from submit: " + (text or "")[:200]})
    print("[FAIL] task_wait: no task_id")

report = {"pass": sum(1 for r in rows if r["ok"]), "fail": sum(1 for r in rows if not r["ok"]), "rows": rows}
(OUT / "reliability-s8-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"\nPASS={report['pass']} FAIL={report['fail']} -> {OUT / 'reliability-s8-report.json'}")
raise SystemExit(0 if report["fail"] == 0 else 1)
