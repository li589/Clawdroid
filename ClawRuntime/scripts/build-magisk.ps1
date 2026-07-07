Param(
    [string]$OutputZip = "dist\\ClawRuntime-magisk.zip",
    [string]$AllowedSignatures = "",
    [switch]$RequireAllowedSignatures
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$projectRoot = Split-Path -Parent $PSScriptRoot
$moduleDir = Join-Path $projectRoot "magisk"
$outputPath = Join-Path $projectRoot $OutputZip
$runtimeBinary = Join-Path $moduleDir "bin\\clawdroid-runtime"
$runtimeGeneratedConfig = Join-Path $moduleDir "config\\runtime.generated.yaml"
$syncSecretScript = Join-Path $PSScriptRoot "sync-shared-secret.ps1"

if (Test-Path $syncSecretScript) {
    $syncArgs = @{}
    if (-not [string]::IsNullOrWhiteSpace($AllowedSignatures)) {
        $syncArgs["AllowedSignatures"] = $AllowedSignatures
    }
    if ($RequireAllowedSignatures) {
        $syncArgs["RequireAllowedSignatures"] = $true
    }
    & $syncSecretScript @syncArgs | Out-Null
}

if (-not (Test-Path $runtimeBinary)) {
    throw "ClawRuntime binary missing: $runtimeBinary. Run .\\scripts\\build-runtime.ps1 first."
}
if (-not (Test-Path $runtimeGeneratedConfig)) {
    throw "Generated runtime config missing: $runtimeGeneratedConfig. Run .\\scripts\\sync-shared-secret.ps1 first."
}

$outputDir = Split-Path -Parent $outputPath
if ($outputDir -and -not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

if (Test-Path $outputPath) {
    Remove-Item -Force $outputPath
}

function Add-DirectoryToZip {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceDirectory,
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive
    )

    $sourceRoot = [System.IO.Path]::GetFullPath($SourceDirectory)
    $files = Get-ChildItem -Path $sourceRoot -Recurse -File
    foreach ($file in $files) {
        $fullName = [System.IO.Path]::GetFullPath($file.FullName)
        $relativePath = $fullName.Substring($sourceRoot.Length).TrimStart('\', '/')
        $entryName = $relativePath -replace '\\', '/'
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $fullName, $entryName) | Out-Null
    }
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

    $zip = [System.IO.Compression.ZipFile]::Open($outputPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        Add-DirectoryToZip -SourceDirectory $stageDir -Archive $zip
    } finally {
        $zip.Dispose()
    }
} finally {
    if (Test-Path $stageDir) {
        Remove-Item -Path $stageDir -Recurse -Force
    }
}

Write-Output "Created $outputPath"
