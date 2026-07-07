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

if (-not $targetDir) {
    $targetDir = Join-Path $scriptDir $OutputDir
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
} else {
    $targetDir = $targetDir.Path
}

$binaryPath = Join-Path $targetDir $BinaryName

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

    go build -trimpath -ldflags="-s -w" -o $binaryPath $runtimeEntry
    Write-Output "Built Android ClawRuntime binary at $binaryPath using entry $runtimeEntry (GOOS=$($env:GOOS), GOARCH=$($env:GOARCH))"
} finally {
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
