[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch]$IncludeRuntimeArtifacts
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$targets = @(
    ".android",
    ".android-tmp",
    ".gradle",
    "ClawApp/.gradle",
    "ClawApp/.gradle-user",
    "ClawApp/app/build",
    "ClawApp/build",
    "ClawApp/xposed-stubs/build",
    "ClawRuntime/runtime/cmd/runtime/runtime.exe",
    "ClawRuntime/scripts/__pycache__"
)

if ($IncludeRuntimeArtifacts) {
    $targets += @(
        "ClawRuntime/dist",
        "ClawRuntime/magisk/bin",
        "ClawRuntime/magisk/config/runtime.generated.yaml"
    )
}

foreach ($relative in $targets) {
    $path = Join-Path $repoRoot $relative
    if (Test-Path $path) {
        if ($PSCmdlet.ShouldProcess($path, "Remove regenerable cache")) {
            Remove-Item -Path $path -Recurse -Force
            Write-Output "Removed $relative"
        }
    }
}

Write-Output "Preserved local.properties, keystore, GitSource, debug-artifacts, artifacts, ai-docs, IDE settings, and .codegraph."
