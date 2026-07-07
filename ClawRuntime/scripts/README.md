# Build Scripts

This directory contains helper scripts for the current `ClawRuntime` packaging flow.

- `build-runtime.ps1`: preferred entry that cross-builds the Go ClawRuntime daemon for Android and places it into the Magisk module `bin/` directory; supports `arm64`, `arm`, and `amd64`
- `build-magisk.ps1`: package the current Magisk module directory into a zip file under `dist/`, preserving Unix-style relative paths for Android extraction; automatically syncs the shared secret before packaging and stages the generated config as `config/runtime.yaml`
- `sync-shared-secret.ps1`: synchronize the shared secret and optional allowed signatures from repo-level `local.properties` into `magisk/config/runtime.generated.yaml`; generates a shared secret automatically if missing

Recommended workflow:

1. Copy the repo-level [local.properties.example](file:///d:/temp_desktop/Proj/Clawdroid/local.properties.example) to `local.properties`, or set `CLAWDROID_RUNTIME_SHARED_SECRET`
2. For release-oriented packaging, also set `clawdroid.runtime.allowedSignatures` or `CLAWDROID_RUNTIME_ALLOWED_SIGNATURES`
3. Run `.\scripts\sync-shared-secret.ps1` from `ClawRuntime\`
4. Run `.\scripts\build-runtime.ps1` from `ClawRuntime\`
5. Run `.\scripts\build-magisk.ps1` from `ClawRuntime\`
6. Install `dist\ClawRuntime-magisk.zip` in Magisk Manager on the test device

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

Secret source priority:

1. `CLAWDROID_RUNTIME_SHARED_SECRET`
2. repo-level `local.properties` key `clawdroid.runtime.sharedSecret`
3. auto-generated random secret written back to repo-level `local.properties`

Allowed signatures source priority:

1. `CLAWDROID_RUNTIME_ALLOWED_SIGNATURES`
2. repo-level `local.properties` key `clawdroid.runtime.allowedSignatures`

Release packaging helpers:

- `.\scripts\sync-shared-secret.ps1 -RequireAllowedSignatures`
- `.\scripts\build-magisk.ps1 -RequireAllowedSignatures`
- `.\scripts\build-magisk.ps1 -AllowedSignatures "sha256:aaa,sha256:bbb" -RequireAllowedSignatures`

Current Magisk module notes:

- Canonical module ID is `clawruntime`
- Package layout only includes the canonical runtime names: `clawdroid-runtime` and `config/runtime.yaml`
- App build and Magisk packaging now share the same secret source: repo-level `local.properties` key `clawdroid.runtime.sharedSecret` or env `CLAWDROID_RUNTIME_SHARED_SECRET`

More scripts can be added later for Android release, cross-compilation, and CI packaging.
