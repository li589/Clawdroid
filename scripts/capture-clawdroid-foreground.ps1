param(
    [string]$AdbPath = "D:\myPrograms\AndroidDevelop\Android-SDK\platform-tools\adb.exe",
    [string]$DeviceId = "",
    [string]$OutputPng = "",
    [string]$OutputXml = "",
    [ValidateSet("root", "exec-out", "shell-file")]
    [string]$CaptureMode = "root",
    [int]$MaxAttempts = 3,
    [int]$RetryDelayMs = 350,
    [int]$LaunchDelayMs = 250,
    [switch]$WithUiDumpValidation
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetScript = Join-Path $PSScriptRoot "adb-capture-foreground.ps1"
$expectedPackage = "com.clawdroid.app.debug"
$launchActivity = "$expectedPackage/com.clawdroid.app.MainActivity"

if (-not (Test-Path $targetScript)) {
    throw "脚本不存在: $targetScript"
}

if ([string]::IsNullOrWhiteSpace($OutputPng)) {
    $OutputPng = Join-Path $repoRoot "debug-artifacts\clawdroid-foreground.png"
}

if ([string]::IsNullOrWhiteSpace($OutputXml)) {
    $OutputXml = Join-Path $repoRoot "debug-artifacts\clawdroid-foreground.xml"
}

$arguments = @(
    "-File", $targetScript,
    "-AdbPath", $AdbPath,
    "-ExpectedPackage", $expectedPackage,
    "-LaunchActivity", $launchActivity,
    "-OutputPng", $OutputPng,
    "-OutputXml", $OutputXml,
    "-MaxAttempts", $MaxAttempts.ToString(),
    "-RetryDelayMs", $RetryDelayMs.ToString(),
    "-LaunchDelayMs", $LaunchDelayMs.ToString()
)

if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
    $arguments += @("-DeviceId", $DeviceId)
}

switch ($CaptureMode) {
    "root" {
        $arguments += "-UseRootShellCapture"
    }
    "shell-file" {
        $arguments += "-UseShellFileCapture"
    }
    "exec-out" {
    }
}

if (-not $WithUiDumpValidation) {
    $arguments += "-SkipUiDumpValidation"
}

& pwsh @arguments
