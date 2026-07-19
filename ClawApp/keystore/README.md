# ClawApp Release Signing Keystore

This directory holds the release signing keystore for ClawApp. The keystore
file (`*.jks`) is **never** committed to git — it is excluded by `.gitignore`.

## Setup

1. Generate a keystore (25-year validity, RSA 2048-bit):

   ```powershell
   $password = "<your-random-password>"  # see local.properties
   keytool -genkeypair -v `
       -keystore ClawApp\keystore\clawdroid-release.jks `
       -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 9125 `
       -alias clawdroid-release `
       -storepass $password -keypass $password `
       -dname "CN=Clawdroid, OU=Clawdroid, O=Clawdroid, L=Unknown, ST=Unknown, C=CN"
   ```

2. Compute the certificate SHA-256 digest (must match
   `clawdroid.runtime.allowedSignatures` in `local.properties`):

   ```powershell
   keytool -list -v -keystore ClawApp\keystore\clawdroid-release.jks `
       -storepass $password -alias clawdroid-release |
       Select-String "SHA256:"
   # Remove colons, lowercase, prefix with "sha256:" → sha256:<hex>
   ```

3. Configure `local.properties` (repo root, gitignored):

   ```properties
   clawdroid.keystore.path=keystore/clawdroid-release.jks
   clawdroid.keystore.password=<your-password>
   clawdroid.keystore.alias=clawdroid-release
   clawdroid.keystore.key.password=<your-password>
   clawdroid.runtime.allowedSignatures=sha256:<certificate-sha256-hex>
   ```

4. Rebuild Magisk module so `runtime.generated.yaml` picks up the new
   `allowed_signatures`:

   ```powershell
   ClawRuntime\scripts\sync-shared-secret.ps1
   ClawRuntime\scripts\build-magisk.ps1
   ```

5. Build signed release APK:

   ```powershell
   cd ClawApp; .\gradlew.bat assembleRelease
   ```

## CI Behavior

GitHub Actions does **not** have access to the keystore. When
`clawdroid.keystore.path` is absent from `local.properties`, the release
build type falls back to **unsigned** output (`app-release-unsigned.apk`).
This is intentional — CI verifies compilation, while signed release APKs
are produced locally.

## Lost Keystore

If the keystore is lost, a new one must be generated and
`clawdroid.runtime.allowedSignatures` must be updated to match. Existing
installs cannot be upgraded — they must be uninstalled first because
Android refuses signature changes across updates.
