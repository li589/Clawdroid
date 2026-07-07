param(
    [string]$AdbPath = "D:\myPrograms\AndroidDevelop\Android-SDK\platform-tools\adb.exe",
    [string]$DeviceId = "",
    [string]$PackageName = "com.clawdroid.app.debug",
    [string]$Operation = "probe",
    [string]$Prompt = "获取能力",
    [switch]$ClearHistory,
    [int]$WaitMs = 350,
    [string]$OutputJson = ""
)

$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

if (-not (Test-Path $AdbPath)) {
    throw "adb 不存在: $AdbPath"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$safeOperation = if ([string]::IsNullOrWhiteSpace($Operation)) {
    "probe"
} else {
    ($Operation -replace "[^a-zA-Z0-9_-]", "_").ToLowerInvariant()
}

if ([string]::IsNullOrWhiteSpace($OutputJson)) {
    $OutputJson = Join-Path $repoRoot "debug-artifacts\runtime\$safeOperation-result.json"
}

$outputDir = Split-Path -Parent $OutputJson
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

$bridgeActivity = "$PackageName/com.clawdroid.app.DebugRuntimeBridgeActivity"
$bridgeReceiver = "$PackageName/com.clawdroid.app.DebugRuntimeBridgeReceiver"

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $AdbPath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = $utf8
    $startInfo.StandardErrorEncoding = $utf8

    $fullArgs = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
        $fullArgs.Add("-s")
        $fullArgs.Add($DeviceId)
    }
    foreach ($argument in $Arguments) {
        $fullArgs.Add($argument)
    }
    foreach ($argument in $fullArgs) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        $detail = if ([string]::IsNullOrWhiteSpace($stderr)) { $stdout } else { $stderr }
        throw "adb 执行失败(exit=$($process.ExitCode)): $detail"
    }

    return $stdout.TrimEnd("`r", "`n")
}

function Read-ResultJson {
    $raw = Invoke-Adb -Arguments @(
        "shell",
        "run-as",
        $PackageName,
        "cat",
        "files/debug-runtime-result.json"
    ) | Out-String

    $normalized = $raw.Trim()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        throw "未读取到 debug-runtime-result.json，请先确认桥接操作已执行成功。"
    }
    return $normalized
}

if ($Operation -eq "chat_prompt") {
    $commandOutput = Invoke-Adb -Arguments @(
        "shell",
        "am",
        "broadcast",
        "-n",
        $bridgeReceiver,
        "--es",
        "operation",
        "chat_prompt",
        "--es",
        "prompt",
        $Prompt
    )
    if ($ClearHistory) {
        $commandOutput = Invoke-Adb -Arguments @(
            "shell",
            "am",
            "broadcast",
            "-n",
            $bridgeReceiver,
            "--es",
            "operation",
            "chat_prompt",
            "--es",
            "prompt",
            $Prompt,
            "--ez",
            "clear_history",
            "true"
        )
    }
} else {
    $commandOutput = Invoke-Adb -Arguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        $bridgeActivity,
        "--es",
        "operation",
        $Operation
    )
}

if ($WaitMs -gt 0) {
    Start-Sleep -Milliseconds $WaitMs
}

$resultJson = Read-ResultJson
Set-Content -Path $OutputJson -Value $resultJson -Encoding utf8
$displayJson = try {
    $resultJson | ConvertFrom-Json | ConvertTo-Json -Depth 8 -EscapeHandling EscapeNonAscii
} catch {
    $resultJson
}

Write-Host "Operation: $Operation"
Write-Host "Result JSON: $OutputJson"
if ($commandOutput) {
    Write-Host ($commandOutput | Out-String).Trim()
}
Write-Host $displayJson
