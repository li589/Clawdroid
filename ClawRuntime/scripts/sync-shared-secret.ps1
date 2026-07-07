Param(
    [string]$SharedSecret = "",
    [string]$AllowedSignatures = "",
    [switch]$RequireAllowedSignatures,
    [switch]$Regenerate
)

$ErrorActionPreference = "Stop"

$runtimeRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $runtimeRoot
$repoLocalProperties = Join-Path $repoRoot "local.properties"
$runtimeConfigTemplate = Join-Path $runtimeRoot "magisk\config\runtime.yaml"
$runtimeConfigExample = Join-Path $runtimeRoot "magisk\config\runtime.yaml.example"
$runtimeGeneratedConfig = Join-Path $runtimeRoot "magisk\config\runtime.generated.yaml"
$sharedSecretPropertyKey = "clawdroid.runtime.sharedSecret"
$allowedSignaturesPropertyKey = "clawdroid.runtime.allowedSignatures"

function Get-LocalPropertyValue {
    param(
        [string]$FilePath,
        [string]$Key
    )
    $escapedKey = [System.Text.RegularExpressions.Regex]::Escape($Key)
    if (-not (Test-Path $FilePath)) {
        return ""
    }
    foreach ($line in Get-Content -Path $FilePath) {
        if ($line -match "^$escapedKey=(.*)$") {
            return $Matches[1].Trim()
        }
    }
    return ""
}

function Set-LocalPropertyValue {
    param(
        [string]$FilePath,
        [string]$Key,
        [string]$Value
    )
    $escapedKey = [System.Text.RegularExpressions.Regex]::Escape($Key)
    $lines = @()
    if (Test-Path $FilePath) {
        $lines = Get-Content -Path $FilePath
    }
    $updated = $false
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match "^$escapedKey=") {
            $lines[$index] = "$Key=$Value"
            $updated = $true
        }
    }
    if (-not $updated) {
        $lines += "$Key=$Value"
    }
    Set-Content -Path $FilePath -Value $lines -Encoding ASCII
}

function New-SharedSecret {
    return ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
}

if ([string]::IsNullOrWhiteSpace($SharedSecret)) {
    $SharedSecret = $env:CLAWDROID_RUNTIME_SHARED_SECRET
}
if ([string]::IsNullOrWhiteSpace($SharedSecret) -and -not $Regenerate) {
    $SharedSecret = Get-LocalPropertyValue -FilePath $repoLocalProperties -Key $sharedSecretPropertyKey
}
if ([string]::IsNullOrWhiteSpace($SharedSecret)) {
    $SharedSecret = New-SharedSecret
}

if ([string]::IsNullOrWhiteSpace($AllowedSignatures)) {
    $AllowedSignatures = $env:CLAWDROID_RUNTIME_ALLOWED_SIGNATURES
}
if ([string]::IsNullOrWhiteSpace($AllowedSignatures)) {
    $AllowedSignatures = Get-LocalPropertyValue -FilePath $repoLocalProperties -Key $allowedSignaturesPropertyKey
}

if ($RequireAllowedSignatures -and [string]::IsNullOrWhiteSpace($AllowedSignatures)) {
    throw "Release packaging requires non-empty allowed signatures. Set CLAWDROID_RUNTIME_ALLOWED_SIGNATURES or clawdroid.runtime.allowedSignatures in local.properties."
}

Set-LocalPropertyValue -FilePath $repoLocalProperties -Key $sharedSecretPropertyKey -Value $SharedSecret
if (-not [string]::IsNullOrWhiteSpace($AllowedSignatures)) {
    Set-LocalPropertyValue -FilePath $repoLocalProperties -Key $allowedSignaturesPropertyKey -Value $AllowedSignatures
}

if (-not (Test-Path $runtimeConfigTemplate) -and -not (Test-Path $runtimeConfigExample)) {
    throw "Runtime config template not found. Expected one of: $runtimeConfigTemplate or $runtimeConfigExample"
}

$runtimeConfigSource = if (Test-Path $runtimeConfigTemplate) {
    $runtimeConfigTemplate
} else {
    $runtimeConfigExample
}

$runtimeYaml = Get-Content -Path $runtimeConfigSource -Raw
$sharedSecretPattern = 'shared_secret:\s*"[^"]*"'
$allowedSignaturesPattern = 'allowed_signatures:\s*"[^"]*"'
$currentSecretPattern = "shared_secret:\s*`"$([System.Text.RegularExpressions.Regex]::Escape($SharedSecret))`""
$hasSharedSecretEntry = [System.Text.RegularExpressions.Regex]::IsMatch($runtimeYaml, $sharedSecretPattern)
$alreadySynchronized = [System.Text.RegularExpressions.Regex]::IsMatch($runtimeYaml, $currentSecretPattern)
$updatedYaml = if ($alreadySynchronized) {
    $runtimeYaml
} else {
    [System.Text.RegularExpressions.Regex]::Replace(
        $runtimeYaml,
        $sharedSecretPattern,
        "shared_secret: `"$SharedSecret`""
    )
}
if (-not $hasSharedSecretEntry) {
    throw "Failed to update shared_secret in template source $runtimeConfigSource"
}

if (-not [System.Text.RegularExpressions.Regex]::IsMatch($updatedYaml, $allowedSignaturesPattern)) {
    throw "Failed to find allowed_signatures entry in template source $runtimeConfigSource"
}

$normalizedAllowedSignatures = if ([string]::IsNullOrWhiteSpace($AllowedSignatures)) {
    ""
} else {
    ($AllowedSignatures.Split(",") | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ -ne "" }) -join ","
}

$updatedYaml = [System.Text.RegularExpressions.Regex]::Replace(
    $updatedYaml,
    $allowedSignaturesPattern,
    "allowed_signatures: `"$normalizedAllowedSignatures`""
)

Set-Content -Path $runtimeGeneratedConfig -Value $updatedYaml -Encoding ASCII

Write-Output "Shared secret synchronized to local.properties and runtime.generated.yaml"
Write-Output "Template source: $runtimeConfigSource"
Write-Output "Generated config: $runtimeGeneratedConfig"
Write-Output "Secret length: $($SharedSecret.Length)"
Write-Output "Allowed signatures configured: $(-not [string]::IsNullOrWhiteSpace($normalizedAllowedSignatures))"
