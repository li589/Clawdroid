Param(
    [string]$SharedSecret = "",
    [string]$AllowedSignatures = "",
    [string]$GradleSharedSecret = "",
    [switch]$RequireAllowedSignatures,
    [switch]$Regenerate
)

$ErrorActionPreference = "Stop"

$runtimeRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $runtimeRoot
$repoLocalProperties = Join-Path $repoRoot "local.properties"
$appLocalProperties = Join-Path $repoRoot "ClawApp\local.properties"
$runtimeConfigTemplate = Join-Path $runtimeRoot "magisk\config\runtime.yaml"
$runtimeConfigExample = Join-Path $runtimeRoot "magisk\config\runtime.yaml.example"
$runtimeGeneratedConfig = Join-Path $runtimeRoot "magisk\config\runtime.generated.yaml"
$sharedSecretPropertyKey = "clawdroid.runtime.sharedSecret"
$allowedSignaturesPropertyKey = "clawdroid.runtime.allowedSignatures"
$gradleSharedSecretPropertyKey = "clawdroid.clawRuntimeSharedSecret"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Get-PropertySources {
    param(
        [string]$FilePath,
        [string]$Key,
        [string]$SourceName
    )
    if (-not (Test-Path $FilePath -PathType Leaf)) {
        return
    }
    $escapedKey = [System.Text.RegularExpressions.Regex]::Escape($Key)
    $text = [System.IO.File]::ReadAllText($FilePath, $utf8NoBom)
    $lineNumber = 0
    foreach ($line in ($text -split '\r?\n')) {
        $lineNumber++
        if ($line -match "^\s*$escapedKey\s*[=:]\s*(.*?)\s*$") {
            [pscustomobject]@{
                Source = "$SourceName (line $lineNumber)"
                Value = $Matches[1]
            }
        }
    }
}

function Get-GradlePropertySources {
    param([string]$Key)
    $files = @(
        (Join-Path $repoRoot "gradle.properties"),
        (Join-Path $repoRoot "ClawApp\gradle.properties")
    )
    $gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME ".gradle" }
    $files += Join-Path $gradleUserHome "gradle.properties"
    foreach ($file in $files) {
        Get-PropertySources -FilePath $file -Key $Key -SourceName $file
    }
}

function Assert-SharedSecret {
    param([string]$Value, [string]$SourceName = "shared secret")
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$SourceName must not be empty."
    }
    $secret = $Value.Trim()
    if ($secret.StartsWith("REPLACE_", [System.StringComparison]::OrdinalIgnoreCase) -or $secret.Contains("<") -or $secret.Contains(">")) {
        throw "$SourceName is a placeholder; generate a local secret first."
    }
    if ($secret.Length -lt 32 -or $secret.Length % 2 -ne 0 -or $secret -notmatch '^[0-9a-fA-F]+$') {
        throw "$SourceName must contain at least 32 hexadecimal characters (64 recommended)."
    }
}

function Assert-AllowedSignatures {
    param([string]$Value, [string]$SourceName = "allowed signatures")
    foreach ($signature in ($Value.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })) {
        if ($signature -notmatch '^sha256:[0-9a-fA-F]{64}$') {
            throw "$SourceName contains an invalid digest; expected sha256: followed by 64 hexadecimal characters."
        }
    }
}

function Resolve-ConfiguredValue {
    param(
        [object[]]$Sources,
        [string]$ValueName,
        [switch]$RejectConfiguredEmpty
    )
    $configured = @($Sources | Where-Object { $null -ne $_ -and -not [string]::IsNullOrWhiteSpace($_.Value) })
    if ($RejectConfiguredEmpty) {
        $emptySource = @($Sources | Where-Object { $null -ne $_ -and [string]::IsNullOrWhiteSpace($_.Value) } | Select-Object -First 1)
        if ($emptySource.Count -gt 0) {
            throw "$ValueName in $($emptySource[0].Source) must not be empty."
        }
    }
    $distinct = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $sourceNames = [System.Collections.Generic.List[string]]::new()
    foreach ($source in $configured) {
        [void]$distinct.Add($source.Value.Trim())
        $sourceNames.Add($source.Source)
    }
    if ($distinct.Count -gt 1) {
        throw "Conflicting $ValueName values across: $($sourceNames -join '; '). Keep every configured source identical."
    }
    if ($distinct.Count -eq 0) {
        return ""
    }
    return @($configured)[0].Value.Trim()
}

function Set-LocalPropertyValue {
    param(
        [string]$FilePath,
        [string]$Key,
        [string]$Value
    )
    $escapedKey = [System.Text.RegularExpressions.Regex]::Escape($Key)
    $text = if (Test-Path $FilePath -PathType Leaf) {
        [System.IO.File]::ReadAllText($FilePath, $utf8NoBom)
    } else {
        ""
    }
    $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $pattern = "(?m)^$escapedKey\s*[=:][^\r\n]*"
    $replacement = "$Key=$Value"
    if ([System.Text.RegularExpressions.Regex]::IsMatch($text, $pattern)) {
        $updated = [System.Text.RegularExpressions.Regex]::Replace(
            $text,
            $pattern,
            [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $replacement }
        )
    } else {
        $separator = if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) { $newline } else { "" }
        $updated = "$text$separator$replacement$newline"
    }
    $parent = Split-Path -Parent $FilePath
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [System.IO.File]::WriteAllText($FilePath, $updated, $utf8NoBom)
}

function New-SharedSecret {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Get-ExternalSecretSources {
    param(
        [string]$ExplicitSharedSecret = "",
        [string]$ExplicitGradleSharedSecret = "",
        [switch]$HasExplicitSharedSecret,
        [switch]$HasExplicitGradleSharedSecret
    )
    $sources = @()
    if ($HasExplicitSharedSecret) {
        $sources += [pscustomobject]@{ Source = "-SharedSecret"; Value = $ExplicitSharedSecret }
    }
    if ($HasExplicitGradleSharedSecret) {
        $sources += [pscustomobject]@{ Source = "-GradleSharedSecret"; Value = $ExplicitGradleSharedSecret }
    }
    if (Test-Path Env:CLAWDROID_RUNTIME_SHARED_SECRET) {
        $sources += [pscustomobject]@{ Source = "CLAWDROID_RUNTIME_SHARED_SECRET"; Value = $env:CLAWDROID_RUNTIME_SHARED_SECRET }
    }
    foreach ($gradleEnvironmentKey in @(
        "ORG_GRADLE_PROJECT_clawdroid_clawRuntimeSharedSecret",
        "ORG_GRADLE_PROJECT_clawdroid.clawRuntimeSharedSecret"
    )) {
        if (Test-Path "Env:$gradleEnvironmentKey") {
            $value = [System.Environment]::GetEnvironmentVariable($gradleEnvironmentKey)
            $sources += [pscustomobject]@{ Source = $gradleEnvironmentKey; Value = $value }
        }
    }
    $sources += @(Get-GradlePropertySources -Key $gradleSharedSecretPropertyKey)
    return $sources
}

$externalSecretSources = @(Get-ExternalSecretSources `
    -ExplicitSharedSecret $SharedSecret `
    -ExplicitGradleSharedSecret $GradleSharedSecret `
    -HasExplicitSharedSecret:$PSBoundParameters.ContainsKey("SharedSecret") `
    -HasExplicitGradleSharedSecret:$PSBoundParameters.ContainsKey("GradleSharedSecret"))
$sharedSecretSources = @($externalSecretSources)
$sharedSecretSources += @(Get-PropertySources -FilePath $repoLocalProperties -Key $sharedSecretPropertyKey -SourceName $repoLocalProperties)
$sharedSecretSources += @(Get-PropertySources -FilePath $appLocalProperties -Key $sharedSecretPropertyKey -SourceName $appLocalProperties)

if ($Regenerate) {
    $externalSecret = Resolve-ConfiguredValue -Sources $externalSecretSources -ValueName "shared secret" -RejectConfiguredEmpty
    $SharedSecret = New-SharedSecret
    if ($externalSecret -ne "" -and $externalSecret -ne $SharedSecret) {
        throw "Cannot regenerate while an environment or Gradle shared secret is configured; make those sources identical or unset them first."
    }
} else {
    # Priority: explicit argument, environment, repo local.properties, App
    # local.properties, explicit Gradle property / Gradle properties files.
    # Conflicting non-empty values are rejected instead of silently overriding.
    $SharedSecret = Resolve-ConfiguredValue -Sources $sharedSecretSources -ValueName "shared secret" -RejectConfiguredEmpty
    if ($SharedSecret -eq "") {
        $SharedSecret = New-SharedSecret
    }
}
Assert-SharedSecret -Value $SharedSecret

$allowedSignatureSources = @()
if ($PSBoundParameters.ContainsKey("AllowedSignatures")) {
    $allowedSignatureSources += [pscustomobject]@{ Source = "-AllowedSignatures"; Value = $AllowedSignatures }
}
if (Test-Path Env:CLAWDROID_RUNTIME_ALLOWED_SIGNATURES) {
    $allowedSignatureSources += [pscustomobject]@{ Source = "CLAWDROID_RUNTIME_ALLOWED_SIGNATURES"; Value = $env:CLAWDROID_RUNTIME_ALLOWED_SIGNATURES }
}
$allowedSignatureSources += @(Get-PropertySources -FilePath $repoLocalProperties -Key $allowedSignaturesPropertyKey -SourceName $repoLocalProperties)
$allowedSignatureSources += @(Get-PropertySources -FilePath $appLocalProperties -Key $allowedSignaturesPropertyKey -SourceName $appLocalProperties)
$allowedSignatureSources += @(Get-GradlePropertySources -Key $allowedSignaturesPropertyKey)
$AllowedSignatures = Resolve-ConfiguredValue -Sources $allowedSignatureSources -ValueName "allowed signatures"
Assert-AllowedSignatures -Value $AllowedSignatures
$normalizedAllowedSignatures = ($AllowedSignatures.Split(",") | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ -ne "" }) -join ","

if ($RequireAllowedSignatures -and [string]::IsNullOrWhiteSpace($normalizedAllowedSignatures)) {
    throw "Release packaging requires non-empty allowed signatures. Set CLAWDROID_RUNTIME_ALLOWED_SIGNATURES or clawdroid.runtime.allowedSignatures in local.properties."
}

if (-not (Test-Path $runtimeConfigTemplate) -and -not (Test-Path $runtimeConfigExample)) {
    throw "Runtime config template not found. Expected one of: $runtimeConfigTemplate or $runtimeConfigExample"
}
$runtimeConfigSource = if (Test-Path $runtimeConfigTemplate) { $runtimeConfigTemplate } else { $runtimeConfigExample }
$runtimeYaml = [System.IO.File]::ReadAllText($runtimeConfigSource, $utf8NoBom)
$sharedSecretPattern = 'shared_secret:\s*"[^"]*"'
$allowedSignaturesPattern = 'allowed_signatures:\s*"[^"]*"'
if (-not [System.Text.RegularExpressions.Regex]::IsMatch($runtimeYaml, $sharedSecretPattern)) {
    throw "Failed to find shared_secret entry in template source $runtimeConfigSource"
}
if (-not [System.Text.RegularExpressions.Regex]::IsMatch($runtimeYaml, $allowedSignaturesPattern)) {
    throw "Failed to find allowed_signatures entry in template source $runtimeConfigSource"
}
$updatedYaml = [System.Text.RegularExpressions.Regex]::Replace($runtimeYaml, $sharedSecretPattern, "shared_secret: `"$SharedSecret`"")
$updatedYaml = [System.Text.RegularExpressions.Regex]::Replace($updatedYaml, $allowedSignaturesPattern, "allowed_signatures: `"$normalizedAllowedSignatures`"")

Set-LocalPropertyValue -FilePath $repoLocalProperties -Key $sharedSecretPropertyKey -Value $SharedSecret
Set-LocalPropertyValue -FilePath $appLocalProperties -Key $sharedSecretPropertyKey -Value $SharedSecret
if (-not [string]::IsNullOrWhiteSpace($normalizedAllowedSignatures)) {
    Set-LocalPropertyValue -FilePath $repoLocalProperties -Key $allowedSignaturesPropertyKey -Value $normalizedAllowedSignatures
    Set-LocalPropertyValue -FilePath $appLocalProperties -Key $allowedSignaturesPropertyKey -Value $normalizedAllowedSignatures
}
[System.IO.File]::WriteAllText($runtimeGeneratedConfig, $updatedYaml, $utf8NoBom)

Write-Output "Shared secret synchronized to repo/App local.properties and runtime.generated.yaml"
Write-Output "Template source: $runtimeConfigSource"
Write-Output "Generated config: $runtimeGeneratedConfig"
Write-Output "Secret length: $($SharedSecret.Length)"
Write-Output "Allowed signatures configured: $(-not [string]::IsNullOrWhiteSpace($normalizedAllowedSignatures))"
