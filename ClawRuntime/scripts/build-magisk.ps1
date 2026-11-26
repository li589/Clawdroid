Param(
    [string]$OutputZip = "dist\\ClawRuntime-magisk.zip",
    [string]$AllowedSignatures = "",
    [string]$GradleSharedSecret = "",
    [switch]$AllowEmptyAllowedSignatures
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$moduleDir = Join-Path $projectRoot "magisk"
$outputPath = Join-Path $projectRoot $OutputZip
$runtimeBinary = Join-Path $moduleDir "bin\\clawdroid-runtime"
$runtimeGeneratedConfig = Join-Path $moduleDir "config\\runtime.generated.yaml"
$syncSecretScript = Join-Path $PSScriptRoot "sync-shared-secret.ps1"
$packScript = Join-Path $PSScriptRoot "pack_magisk_zip.py"
$metaBinary = Join-Path $moduleDir "META-INF\\com\\google\\android\\update-binary"
$metaScript = Join-Path $moduleDir "META-INF\\com\\google\\android\\updater-script"
$runtimeSourceDir = Join-Path $projectRoot "runtime"

$revision = (& git -C $runtimeSourceDir rev-parse HEAD 2>$null)
if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$') {
    throw "Unable to identify the Runtime Git revision; refusing to package an unverifiable binary."
}
$revision = $revision.Trim()
if (-not (Test-Path $packScript)) {
    throw "Missing packer: $packScript"
}
& python $packScript --verify-build --module-dir $moduleDir --runtime-dir $runtimeSourceDir --expected-revision $revision
if ($LASTEXITCODE -ne 0) {
    throw "Runtime binary freshness/source verification failed (python exit $LASTEXITCODE)."
}

if (Test-Path $syncSecretScript) {
    $syncArgs = @{}
    if ($PSBoundParameters.ContainsKey("AllowedSignatures")) {
        $syncArgs["AllowedSignatures"] = $AllowedSignatures
    }
    if ($PSBoundParameters.ContainsKey("GradleSharedSecret")) {
        $syncArgs["GradleSharedSecret"] = $GradleSharedSecret
    }
    if (-not $AllowEmptyAllowedSignatures) {
        $syncArgs["RequireAllowedSignatures"] = $true
    }
    & $syncSecretScript @syncArgs | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "sync-shared-secret.ps1 failed with exit $LASTEXITCODE"
    }
}

if (-not (Test-Path $runtimeBinary)) {
    throw "ClawRuntime binary missing: $runtimeBinary. Run .\\scripts\\build-runtime.ps1 first."
}
if (-not (Test-Path $runtimeGeneratedConfig)) {
    throw "Generated runtime config missing: $runtimeGeneratedConfig. Run .\\scripts\\sync-shared-secret.ps1 first."
}
$generatedConfigText = [System.IO.File]::ReadAllText($runtimeGeneratedConfig)
$allowedSignaturesMatch = [System.Text.RegularExpressions.Regex]::Match(
    $generatedConfigText,
    '(?m)^\s*allowed_signatures:\s*"([^"]*)"\s*$'
)
if (-not $allowedSignaturesMatch.Success) {
    throw "Generated runtime config has no supported auth.allowed_signatures entry."
}
$packagedAllowedSignatures = @($allowedSignaturesMatch.Groups[1].Value.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
if (-not $AllowEmptyAllowedSignatures -and $packagedAllowedSignatures.Count -eq 0) {
    throw "Release packaging requires non-empty allowed signatures. Configure a digest or pass -AllowEmptyAllowedSignatures only for local development."
}
if (-not (Test-Path $metaBinary) -or -not (Test-Path $metaScript)) {
    throw "Magisk META-INF installer missing under magisk/META-INF/com/google/android/"
}
if (-not (Test-Path $packScript)) {
    throw "Missing packer: $packScript"
}

$outputDir = Split-Path -Parent $outputPath
if ($outputDir -and -not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

$stageDir = Join-Path ([System.IO.Path]::GetTempPath()) ("clawdroid-magisk-stage-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $stageDir | Out-Null

try {
    Get-ChildItem -Path $moduleDir -Force | ForEach-Object {
        Copy-Item -Path $_.FullName -Destination $stageDir -Recurse -Force
    }

    $stageGeneratedConfig = Join-Path $stageDir "config\\runtime.generated.yaml"
    if (Test-Path $stageGeneratedConfig) {
        Remove-Item -Force $stageGeneratedConfig
    }
    $stageConfigExample = Join-Path $stageDir "config\\runtime.yaml.example"
    if (Test-Path $stageConfigExample) {
        Remove-Item -Force $stageConfigExample
    }
    Copy-Item -Path $runtimeGeneratedConfig -Destination (Join-Path $stageDir "config\\runtime.yaml") -Force

    # Drop empty placeholder trees that confuse some Magisk extractors.
    foreach ($emptyDir in @("system", "zygisk")) {
        $candidate = Join-Path $stageDir $emptyDir
        if ((Test-Path $candidate) -and -not (Get-ChildItem $candidate -Force -Recurse -ErrorAction SilentlyContinue | Where-Object { -not $_.PSIsContainer })) {
            Remove-Item -Path $candidate -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # Revalidate the copied artifact and current sources after staging. This
    # closes the preflight-to-pack window instead of trusting the first check.
    & python $packScript --verify-build --module-dir $stageDir --runtime-dir $runtimeSourceDir --expected-revision $revision
    if ($LASTEXITCODE -ne 0) {
        throw "Post-stage Runtime binary freshness/source verification failed (python exit $LASTEXITCODE)."
    }

    & python $packScript --stage $stageDir --output $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw "pack_magisk_zip.py failed with exit $LASTEXITCODE"
    }
} finally {
    if (Test-Path $stageDir) {
        Remove-Item -Path $stageDir -Recurse -Force
    }
}

Write-Output "Created $outputPath"
