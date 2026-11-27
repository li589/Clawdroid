from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
RUNTIME_ROOT = SCRIPTS_DIR.parent
REPO_ROOT = RUNTIME_ROOT.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import pack_magisk_zip as packer


SECRET_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
SECRET_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
SECRET_C = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
SIGNATURE_A = "sha256:" + "a" * 64
PWSH = shutil.which("pwsh")
WINDOWS_POWERSHELL = shutil.which("powershell") or shutil.which("powershell.exe")


@unittest.skipUnless(PWSH, "PowerShell 7 is required for build-script integration tests")
class BuildScriptsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.runtime_root = self.root / "ClawRuntime"
        self.scripts = self.runtime_root / "scripts"
        self.scripts.mkdir(parents=True)
        for name in (
            "build-runtime.ps1",
            "build-magisk.ps1",
            "sync-shared-secret.ps1",
            "pack_magisk_zip.py",
        ):
            shutil.copy2(SCRIPTS_DIR / name, self.scripts / name)

        self.runtime_dir = self.runtime_root / "runtime"
        (self.runtime_dir / "cmd/runtime").mkdir(parents=True)
        (self.runtime_dir / "cmd/runtime/main.go").write_text("package main\n", encoding="utf-8")
        (self.runtime_dir / "go.mod").write_text("module fixture\n", encoding="utf-8")

        self.module_dir = self.runtime_root / "magisk"
        shutil.copytree(
            REPO_ROOT / "ClawRuntime/magisk",
            self.module_dir,
            ignore=shutil.ignore_patterns("bin", "runtime.generated.yaml"),
        )
        self.binary = self.module_dir / "bin/clawdroid-runtime"
        self.binary.parent.mkdir(parents=True, exist_ok=True)
        template = self.module_dir / "config/runtime.yaml"
        template.write_text("# 运行时配置\n" + template.read_text(encoding="utf-8"), encoding="utf-8")
        self.generated_config = self.module_dir / "config/runtime.generated.yaml"
        self.repo_properties = self.root / "local.properties"
        self.app_properties = self.runtime_root.parent / "ClawApp/local.properties"
        self.app_properties.parent.mkdir()
        self.write_properties(self.repo_properties, f"# 仓库配置\nclawdroid.runtime.sharedSecret={SECRET_A}\n")
        self.write_properties(self.app_properties, f"# 应用配置\nclawdroid.runtime.sharedSecret={SECRET_A}\n")

        self.fake_bin = self.root / "fake-bin"
        self.fake_bin.mkdir()
        self.write_fake_go()
        self.write_fake_git()

    @staticmethod
    def write_properties(path: Path, content: str) -> None:
        path.write_text(content, encoding="utf-8", newline="")

    def write_fake_go(self) -> None:
        fake = self.fake_bin / "fake_go.py"
        fake.write_text(
            "import json, os, sys\n"
            "from pathlib import Path\n"
            "args = sys.argv[1:]\n"
            "output = Path(args[args.index('-o') + 1])\n"
            "with (Path(__file__).parent / 'go-invocation.jsonl').open('a', encoding='utf-8') as f:\n"
            "    f.write(json.dumps(args) + '\\n')\n"
            "if os.environ.get('FAKE_GO_FAIL') == '1':\n"
            "    output.write_bytes(b'partial-failed-build')\n"
            "    sys.exit(23)\n"
            "output.write_bytes(b'rebuilt-runtime')\n",
            encoding="utf-8",
        )
        (self.fake_bin / "go.cmd").write_text(
            f'@echo off\r\n"{sys.executable}" "%~dp0fake_go.py" %*\r\nexit /b %ERRORLEVEL%\r\n',
            encoding="ascii",
        )

    def write_fake_git(self) -> None:
        fake = self.fake_bin / "fake_git.py"
        fake.write_text(
            "import os, sys\n"
            "if 'rev-parse' not in sys.argv:\n"
            "    sys.exit(2)\n"
            "print(os.environ.get('FAKE_GIT_REVISION', 'a' * 40))\n",
            encoding="utf-8",
        )
        (self.fake_bin / "git.cmd").write_text(
            f'@echo off\r\n"{sys.executable}" "%~dp0fake_git.py" %*\r\nexit /b %ERRORLEVEL%\r\n',
            encoding="ascii",
        )

    def environment(self, **overrides: str) -> dict[str, str]:
        env = os.environ.copy()
        for key in (
            "CLAWDROID_RUNTIME_SHARED_SECRET",
            "CLAWDROID_RUNTIME_ALLOWED_SIGNATURES",
            "ORG_GRADLE_PROJECT_clawdroid.clawRuntimeSharedSecret",
            "ORG_GRADLE_PROJECT_clawdroid_clawRuntimeSharedSecret",
            "FAKE_GO_FAIL",
        ):
            env.pop(key, None)
        env["PATH"] = str(self.fake_bin) + os.pathsep + env.get("PATH", "")
        env["GRADLE_USER_HOME"] = str(self.root / "gradle-user-home")
        env["FAKE_GIT_REVISION"] = "a" * 40
        env.update(overrides)
        return env

    def run_script(
        self,
        name: str,
        *args: str,
        env: dict[str, str] | None = None,
        shell: str | None = None,
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            [shell or PWSH, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(self.scripts / name), *args],
            cwd=self.runtime_root,
            env=env or self.environment(),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )

    def assert_failed(self, result: subprocess.CompletedProcess) -> None:
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)

    def make_current_binary(self) -> None:
        self.binary.write_bytes(b"current-runtime")
        packer.write_build_metadata(
            self.binary,
            self.runtime_dir,
            revision="a" * 40,
            goos="android",
            goarch="arm64",
        )
        self.generated_config.write_text(
            f'auth:\n  shared_secret: "{SECRET_A}"\n  allowed_signatures: ""\n',
            encoding="utf-8",
        )

    def test_failed_go_build_is_nonzero_and_preserves_old_binary(self) -> None:
        self.binary.write_bytes(b"previous-good-runtime")

        result = self.run_script("build-runtime.ps1", env=self.environment(FAKE_GO_FAIL="1"))

        self.assert_failed(result)
        self.assertEqual(self.binary.read_bytes(), b"previous-good-runtime")
        self.assertNotIn("Built Android ClawRuntime", result.stdout)
        self.assertEqual(list(self.binary.parent.glob("*.tmp")), [])

    def test_metadata_failure_preserves_previous_binary_and_sidecar(self) -> None:
        self.binary.write_bytes(b"previous-good-runtime")
        sidecar = self.binary.with_name(self.binary.name + ".build.json")
        sidecar.write_bytes(b"previous-good-sidecar")
        fake_python = self.fake_bin / "fake_python.py"
        fake_python.write_text(
            "import os, subprocess, sys\n"
            "from pathlib import Path\n"
            "args = sys.argv[1:]\n"
            "if args and Path(args[0]).name == 'pack_magisk_zip.py':\n"
            "    args = args[1:]\n"
            "if os.environ.get('FAKE_METADATA_FAIL') == '1' and ('--embed-build-metadata' in args or '--write-build-metadata' in args):\n"
            "    raise SystemExit(24)\n"
            "raise SystemExit(subprocess.call([sys.executable, os.environ['FAKE_REAL_PACKER'], *args]))\n",
            encoding="utf-8",
        )
        (self.fake_bin / "python.cmd").write_text(
            f'@echo off\r\n"{sys.executable}" "%~dp0fake_python.py" %*\r\nexit /b %ERRORLEVEL%\r\n',
            encoding="ascii",
        )

        result = self.run_script(
            "build-runtime.ps1",
            env=self.environment(
                FAKE_METADATA_FAIL="1",
                FAKE_REAL_PACKER=str(self.scripts / "pack_magisk_zip.py"),
            ),
        )

        self.assert_failed(result)
        self.assertEqual(self.binary.read_bytes(), b"previous-good-runtime")
        self.assertEqual(sidecar.read_bytes(), b"previous-good-sidecar")
        self.assertNotIn("Built Android ClawRuntime", result.stdout)
        self.assertEqual(list(self.binary.parent.glob("*.tmp")), [])

    def test_successful_build_uses_temporary_output_and_writes_metadata(self) -> None:
        self.binary.write_bytes(b"previous-good-runtime")

        result = self.run_script("build-runtime.ps1")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.binary.read_bytes().endswith(packer.BUILD_METADATA_MAGIC))
        invocations = [json.loads(line) for line in (self.fake_bin / "go-invocation.jsonl").read_text().splitlines()]
        temporary_output = Path(invocations[0][invocations[0].index("-o") + 1])
        if not temporary_output.is_absolute():
            temporary_output = self.runtime_dir / temporary_output
        self.assertEqual(temporary_output.parent.resolve(), self.binary.parent.resolve())
        self.assertNotEqual(temporary_output.resolve(), self.binary.resolve())
        metadata = packer.read_binary_build_metadata(
            self.binary,
            runtime_dir=self.runtime_dir,
            expected_revision="a" * 40,
            expected_goarch="arm64",
        )
        self.assertEqual(metadata["revision"], "a" * 40)
        self.assertEqual(metadata["binary_sha256"], hashlib.sha256(b"rebuilt-runtime").hexdigest())
        self.assertEqual(metadata["goos"], "android")
        self.assertEqual(metadata["goarch"], "arm64")
        self.assertEqual(metadata["cgo_enabled"], "0")
        sidecar = json.loads((self.binary.parent / "clawdroid-runtime.build.json").read_text())
        self.assertEqual(sidecar["binary_sha256"], metadata["binary_sha256"])

    @unittest.skipUnless(WINDOWS_POWERSHELL, "Windows PowerShell 5.1 is not available")
    def test_windows_powershell_build_replaces_existing_binary(self) -> None:
        self.binary.write_bytes(b"previous-good-runtime")

        result = self.run_script("build-runtime.ps1", shell=WINDOWS_POWERSHELL)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.binary.read_bytes().endswith(packer.BUILD_METADATA_MAGIC))
        self.assertEqual(list(self.binary.parent.glob("*.tmp")), [])

    def test_build_restores_go_environment(self) -> None:
        command = (
            "$env:GOOS='plan9'; $env:GOARCH='386'; $env:GOARM='5'; $env:CGO_ENABLED='1'; "
            f"& '{self.scripts / 'build-runtime.ps1'}'; "
            "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; "
            "@{GOOS=$env:GOOS; GOARCH=$env:GOARCH; GOARM=$env:GOARM; CGO_ENABLED=$env:CGO_ENABLED} | ConvertTo-Json -Compress"
        )
        result = subprocess.run(
            [PWSH, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command],
            cwd=self.runtime_root,
            env=self.environment(),
            capture_output=True,
            text=True,
            timeout=120,
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        restored = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(restored, {"GOOS": "plan9", "GOARCH": "386", "GOARM": "5", "CGO_ENABLED": "1"})

    def test_packaging_rejects_missing_build_metadata_and_keeps_old_zip(self) -> None:
        self.binary.write_bytes(b"unverifiable-runtime")
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        output.parent.mkdir()
        output.write_bytes(b"previous-good-zip")

        result = self.run_script("build-magisk.ps1")

        self.assert_failed(result)
        self.assertEqual(output.read_bytes(), b"previous-good-zip")

    def test_packaging_rejects_empty_allowed_signatures_by_default(self) -> None:
        self.make_current_binary()
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        output.parent.mkdir()
        output.write_bytes(b"previous-good-zip")

        result = self.run_script("build-magisk.ps1")

        self.assert_failed(result)
        self.assertIn("allowed signatures", (result.stdout + result.stderr).lower())
        self.assertEqual(output.read_bytes(), b"previous-good-zip")

    def test_packaging_allows_empty_signatures_with_explicit_dev_switch(self) -> None:
        self.make_current_binary()

        result = self.run_script("build-magisk.ps1", "-AllowEmptyAllowedSignatures")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        with zipfile.ZipFile(output) as archive:
            self.assertIn('allowed_signatures: ""', archive.read("config/runtime.yaml").decode("utf-8"))

    def test_packaging_accepts_nonempty_signatures_without_dev_switch(self) -> None:
        self.make_current_binary()

        result = self.run_script(
            "build-magisk.ps1",
            env=self.environment(CLAWDROID_RUNTIME_ALLOWED_SIGNATURES=SIGNATURE_A),
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        with zipfile.ZipFile(output) as archive:
            self.assertIn(SIGNATURE_A, archive.read("config/runtime.yaml").decode("utf-8"))

    def test_packaging_rejects_binary_built_from_stale_revision(self) -> None:
        self.make_current_binary()
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        output.parent.mkdir()
        output.write_bytes(b"previous-good-zip")

        result = self.run_script("build-magisk.ps1", env=self.environment(FAKE_GIT_REVISION="b" * 40))

        self.assert_failed(result)
        self.assertEqual(output.read_bytes(), b"previous-good-zip")

    def test_packaging_rejects_sources_changed_after_build(self) -> None:
        self.make_current_binary()
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        output.parent.mkdir()
        output.write_bytes(b"previous-good-zip")
        (self.runtime_dir / "cmd/runtime/main.go").write_text("package main\n// changed\n", encoding="utf-8")

        result = self.run_script("build-magisk.ps1")

        self.assert_failed(result)
        self.assertEqual(output.read_bytes(), b"previous-good-zip")

    def test_packaging_rechecks_sources_after_staging(self) -> None:
        self.make_current_binary()
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        output.parent.mkdir()
        output.write_bytes(b"previous-good-zip")
        source_path = self.runtime_dir / "cmd/runtime/main.go"
        fake_python = self.fake_bin / "fake_python.py"
        fake_python.write_text(
            "import os, subprocess, sys\n"
            "from pathlib import Path\n"
            "args = sys.argv[1:]\n"
            "if args and Path(args[0]).name == 'pack_magisk_zip.py':\n"
            "    args = args[1:]\n"
            "result = subprocess.call([sys.executable, os.environ['FAKE_REAL_PACKER'], *args])\n"
            "if result == 0 and '--verify-build' in args and os.environ.get('FAKE_MUTATE_SOURCE_AFTER_VERIFY'):\n"
            "    Path(os.environ['FAKE_MUTATE_SOURCE_AFTER_VERIFY']).write_text('package main\\n// changed after preflight\\n', encoding='utf-8')\n"
            "raise SystemExit(result)\n",
            encoding="utf-8",
        )
        (self.fake_bin / "python.cmd").write_text(
            f'@echo off\r\n"{sys.executable}" "%~dp0fake_python.py" %*\r\nexit /b %ERRORLEVEL%\r\n',
            encoding="ascii",
        )

        result = self.run_script(
            "build-magisk.ps1",
            "-AllowEmptyAllowedSignatures",
            env=self.environment(
                FAKE_MUTATE_SOURCE_AFTER_VERIFY=str(source_path),
                FAKE_REAL_PACKER=str(self.scripts / "pack_magisk_zip.py"),
            ),
        )

        self.assert_failed(result)
        self.assertEqual(output.read_bytes(), b"previous-good-zip")

    def test_packaging_rejects_tampered_binary(self) -> None:
        self.make_current_binary()
        self.binary.write_bytes(b"tampered-runtime")
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        output.parent.mkdir()
        output.write_bytes(b"previous-good-zip")

        result = self.run_script("build-magisk.ps1")

        self.assert_failed(result)
        self.assertEqual(output.read_bytes(), b"previous-good-zip")

    def test_pack_failure_is_nonzero_and_keeps_old_zip(self) -> None:
        self.make_current_binary()
        (self.module_dir / "service.sh").unlink()
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        output.parent.mkdir()
        output.write_bytes(b"previous-good-zip")

        result = self.run_script("build-magisk.ps1", "-AllowEmptyAllowedSignatures")

        self.assert_failed(result)
        self.assertEqual(output.read_bytes(), b"previous-good-zip")

    def test_successful_packaging_contains_generated_runtime_config(self) -> None:
        self.make_current_binary()

        result = self.run_script("build-magisk.ps1", "-AllowEmptyAllowedSignatures")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output = self.runtime_root / "dist/ClawRuntime-magisk.zip"
        with zipfile.ZipFile(output) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn(SECRET_A, archive.read("config/runtime.yaml").decode("utf-8"))
            self.assertNotIn("config/runtime.generated.yaml", archive.namelist())

    def test_secret_sync_preserves_non_ascii_content(self) -> None:
        result = self.run_script("sync-shared-secret.ps1")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("# 仓库配置", self.repo_properties.read_text(encoding="utf-8"))
        self.assertIn("# 应用配置", self.app_properties.read_text(encoding="utf-8"))
        self.assertIn("# 运行时配置", self.generated_config.read_text(encoding="utf-8"))

    def test_secret_sync_rejects_conflicting_app_properties(self) -> None:
        self.write_properties(self.app_properties, f"clawdroid.runtime.sharedSecret={SECRET_B}\n")
        before_root = self.repo_properties.read_bytes()
        before_app = self.app_properties.read_bytes()

        result = self.run_script("sync-shared-secret.ps1")

        self.assert_failed(result)
        self.assertEqual(self.repo_properties.read_bytes(), before_root)
        self.assertEqual(self.app_properties.read_bytes(), before_app)
        self.assertFalse(self.generated_config.exists())

    def test_secret_sync_rejects_conflicting_environment(self) -> None:
        before_root = self.repo_properties.read_bytes()

        result = self.run_script(
            "sync-shared-secret.ps1", env=self.environment(CLAWDROID_RUNTIME_SHARED_SECRET=SECRET_B)
        )

        self.assert_failed(result)
        self.assertEqual(self.repo_properties.read_bytes(), before_root)
        self.assertFalse(self.generated_config.exists())

    def test_secret_sync_reads_gradle_property(self) -> None:
        self.write_properties(self.repo_properties, "# no secret here\n")
        self.write_properties(self.app_properties, "# no secret here\n")
        (self.runtime_root.parent / "ClawApp/gradle.properties").write_text(
            f"clawdroid.clawRuntimeSharedSecret={SECRET_C}\n", encoding="utf-8"
        )

        result = self.run_script("sync-shared-secret.ps1")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"clawdroid.runtime.sharedSecret={SECRET_C}", self.repo_properties.read_text())
        self.assertIn(f"clawdroid.runtime.sharedSecret={SECRET_C}", self.app_properties.read_text())
        self.assertIn(SECRET_C, self.generated_config.read_text(encoding="utf-8"))

    def test_secret_sync_rejects_conflicting_gradle_property(self) -> None:
        (self.runtime_root.parent / "ClawApp/gradle.properties").write_text(
            f"clawdroid.clawRuntimeSharedSecret={SECRET_C}\n", encoding="utf-8"
        )

        result = self.run_script("sync-shared-secret.ps1")

        self.assert_failed(result)
        self.assertFalse(self.generated_config.exists())

    def test_secret_sync_rejects_conflicting_gradle_environment_property(self) -> None:
        result = self.run_script(
            "sync-shared-secret.ps1",
            env=self.environment(ORG_GRADLE_PROJECT_clawdroid_clawRuntimeSharedSecret=SECRET_B),
        )

        self.assert_failed(result)
        self.assertIn("Conflicting shared secret", result.stdout + result.stderr)
        self.assertFalse(self.generated_config.exists())

    def test_packaging_forwards_explicit_gradle_secret_and_rejects_conflict(self) -> None:
        self.make_current_binary()

        result = self.run_script("build-magisk.ps1", "-GradleSharedSecret", SECRET_B)

        self.assert_failed(result)
        self.assertIn("Conflicting shared secret", result.stdout + result.stderr)

    def test_secret_sync_rejects_empty_placeholder_short_and_non_hex_values(self) -> None:
        invalid_values = ("", "<64-char-random-secret>", "REPLACE_WITH_LOCAL_SECRET", "a" * 31, "z" * 64)
        for value in invalid_values:
            with self.subTest(value=value):
                self.write_properties(self.repo_properties, f"clawdroid.runtime.sharedSecret={value}\n")
                self.write_properties(self.app_properties, "# no secret here\n")
                self.generated_config.unlink(missing_ok=True)

                result = self.run_script("sync-shared-secret.ps1")

                self.assert_failed(result)
                self.assertFalse(self.generated_config.exists())

    def test_secret_sync_generates_one_valid_secret_for_app_and_runtime(self) -> None:
        self.write_properties(self.repo_properties, "# no secret here\n")
        self.write_properties(self.app_properties, "# no secret here\n")

        result = self.run_script("sync-shared-secret.ps1")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        root_text = self.repo_properties.read_text()
        app_text = self.app_properties.read_text()
        generated = self.generated_config.read_text(encoding="utf-8")
        root_secret = next(line.split("=", 1)[1] for line in root_text.splitlines() if line.startswith("clawdroid.runtime.sharedSecret="))
        app_secret = next(line.split("=", 1)[1] for line in app_text.splitlines() if line.startswith("clawdroid.runtime.sharedSecret="))
        self.assertEqual(root_secret, app_secret)
        self.assertRegex(root_secret, r"^[0-9a-f]{64}$")
        self.assertIn(f'shared_secret: "{root_secret}"', generated)

    def test_secret_sync_rejects_incomplete_allowed_signature(self) -> None:
        self.write_properties(
            self.repo_properties,
            f"clawdroid.runtime.sharedSecret={SECRET_A}\nclawdroid.runtime.allowedSignatures=sha256:{'a' * 63}\n",
        )

        result = self.run_script("sync-shared-secret.ps1")

        self.assert_failed(result)
        self.assertFalse(self.generated_config.exists())

    def test_property_example_can_be_used_without_a_placeholder_secret(self) -> None:
        shutil.copy2(REPO_ROOT / "local.properties.example", self.repo_properties)
        shutil.copy2(REPO_ROOT / "ClawApp/local.properties.example", self.app_properties)

        result = self.run_script("sync-shared-secret.ps1")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        generated = self.generated_config.read_text(encoding="utf-8")
        self.assertNotIn("<64-char-random-secret>", generated)
        self.assertNotIn("REPLACE_WITH_LOCAL_SECRET", generated)
        self.assertRegex(generated, r'shared_secret: "[0-9a-f]{64}"')


if __name__ == "__main__":
    unittest.main()
