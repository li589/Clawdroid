#!/usr/bin/env python3
from __future__ import annotations

import importlib
import os
import unittest
from pathlib import Path
from unittest import mock

import p0_config


class P0ConfigTest(unittest.TestCase):
    def test_repo_relative_defaults(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True):
            importlib.reload(p0_config)
            self.assertTrue((p0_config.REPO_ROOT / "ClawRuntime").is_dir())
            self.assertEqual("http://127.0.0.1:8765/mcp", p0_config.MCP_URL)
            self.assertEqual("debug-artifacts", p0_config.P0_OUT.parent.name)
        importlib.reload(p0_config)

    def test_environment_overrides(self) -> None:
        with mock.patch.dict(
            os.environ,
            {
                "CLAWDROID_MCP_URL": "http://127.0.0.1:9999/mcp",
                "CLAWDROID_MCP_TOKEN": "test-token",
                "CLAWDROID_P0_OUT": "C:/tmp/p0",
                "CLAWDROID_ASSIST_PORT": "9998",
            },
            clear=True,
        ):
            importlib.reload(p0_config)
            self.assertEqual("http://127.0.0.1:9999/mcp", p0_config.MCP_URL)
            self.assertEqual("test-token", p0_config.MCP_TOKEN)
            self.assertEqual(Path("C:/tmp/p0"), p0_config.P0_OUT)
            self.assertEqual(9998, p0_config.ASSIST_PORT)
        importlib.reload(p0_config)


if __name__ == "__main__":
    unittest.main()
