Param(
    [string]$OutputDir = "..\\magisk\\bin",
    [string]$BinaryName = "clawdroid-runtime",
    [ValidateSet("arm64", "arm", "amd64")]
    [string]$TargetArch = "arm64"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimeDir = Join-Path (Split-Path -Parent $scriptDir) "runtime"
$targetDir = Resolve-Path (Join-Path $scriptDir $OutputDir) -ErrorAction SilentlyContinue
$runtimeEntry = ".\\cmd\\runtime"
$packScript = Join-Path $PSScriptRoot "pack_magisk_zip.py"

if (-not $targetDir) {
    $targetDir = [System.IO.Path]::GetFullPath((Join-Path $scriptDir $OutputDir))
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
} else {
    $targetDir = $targetDir.Path
}

$binaryPath = Join-Path $targetDir $BinaryName
$temporaryBinaryPath = Join-Path $targetDir (".$BinaryName.$PID.$([guid]::NewGuid().ToString('N')).tmp")
$temporaryMetadataPath = "$temporaryBinaryPath.build.json"
$metadataPath = "$binaryPath.build.json"
$revision = (& git -C $runtimeDir rev-parse HEAD 2>$null)
if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$') {
    throw "Unable to identify the Runtime Git revision; refusing to build unverifiable artifacts."
}
$revision = $revision.Trim()
if (-not (Test-Path $packScript)) {
    throw "Missing build metadata packer: $packScript"
}

$goArch = switch ($TargetArch) {
    "arm64" { "arm64" }
    "arm" { "arm" }
    "amd64" { "amd64" }
}

Push-Location $runtimeDir
try {
    $previousGoos = $env:GOOS
    $previousGoarch = $env:GOARCH
    $previousGoarm = $env:GOARM
    $previousCgo = $env:CGO_ENABLED

    $env:GOOS = "android"
    $env:GOARCH = $goArch
    $env:CGO_ENABLED = "0"
    if ($goArch -eq "arm") {
        $env:GOARM = "7"
    } elseif (Test-Path Env:GOARM) {
        Remove-Item Env:GOARM
    }

    if (-not (Test-Path (Join-Path $runtimeDir "cmd\\runtime"))) {
        throw "Runtime entry missing: $runtimeDir\\cmd\\runtime"
    }

    go build -trimpath -ldflags="-s -w" -o $temporaryBinaryPath $runtimeEntry
    if ($LASTEXITCODE -ne 0) {
        throw "go build failed with exit code $LASTEXITCODE; previous Runtime binary was not replaced."
    }
    if (-not (Test-Path $temporaryBinaryPath -PathType Leaf)) {
        throw "go build reported success but did not create $temporaryBinaryPath"
    }

    # Embed and verify provenance while the new binary is still temporary.
    # A metadata failure must not replace the previously working binary.
    & python $packScript --embed-build-metadata --binary $temporaryBinaryPath --runtime-dir $runtimeDir --revision $revision --goos android --goarch $goArch --cgo-enabled 0
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to embed Runtime build metadata (python exit $LASTEXITCODE); previous Runtime binary was not replaced."
    }
    if (-not (Test-Path $temporaryMetadataPath -PathType Leaf)) {
        throw "Runtime metadata embedding reported success but did not create $temporaryMetadataPath; previous Runtime binary was not replaced."
    }

    # Both binary paths are on the same filesystem; overwrite is an atomic rename.
    [System.IO.File]::Move($temporaryBinaryPath, $binaryPath, $true)
    try {
        [System.IO.File]::Move($temporaryMetadataPath, $metadataPath, $true)
    } catch {
        # The sidecar is for humans only. The authoritative trailer is already
        # inside the atomically installed binary, so a stale sidecar is non-fatal.
        Write-Warning "Installed Runtime binary but could not refresh human-readable metadata: $($_.Exception.Message)"
    }
    Write-Output "Built Android ClawRuntime binary at $binaryPath using entry $runtimeEntry (GOOS=$($env:GOOS), GOARCH=$($env:GOARCH), revision=$revision)"
} finally {
    if (Test-Path $temporaryBinaryPath -PathType Leaf) {
        Remove-Item -Force $temporaryBinaryPath -ErrorAction SilentlyContinue
    }
    if (Test-Path $temporaryMetadataPath -PathType Leaf) {
        Remove-Item -Force $temporaryMetadataPath -ErrorAction SilentlyContinue
    }
    $env:GOOS = $previousGoos
    $env:GOARCH = $previousGoarch
    $env:CGO_ENABLED = $previousCgo
    if ($null -ne $previousGoarm) {
        $env:GOARM = $previousGoarm
    } elseif (Test-Path Env:GOARM) {
        Remove-Item Env:GOARM
    }
    Pop-Location
}
