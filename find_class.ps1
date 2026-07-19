$targetFile = Join-Path $PSScriptRoot 'ClawApp\app\src\main\java\com\clawdroid\app\ipc\ClawRuntimeIpcClient.kt'
$lines = Get-Content $targetFile
$lineNum = 0
foreach ($line in $lines) {
    $lineNum++
    if ($line -match '^class ClawRuntimeIpcClient|^\s+companion object') {
        Write-Host "Line $lineNum : $line"
    }
}
