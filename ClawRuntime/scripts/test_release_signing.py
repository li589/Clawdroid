from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
APP_ROOT = REPO_ROOT / "ClawApp"
GRADLE = Path(os.environ.get("CLAWDROID_GRADLE_BIN", r"C:\FreeRulesPrograms\BasicToolkits\Gradle\gradle-8.13\bin\gradle.bat"))
JAVA_HOME = Path(os.environ.get("JAVA_HOME", r"C:\FreeRulesPrograms\BasicToolkits\Java\jdk-17.0.19.10-hotspot"))
ANDROID_SDK = Path(os.environ.get("ANDROID_SDK_ROOT", r"D:\myPrograms\AndroidDevelop\Android-SDK"))
SECRET_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
SECRET_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"


@unittest.skipUnless(
    GRADLE.is_file() and (JAVA_HOME / "bin/java.exe").is_file() and (ANDROID_SDK / "platforms/android-35").is_dir(),
    "Gradle 8.13, JDK 17, and Android SDK 35 are required for release configuration tests",
)
class ReleaseSigningConfigurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo_root = Path(self.temporary.name)
        # Mirror the real layout: repo/local.properties and ClawApp/{app,...}.
        # Gradle's rootDir is ClawApp, not the app subproject.
        self.root = self.repo_root / "ClawApp"
        self.app = self.root / "app"
        self.app.mkdir(parents=True)
        (self.root / "xposed-stubs").mkdir()
        (self.root / "settings.gradle.kts").write_text(
            """pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ClawAppSigningFixture"
include(":app")
include(":xposed-stubs")
""",
            encoding="utf-8",
        )
        for relative in ("build.gradle.kts", "gradle.properties"):
            shutil.copy2(APP_ROOT / relative, self.root / relative)
        shutil.copy2(APP_ROOT / "app/build.gradle.kts", self.app / "build.gradle.kts")
        shutil.copy2(APP_ROOT / "xposed-stubs/build.gradle.kts", self.root / "xposed-stubs/build.gradle.kts")
        manifest = self.app / "src/main/AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        shutil.copy2(APP_ROOT / "app/src/main/AndroidManifest.xml", manifest)
        self.app_properties = self.root / "local.properties"
        self.app_properties.write_text(f"sdk.dir={ANDROID_SDK.as_posix()}\n", encoding="utf-8")
        (self.repo_root / "local.properties").write_text(
            f"clawdroid.runtime.sharedSecret={SECRET_A}\n", encoding="utf-8"
        )

    def run_gradle(self, task: str, *properties: str, env_overrides: dict[str, str] | None = None) -> subprocess.CompletedProcess:
        env = os.environ.copy()
        env["JAVA_HOME"] = str(JAVA_HOME)
        env["ANDROID_HOME"] = str(ANDROID_SDK)
        env["ANDROID_SDK_ROOT"] = str(ANDROID_SDK)
        env["CI"] = "true"
        for key in (
            "CLAWDROID_RUNTIME_SHARED_SECRET",
            "ORG_GRADLE_PROJECT_clawdroid.clawRuntimeSharedSecret",
            "ORG_GRADLE_PROJECT_clawdroid_clawRuntimeSharedSecret",
        ):
            env.pop(key, None)
        if env_overrides:
            env.update(env_overrides)
        return subprocess.run(
            [
                str(GRADLE),
                "-p",
                str(self.root),
                "--offline",
                "--no-daemon",
                "--console=plain",
                task,
                "--dry-run",
                *properties,
            ],
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=240,
        )

    def write_complete_signing_config(self) -> None:
        keystore = self.root / "keystore/clawdroid-release.jks"
        keystore.parent.mkdir()
        keystore.write_bytes(b"signing-config-fixture")
        (self.repo_root / "local.properties").write_text(
            f"clawdroid.runtime.sharedSecret={SECRET_A}\n"
            "clawdroid.keystore.path=keystore/clawdroid-release.jks\n"
            "clawdroid.keystore.password=test-password\n"
            "clawdroid.keystore.alias=clawdroid-release\n"
            "clawdroid.keystore.key.password=test-password\n",
            encoding="utf-8",
        )

    def test_debug_configuration_succeeds_without_release_signing(self) -> None:
        result = self.run_gradle(":app:assembleDebug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_release_configuration_fails_without_complete_signing(self) -> None:
        result = self.run_gradle(":app:assembleRelease")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("release", (result.stdout + result.stderr).lower())
        self.assertIn("sign", (result.stdout + result.stderr).lower())

    def test_debug_configuration_ignores_incomplete_release_signing(self) -> None:
        with (self.repo_root / "local.properties").open("a", encoding="utf-8") as stream:
            stream.write("clawdroid.keystore.path=missing.jks\n")
        result = self.run_gradle(":app:assembleDebug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_release_configuration_succeeds_with_complete_signing(self) -> None:
        self.write_complete_signing_config()
        result = self.run_gradle(":app:assembleRelease")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_release_configuration_uses_store_password_when_key_password_is_omitted(self) -> None:
        self.write_complete_signing_config()
        properties = self.repo_root / "local.properties"
        properties.write_text(
            properties.read_text(encoding="utf-8").replace("clawdroid.keystore.key.password=test-password\n", ""),
            encoding="utf-8",
        )
        result = self.run_gradle(":app:assembleRelease")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_generic_assemble_and_build_require_release_signing(self) -> None:
        for task in (":app:assemble", ":app:build"):
            with self.subTest(task=task):
                result = self.run_gradle(task)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("signing configuration", (result.stdout + result.stderr).lower())

    def test_release_lint_and_unit_tests_do_not_require_signing(self) -> None:
        for task in (":app:lintRelease", ":app:testReleaseUnitTest"):
            with self.subTest(task=task):
                result = self.run_gradle(task)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_release_bundle_and_package_require_signing(self) -> None:
        for task in (":app:bundleRelease", ":app:packageRelease"):
            with self.subTest(task=task):
                result = self.run_gradle(task)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("signing configuration", (result.stdout + result.stderr).lower())

    def test_release_configuration_rejects_missing_keystore_file(self) -> None:
        self.write_complete_signing_config()
        (self.root / "keystore/clawdroid-release.jks").unlink()
        result = self.run_gradle(":app:assembleRelease")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_app_and_runtime_secret_conflicts_fail_configuration(self) -> None:
        cases = (
            ("app local.properties", lambda: self.app_properties.write_text(
                f"sdk.dir={ANDROID_SDK.as_posix()}\nclawdroid.runtime.sharedSecret={SECRET_B}\n", encoding="utf-8"
            ), {}, ()),
            ("environment", lambda: None, {"CLAWDROID_RUNTIME_SHARED_SECRET": SECRET_B}, ()),
            ("Gradle property", lambda: None, {}, (f"-Pclawdroid.clawRuntimeSharedSecret={SECRET_B}",)),
        )
        for name, arrange, env, properties in cases:
            with self.subTest(source=name):
                arrange()
                result = self.run_gradle(":app:assembleDebug", *properties, env_overrides=env)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("conflict", (result.stdout + result.stderr).lower())
                # Restore the common fixture for the next subcase.
                self.app_properties.write_text(f"sdk.dir={ANDROID_SDK.as_posix()}\n", encoding="utf-8")

    def test_placeholder_secret_fails_configuration(self) -> None:
        (self.repo_root / "local.properties").write_text(
            "clawdroid.runtime.sharedSecret=<64-char-random-secret>\n", encoding="utf-8"
        )
        result = self.run_gradle(":app:assembleDebug")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("shared secret", (result.stdout + result.stderr).lower())


if __name__ == "__main__":
    unittest.main()
