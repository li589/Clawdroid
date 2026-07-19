#!/usr/bin/env python3
import json
import re
import time
import urllib.request

BASE = "http://127.0.0.1:8765/mcp"
TOKEN = "p0-test-token-clawdroid-8765"
_id = 0


def rpc(method, params=None):
    global _id
    _id += 1
    req = urllib.request.Request(
        BASE,
        data=json.dumps({"jsonrpc": "2.0", "id": _id, "method": method, "params": params or {}}).encode(),
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def call(name, args):
    d = rpc("tools/call", {"name": name, "arguments": args})
    res = d.get("result") or {}
    text = "\n".join(c.get("text", "") for c in res.get("content") or [] if c.get("type") == "text")
    print(name, "isError=", res.get("isError"), "meta=", (res.get("_meta") or {}).get("error"), "|", text[:220].replace("\n", " "))
    return res, text


rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "p0", "version": "1"}})
call("assist_status", {})
call("assist_ping", {})
call("assist_list_tools", {})
call("assist_call_tool", {"name": "pc_echo", "arguments_json": json.dumps({"text": "p0-hello"})})
call("ftp_transfer", {"op": "get", "host": "example.com", "local_path": "/sdcard/not-sandbox", "remote_path": "/"})
_, text = call("download_start", {"url": "https://example.com", "threads": 1, "resume": False})
m = re.search(r"download_id=([\w-]+)", text or "")
dest = re.search(r"dest=(\S+)", text or "")
time.sleep(3)
if m:
    call("download_status", {"download_id": m.group(1)})
if dest:
    call("download_verify", {"path": dest.group(1)})
call("subscribe_events", {"operation": "start"})
call("subscribe_events", {"operation": "stop"})
call("shizuku_status", {})
