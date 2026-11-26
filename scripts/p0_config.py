#!/usr/bin/env python3
"""Shared configuration for local P0 Assist MCP smoke scripts."""

from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MCP_URL = os.environ.get("CLAWDROID_MCP_URL", "http://127.0.0.1:8765/mcp")
MCP_TOKEN = os.environ.get("CLAWDROID_MCP_TOKEN", "p0-test-token-clawdroid-8765")
P0_OUT = Path(os.environ.get("CLAWDROID_P0_OUT", REPO_ROOT / "debug-artifacts" / "p0"))
ASSIST_PORT = int(os.environ.get("CLAWDROID_ASSIST_PORT", "8766"))
