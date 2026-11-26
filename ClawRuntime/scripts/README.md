# Build Scripts

This directory contains helper scripts for the current `ClawRuntime` packaging flow.

- `build-runtime.ps1`: cross-builds the Go ClawRuntime daemon for Android (`arm64`, `arm`, `amd64`). It builds and embeds a magic/length/hash metadata trailer in a same-directory temporary binary, verifies it, and only then atomically replaces the previous binary. The JSON sidecar is a human-readable copy, not a verification input.
- `build-magisk.ps1`: verifies the binary's embedded Git revision, Runtime source fingerprint, raw-binary SHA-256, and Android target before staging and again after staging. It rejects an empty `auth.allowed_signatures` by default; only `-AllowEmptyAllowedSignatures` permits a local development package with an empty list.
- `sync-shared-secret.ps1`: validates all configured shared-secret sources, rejects conflicts/placeholders, and writes one value to repo/App `local.properties` and `magisk/config/runtime.generated.yaml` using UTF-8 without a BOM. It generates a 64-character random hex secret when no source is configured.
- `pack_magisk_zip.py`: validates the explicit Magisk file contract and the metadata trailer inside the binary, writes a temporary ZIP, checks its entries and CRC, then atomically replaces the output. Sidecar rewrites cannot bypass binary provenance checks; failures preserve the previous ZIP.

Recommended workflow:

1. Copy the repo-level [local.properties.example](../../local.properties.example) to `local.properties`, then run `.\scripts\sync-shared-secret.ps1` from `ClawRuntime\` to initialize the shared secret.
2. For release-oriented packaging, also set `clawdroid.runtime.allowedSignatures` or `CLAWDROID_RUNTIME_ALLOWED_SIGNATURES`.
3. Run `.\scripts\build-runtime.ps1` from `ClawRuntime\`.
4. Run `.\scripts\build-magisk.ps1` from `ClawRuntime\` for a release package. The script rechecks that the binary matches the current Git revision and source tree both before and after staging, and requires a non-empty signature allowlist.
5. Install `dist\ClawRuntime-magisk.zip` in Magisk Manager on the test device.

Bridge-based retest workflow after reinstall:

1. Reinstall the latest `app-debug.apk`
2. Reinstall `dist\ClawRuntime-magisk.zip`
3. Reboot the device
4. Verify module state from device:
   - `/data/adb/modules/clawruntime/webroot/status.json`
   - `/data/adb/modules/clawruntime/webroot/verify.json`
5. Trigger `DebugRuntimeBridgeActivity` from ADB with:
   - `probe`
   - `capabilities`
   - `capture_and_read`
   - `swipe`
   - `exec_shell_limited`
   - `events`
6. Pull `files/debug-runtime-result.json` with `run-as` and record:
   - `finalState`
   - `stateTrace`
   - `degradedReason`
   - action-level fields such as `accepted`, `exitCode`, `timedOut`, `frameCount`, `closedReason`

Config model:

- `magisk/config/runtime.yaml`
  - committed template used as the public default structure
- `magisk/config/runtime.yaml.example`
  - example copy for reference and onboarding
- `magisk/config/runtime.generated.yaml`
  - local generated file containing the real shared secret
  - ignored by the repository and used only for local packaging

Shared secret resolution:

- Explicit `-SharedSecret` / `-GradleSharedSecret` arguments, `CLAWDROID_RUNTIME_SHARED_SECRET`, and Gradle's `ORG_GRADLE_PROJECT_clawdroid_clawRuntimeSharedSecret` environment property.
- `clawdroid.runtime.sharedSecret` in repo-root and `ClawApp/local.properties`.
- `clawdroid.clawRuntimeSharedSecret` in repo-root, `ClawApp/`, and Gradle user-home `gradle.properties`.
- Every configured non-empty value must be identical; a configured empty value is rejected. Only when no source exists is a random secret generated and written to both local properties files.
- Secrets must be at least 32 hexadecimal characters (64 recommended). `REPLACE_*`, angle-bracket placeholders, and non-hex values are rejected.

Allowed signatures resolution:

- `-AllowedSignatures`, `CLAWDROID_RUNTIME_ALLOWED_SIGNATURES`, repo/App `local.properties`, and the same Gradle properties files.
- Conflicting configured values fail. Each non-empty digest must be `sha256:` followed by exactly 64 hexadecimal characters.

Release packaging helpers:

- `.\scripts\build-magisk.ps1` (default; fails when the signature allowlist is empty)
- `.\scripts\build-magisk.ps1 -AllowedSignatures "sha256:<64-hex>"`
- `.\scripts\build-magisk.ps1 -GradleSharedSecret "<64-hex>"`
- `.\scripts\build-magisk.ps1 -AllowEmptyAllowedSignatures` (local development only)

Current Magisk module notes:

- Canonical module ID is `clawruntime`.
- The package contains `bin/clawdroid-runtime` with an authoritative embedded provenance trailer, its `bin/clawdroid-runtime.build.json` human-readable sidecar, and the staged `config/runtime.yaml`.
- Packaging requires the installer scripts, `module.prop`, `sepolicy.rule`, WebUI files, and Magisk `META-INF` entrypoints. Missing files, CRC errors, or atomic-replace failures leave any previous ZIP untouched.

More scripts can be added later for Android release, cross-compilation, and CI packaging.
