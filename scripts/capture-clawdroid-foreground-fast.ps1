param(
    [string]$AdbPath = "D:\myPrograms\AndroidDevelop\Android-SDK\platform-tools\adb.exe",
    [string]$DeviceId = "",
    [string]$OutputPng = "",
    [string]$OutputXml = "",
    [int]$MaxAttempts = 3,
    [int]$RetryDelayMs = 350,
    [int]$LaunchDelayMs = 250
)

$ErrorActionPreference = "Stop"

$targetScript = Join-Path $PSScriptRoot "capture-clawdroid-foreground.ps1"
if (-not (Test-Path $targetScript)) {
    throw "脚本不存在: $targetScript"
}

$arguments = @(
    "-File", $targetScript,
    "-AdbPath", $AdbPath,
    "-CaptureMode", "exec-out",
    "-MaxAttempts", $MaxAttempts.ToString(),
    "-RetryDelayMs", $RetryDelayMs.ToString(),
    "-LaunchDelayMs", $LaunchDelayMs.ToString()
)

if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
    $arguments += @("-DeviceId", $DeviceId)
}

if (-not [string]::IsNullOrWhiteSpace($OutputPng)) {
    $arguments += @("-OutputPng", $OutputPng)
}

if (-not [string]::IsNullOrWhiteSpace($OutputXml)) {
    $arguments += @("-OutputXml", $OutputXml)
}

& pwsh @arguments
