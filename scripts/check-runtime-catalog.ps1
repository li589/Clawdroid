#Requires -Version 5.1
<#
.SYNOPSIS
  Verify App RuntimeActionCatalog / RuntimeErrorCodes stay aligned with Go ipc SSOT.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root "ClawRuntime"))) {
    $root = $PSScriptRoot
    if (-not (Test-Path (Join-Path $root "ClawRuntime"))) {
        $root = Split-Path -Parent $PSScriptRoot
    }
}

$goActions = Join-Path $root "ClawRuntime/runtime/internal/ipc/actions.go"
$goErrors = Join-Path $root "ClawRuntime/runtime/internal/ipc/errors.go"
$ktCatalog = Join-Path $root "ClawApp/app/src/main/java/com/clawdroid/app/runtime/RuntimeActionCatalog.kt"
$ktErrors = Join-Path $root "ClawApp/app/src/main/java/com/clawdroid/app/runtime/RuntimeErrorCodes.kt"

function Get-GoStringConsts([string]$path, [string]$prefix) {
    $text = Get-Content -Raw -Path $path
    $matches = [regex]::Matches($text, "(?m)^\s*$prefix\w+\s*=\s*`"([^`"]+)`"")
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($m in $matches) { [void]$set.Add($m.Groups[1].Value) }
    return $set
}

function Get-GoErrorCodes([string]$path) {
    $text = Get-Content -Raw -Path $path
    $matches = [regex]::Matches($text, "(?m)^\s*Code\w+\s*=\s*(\d+)")
    $set = New-Object 'System.Collections.Generic.HashSet[int]'
    foreach ($m in $matches) { [void]$set.Add([int]$m.Groups[1].Value) }
    return $set
}

function Get-KtActionStrings([string]$path) {
    $text = Get-Content -Raw -Path $path
    # Action string consts before capability map; take quoted values that look like actions
    # (snake_case, no dots) from top-level const val assignments that are not CAPABILITY_*.
    $matches = [regex]::Matches(
        $text,
        "(?m)^\s*const val (?!CAPABILITY_)[A-Z0-9_]+\s*=\s*`"([a-z0-9_]+)`""
    )
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($m in $matches) { [void]$set.Add($m.Groups[1].Value) }
    return $set
}

function Get-KtErrorCodes([string]$path) {
    $text = Get-Content -Raw -Path $path
    $matches = [regex]::Matches($text, "(?m)^\s*const val [A-Z0-9_]+\s*=\s*(\d+)")
    $set = New-Object 'System.Collections.Generic.HashSet[int]'
    foreach ($m in $matches) { [void]$set.Add([int]$m.Groups[1].Value) }
    return $set
}

function Assert-SetEqual($left, $right, [string]$label) {
    $onlyLeft = $left | Where-Object { -not $right.Contains($_) } | Sort-Object
    $onlyRight = $right | Where-Object { -not $left.Contains($_) } | Sort-Object
    if ($onlyLeft -or $onlyRight) {
        Write-Host "MISMATCH: $label"
        if ($onlyLeft) { Write-Host "  only in Go: $($onlyLeft -join ', ')" }
        if ($onlyRight) { Write-Host "  only in Kotlin: $($onlyRight -join ', ')" }
        exit 1
    }
    Write-Host "OK: $label ($($left.Count) entries)"
}

$goActionSet = Get-GoStringConsts $goActions "Action"
# Capability strings also match Action* pattern only for Action prefix — good.
# Exclude empty
$ktActionSet = Get-KtActionStrings $ktCatalog

Assert-SetEqual $goActionSet $ktActionSet "IPC actions (actions.go vs RuntimeActionCatalog)"

$goErr = Get-GoErrorCodes $goErrors
$ktErr = Get-KtErrorCodes $ktErrors
Assert-SetEqual $goErr $ktErr "IPC error codes (errors.go vs RuntimeErrorCodes)"

Write-Host "Runtime catalog check passed."
exit 0
