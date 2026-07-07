param(
    [string]$AdbPath = "D:\myPrograms\AndroidDevelop\Android-SDK\platform-tools\adb.exe",
    [string]$DeviceId = "",
    [string]$ExpectedPackage = "com.clawdroid.app.debug",
    [string]$LaunchActivity = "",
    [string]$OutputPng = "",
    [string]$OutputXml = "",
    [int]$MaxAttempts = 4,
    [int]$RetryDelayMs = 900,
    [int]$LaunchDelayMs = 250,
    [switch]$UseShellFileCapture,
    [switch]$UseRootShellCapture,
    [switch]$SkipUiDumpValidation
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

if ($UseRootShellCapture) {
    $SkipUiDumpValidation = $true
}

if (-not (Test-Path $AdbPath)) {
    throw "adb 不存在: $AdbPath"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputPng)) {
    $OutputPng = Join-Path $repoRoot "debug-artifacts\foreground-capture.png"
}
if ([string]::IsNullOrWhiteSpace($OutputXml)) {
    $OutputXml = Join-Path $repoRoot "debug-artifacts\foreground-capture.xml"
}

$outputDir = Split-Path -Parent $OutputPng
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}
$xmlDir = Split-Path -Parent $OutputXml
if (-not (Test-Path $xmlDir)) {
    New-Item -ItemType Directory -Path $xmlDir | Out-Null
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $fullArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
        $fullArgs += @("-s", $DeviceId)
    }
    $fullArgs += $Arguments
    & $AdbPath @fullArgs
}

function Get-FocusDump {
    return (Invoke-Adb -Arguments @(
            "shell",
            "sh",
            "-c",
            "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus=|mFocusedApp='"
        ) | Out-String)
}

function Start-TargetActivity {
    if ([string]::IsNullOrWhiteSpace($LaunchActivity)) {
        return
    }
    Invoke-Adb -Arguments @("shell", "am", "start", "-W", "-n", $LaunchActivity) | Out-Null
}

function Test-ExpectedForeground {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FocusDump
    )

    $focusPattern = [regex]::Escape($ExpectedPackage)
    $currentFocusLine = [regex]::Match($FocusDump, '(?m)^\s*mCurrentFocus=.*$').Value
    $focusedAppLine = [regex]::Match($FocusDump, '(?m)^\s*mFocusedApp=.*$').Value
    return ($currentFocusLine -match $focusPattern) -or ($focusedAppLine -match $focusPattern)
}

function Save-ExecOutCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $AdbPath
    $arguments = @()
    if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
        $arguments += @("-s", $DeviceId)
    }
    $arguments += @("exec-out", "screencap", "-p")
    $startInfo.Arguments = [string]::Join(" ", $arguments)
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()

    $fileStream = [System.IO.File]::Create($DestinationPath)
    try {
        $process.StandardOutput.BaseStream.CopyTo($fileStream)
    } finally {
        $fileStream.Dispose()
    }

    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "exec-out screencap 失败: $stderr"
    }
}

function Save-ShellFileCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    $remotePath = "/sdcard/clawdroid-foreground-capture.png"
    Invoke-Adb -Arguments @("shell", "screencap", "-p", $remotePath) | Out-Null
    Invoke-Adb -Arguments @("pull", $remotePath, $DestinationPath) | Out-Null
}

function Save-RootShellFileCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    $remotePath = "/sdcard/clawdroid-foreground-capture-root.png"
    Invoke-Adb -Arguments @("shell", "su", "-c", "screencap -p $remotePath") | Out-Null
    Invoke-Adb -Arguments @("pull", $remotePath, $DestinationPath) | Out-Null
}

function Save-UiDump {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    $remotePath = "/sdcard/clawdroid-foreground-capture.xml"
    Invoke-Adb -Arguments @("shell", "uiautomator", "dump", $remotePath) | Out-Null
    Invoke-Adb -Arguments @("pull", $remotePath, $DestinationPath) | Out-Null
}

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    Start-TargetActivity
    if (-not [string]::IsNullOrWhiteSpace($LaunchActivity) -and $LaunchDelayMs -gt 0) {
        Start-Sleep -Milliseconds $LaunchDelayMs
    }

    $focusDump = Get-FocusDump
    $focusMatches = Test-ExpectedForeground -FocusDump $focusDump
    if (-not $focusMatches) {
        Start-Sleep -Milliseconds $RetryDelayMs
        continue
    }

    if ($SkipUiDumpValidation) {
        Set-Content -Path $OutputXml -Value "<!-- UI dump validation skipped because uiautomator dump switches MIUI foreground to Launcher on this device. -->" -Encoding utf8
    } else {
        Save-UiDump -DestinationPath $OutputXml
        $expectedPattern = [regex]::Escape($ExpectedPackage)
        $xmlMatches = Select-String -Path $OutputXml -Pattern $expectedPattern -Quiet
        if (-not $xmlMatches) {
            Start-Sleep -Milliseconds $RetryDelayMs
            continue
        }
    }

    if ($UseRootShellCapture) {
        Save-RootShellFileCapture -DestinationPath $OutputPng
    } elseif ($UseShellFileCapture) {
        Save-ShellFileCapture -DestinationPath $OutputPng
    } else {
        Save-ExecOutCapture -DestinationPath $OutputPng
    }

    $postFocusDump = Get-FocusDump
    $postFocusMatches = Test-ExpectedForeground -FocusDump $postFocusDump
    $focusLogPath = [System.IO.Path]::ChangeExtension($OutputPng, ".focus.txt")
    $focusLogContent = @(
        "=== BEFORE_CAPTURE ==="
        $focusDump
        "=== AFTER_CAPTURE ==="
        $postFocusDump
    )
    Set-Content -Path $focusLogPath -Value $focusLogContent -Encoding utf8
    if (-not $postFocusMatches) {
        Start-Sleep -Milliseconds $RetryDelayMs
        continue
    }

    Write-Host "Foreground capture succeeded on attempt $attempt"
    Write-Host "PNG: $OutputPng"
    Write-Host "XML: $OutputXml"
    exit 0
}

throw "未能在 $MaxAttempts 次尝试内同时确认前台窗口和 UI 层级属于 $ExpectedPackage"
