#!/usr/bin/env python3
"""Minimal PC-side MCP stub for phone assist_* reverse tests."""
from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT = "127.0.0.1", 8766


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[assist-stub]", fmt % args)

    def _read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        return json.loads(raw.decode("utf-8"))

    def _write_json(self, code: int, obj: dict):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path not in ("/mcp", "/"):
            self._write_json(404, {"error": "not_found"})
            return
        req = self._read_json()
        method = req.get("method")
        rid = req.get("id")
        if method == "initialize":
            self._write_json(200, {
                "jsonrpc": "2.0",
                "id": rid,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "p0-pc-assist", "version": "1.0.0"},
                },
            })
            return
        if method in ("notifications/initialized", "initialized"):
            self.send_response(202)
            self.end_headers()
            return
        if method == "tools/list":
            self._write_json(200, {
                "jsonrpc": "2.0",
                "id": rid,
                "result": {
                    "tools": [{
                        "name": "pc_echo",
                        "description": "Echo arguments for P0",
                        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
                    }]
                },
            })
            return
        if method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            text = f"pc_echo ok name={name} args={json.dumps(args, ensure_ascii=False)}"
            self._write_json(200, {
                "jsonrpc": "2.0",
                "id": rid,
                "result": {
                    "content": [{"type": "text", "text": text}],
                    "isError": False,
                },
            })
            return
        if method == "ping":
            self._write_json(200, {"jsonrpc": "2.0", "id": rid, "result": {}})
            return
        self._write_json(200, {
            "jsonrpc": "2.0",
            "id": rid,
            "error": {"code": -32601, "message": f"Method not found: {method}"},
        })


if __name__ == "__main__":
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"assist stub on http://{HOST}:{PORT}/mcp")
    httpd.serve_forever()
