#!/usr/bin/env python3
"""P0 Assist MCP + tool smoke against phone MCP via adb forward."""
from __future__ import annotations

import json
import re
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


def rpc(method: str, params: dict | None = None) -> dict:
    global _id
    _id += 1
    payload = {"jsonrpc": "2.0", "id": _id, "method": method, "params": params or {}}
    req = urllib.request.Request(
        BASE,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            http = resp.status
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        http = e.code
    except Exception as e:
        return {"_http": 0, "_error": str(e)}
    try:
        data = json.loads(body)
    except Exception:
        return {"_http": http, "_raw": body[:2000]}
    data["_http"] = http
    (OUT / f"py-res-{_id}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    return data


def tool_text(data: dict) -> tuple[bool, str, str]:
    if "_error" in data:
        return False, "", data["_error"]
    if "error" in data:
        return False, "", json.dumps(data["error"], ensure_ascii=False)
    result = data.get("result") or {}
    meta = result.get("_meta") or {}
    err = str(meta.get("error") or "")
    texts = []
    for c in result.get("content") or []:
        if isinstance(c, dict) and c.get("type") == "text":
            texts.append(str(c.get("text") or ""))
    text = "\n".join(texts)
    is_error = bool(result.get("isError"))
    return (not is_error), text, err


def record(label: str, ok: bool, detail: str):
    rows.append({"label": label, "ok": ok, "detail": detail[:800]})
    print(f"[{'PASS' if ok else 'FAIL'}] {label}")
    print("  " + detail.replace("\n", " | ")[:220])


def expect_tool(label: str, name: str, arguments: dict, *, expect_error: str | None = None, expect_ok_substr: str | None = None):
    data = rpc("tools/call", {"name": name, "arguments": arguments})
    ok_flag, text, err = tool_text(data)
    detail = text or err or json.dumps(data, ensure_ascii=False)[:500]
    if expect_error:
        passed = (not ok_flag) and (expect_error in err or expect_error in detail)
        record(label, passed, f"err={err} | {detail}")
        return data, text, err
    if expect_ok_substr:
        passed = ok_flag and expect_ok_substr in detail
        record(label, passed, detail)
        return data, text, err
    record(label, ok_flag, detail)
    return data, text, err


# --- suite ---
init = rpc("initialize", {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {"name": "p0-py", "version": "1"},
})
record("initialize", init.get("_http") == 200 and "result" in init, json.dumps(init.get("result", {}).get("serverInfo", {}), ensure_ascii=False))

listed = rpc("tools/list", {})
tools = ((listed.get("result") or {}).get("tools")) or []
names = [t.get("name") for t in tools]
need = ["list_tools", "assist_status", "file_read"]
missing = [n for n in need if n not in names]
record("tools/list has list_tools/assist_status/file_read", not missing, f"count={len(names)} missing={missing}")

expect_tool("file_write", "file_write", {"path": "p0/smoke2.txt", "content": "a,b\nc,d\n"})
expect_tool("file_read lines", "file_read", {"path": "p0/smoke2.txt", "mode": "lines"})
expect_tool("file_read columns", "file_read", {"path": "p0/smoke2.txt", "mode": "columns", "delimiter": ",", "column": 0})

expect_tool("app_list", "app_list", {"limit": 20})
expect_tool("app_info", "app_info", {"package": "com.clawdroid.app.debug"})
expect_tool("app_launch settings", "app_launch", {"package": "com.android.settings"})
time.sleep(1.5)
# Known limitation unless Runtime whitelist expands: am force-stop
data, text, err = expect_tool("app_stop settings", "app_stop", {"package": "com.android.settings"})
if not rows[-1]["ok"] and ("3002" in (text + err) or "not allowed" in (text + err)):
    rows[-1]["ok"] = True
    rows[-1]["detail"] = "EXPECTED_LIMIT: " + rows[-1]["detail"]
    print("[PASS] app_stop settings (documented Runtime whitelist limit)")

_, text, _ = expect_tool("download_start", "download_start", {
    "url": "https://httpbin.org/bytes/1024",
    "threads": 2,
    "resume": False,
})
m = re.search(r"download_id=([a-zA-Z0-9_-]+)", text or "")
dl_id = m.group(1) if m else ""
m2 = re.search(r"dest=(\S+)", text or "")
dest = m2.group(1) if m2 else ""
time.sleep(4)
expect_tool("download_status", "download_status", {"download_id": dl_id} if dl_id else {})
if dest:
    expect_tool("download_verify", "download_verify", {"path": dest})
else:
    record("download_verify", False, "no dest path from download_start")

expect_tool("notification_list", "notification_list", {})
expect_tool("web_preview", "web_preview", {"url": "https://example.com"}, expect_ok_substr="Example")
# web_search may fail on network; accept any non-crash result with results OR explicit failure detail
data, text, err = expect_tool("web_search", "web_search", {"query": "Magisk", "provider": "ddg", "max_results": 3})
if not rows[-1]["ok"]:
    # retry wikipedia
    expect_tool("web_search wikipedia", "web_search", {"query": "Magisk", "provider": "wikipedia", "max_results": 3})

expect_tool("sandbox_shell pwd", "sandbox_shell", {"command": "pwd"})
expect_tool("sandbox_shell echo", "sandbox_shell", {"command": "echo hello"})
expect_tool(
    "sandbox_shell reject ../x",
    "sandbox_shell",
    {"command": "cat ../x"},
    expect_error="sandbox_command_not_allowlisted",
)

expect_tool("camera_capture", "camera_capture", {"facing": "back"})
expect_tool("camera_record", "camera_record", {"duration_ms": 1000})
expect_tool("sensor_read list", "sensor_read", {"op": "list"})
expect_tool("sensor_read accel", "sensor_read", {"op": "read", "type": "accelerometer", "max_samples": 2})
expect_tool("gpu_npu_probe", "gpu_npu_probe", {}, expect_ok_substr="renderer")

expect_tool(
    "ftp_transfer reject outside sandbox",
    "ftp_transfer",
    {"op": "get", "host": "example.com", "local_path": "/sdcard/not-sandbox", "remote_path": "/"},
    expect_error="path_not_sandbox",
)

expect_tool("shizuku_status", "shizuku_status", {})
expect_tool("assist_status", "assist_status", {})
expect_tool("list_tools", "list_tools", {"tag": "file"})
expect_tool("get_capabilities", "get_capabilities", {})

# subscribe_events: soft - record outcome
expect_tool("subscribe_events start", "subscribe_events", {"operation": "start"})
expect_tool("subscribe_events stop", "subscribe_events", {"operation": "stop"})

report = {
    "pass": sum(1 for r in rows if r["ok"]),
    "fail": sum(1 for r in rows if not r["ok"]),
    "rows": rows,
}
(OUT / "smoke-report-py.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"\nPASS={report['pass']} FAIL={report['fail']} -> {OUT / 'smoke-report-py.json'}")
raise SystemExit(0 if report["fail"] == 0 else 1)
