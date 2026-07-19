param(
    [string]$BaseUrl = "http://127.0.0.1:8765",
    [string]$Token = "p0-test-token-clawdroid-8765",
    [string]$OutDir = "d:\temp_desktop\Proj\Clawdroid\debug-artifacts\p0"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
$id = 1

function Invoke-Mcp {
    param(
        [string]$Method,
        [hashtable]$Params = @{},
        [string]$Label
    )
    $script:id++
    $payload = @{
        jsonrpc = "2.0"
        id = $script:id
        method = $Method
        params = $Params
    } | ConvertTo-Json -Compress -Depth 12

    $tmpIn = Join-Path $OutDir ("req-{0}.json" -f $script:id)
    $tmpOut = Join-Path $OutDir ("res-{0}.json" -f $script:id)
    [System.IO.File]::WriteAllText($tmpIn, $payload, [System.Text.UTF8Encoding]::new($false))

    $args = @(
        "-sS", "-m", "120",
        "-H", "Authorization: Bearer $Token",
        "-H", "Content-Type: application/json",
        "-H", "Accept: application/json, text/event-stream",
        "--data-binary", "@$tmpIn",
        "-o", $tmpOut,
        "-w", "%{http_code}",
        "$BaseUrl/mcp"
    )
    $httpCode = & curl.exe @args
    $body = if (Test-Path $tmpOut) { Get-Content -Raw -Path $tmpOut } else { "" }
    $ok = $false
    $summary = ""
    try {
        $json = $body | ConvertFrom-Json
        if ($null -ne $json.error) {
            $summary = "rpc_error: $($json.error | ConvertTo-Json -Compress)"
        } elseif ($Method -eq "tools/call") {
            $isError = [bool]$json.result.isError
            $text = ""
            if ($json.result.content) {
                $text = ($json.result.content | ForEach-Object { $_.text }) -join "`n"
            }
            $ok = -not $isError
            $summary = if ($text.Length -gt 500) { $text.Substring(0, 500) + "..." } else { $text }
            if (-not $ok -and [string]::IsNullOrWhiteSpace($summary)) {
                $summary = ($json.result | ConvertTo-Json -Compress -Depth 6)
            }
        } else {
            $ok = $true
            $summary = ($json.result | ConvertTo-Json -Compress -Depth 6)
            if ($summary.Length -gt 500) { $summary = $summary.Substring(0, 500) + "..." }
        }
    } catch {
        $summary = "parse_fail http=$httpCode body=$body"
    }
    if ("$httpCode" -ne "200") {
        $ok = $false
        $summary = "http=$httpCode $summary"
    }
    $row = [pscustomobject]@{
        label = $Label
        method = $Method
        ok = $ok
        http = $httpCode
        summary = $summary
    }
    $results.Add($row)
    Write-Host ("[{0}] {1}" -f ($(if ($ok) { "PASS" } else { "FAIL" }), $Label))
    Write-Host ("  " + $summary.Replace("`n", " | ").Substring(0, [Math]::Min(240, $summary.Length)))
    return $row
}

function Call-Tool {
    param(
        [string]$Name,
        [hashtable]$Arguments = @{},
        [string]$Label = $Name
    )
    return Invoke-Mcp -Method "tools/call" -Params @{
        name = $Name
        arguments = $Arguments
    } -Label $Label
}

# 1) initialize + tools/list
Invoke-Mcp -Method "initialize" -Params @{
    protocolVersion = "2024-11-05"
    capabilities = @{}
    clientInfo = @{ name = "p0-smoke"; version = "1" }
} -Label "initialize" | Out-Null

$list = Invoke-Mcp -Method "tools/list" -Params @{} -Label "tools/list"
$toolNames = @()
try {
    $listJson = Get-Content -Raw (Join-Path $OutDir ("res-{0}.json" -f $id)) | ConvertFrom-Json
    $toolNames = @($listJson.result.tools | ForEach-Object { $_.name })
} catch {}
$need = @("list_tools", "assist_status", "file_read")
$missing = @($need | Where-Object { $_ -notin $toolNames })
$results.Add([pscustomobject]@{
    label = "tools/list has list_tools/assist_status/file_read"
    method = "check"
    ok = ($missing.Count -eq 0)
    http = ""
    summary = if ($missing.Count -eq 0) { "tools=$($toolNames.Count)" } else { "missing=$($missing -join ','); total=$($toolNames.Count)" }
}) | Out-Null
Write-Host ("[{0}] tools/list required names" -f ($(if ($missing.Count -eq 0) { "PASS" } else { "FAIL" })))

# file write/read
Call-Tool -Name "file_write" -Arguments @{
    path = "p0/smoke.txt"
    content = "p0-hello`nline2`ncol1,col2`n"
} -Label "file_write" | Out-Null
Call-Tool -Name "file_read" -Arguments @{
    path = "p0/smoke.txt"
    mode = "lines"
} -Label "file_read lines" | Out-Null
Call-Tool -Name "file_read" -Arguments @{
    path = "p0/smoke.txt"
    mode = "columns"
} -Label "file_read columns" | Out-Null

# apps
Call-Tool -Name "app_list" -Arguments @{} -Label "app_list" | Out-Null
Call-Tool -Name "app_info" -Arguments @{ package_name = "com.clawdroid.app.debug" } -Label "app_info" | Out-Null
Call-Tool -Name "app_launch" -Arguments @{ package_name = "com.android.settings" } -Label "app_launch settings" | Out-Null
Start-Sleep -Seconds 2
Call-Tool -Name "app_stop" -Arguments @{ package_name = "com.android.settings" } -Label "app_stop settings" | Out-Null

# download (small public file)
Call-Tool -Name "download_start" -Arguments @{
    url = "https://httpbin.org/bytes/2048"
    filename = "p0-dl.bin"
    threads = 2
} -Label "download_start" | Out-Null
Start-Sleep -Seconds 3
Call-Tool -Name "download_status" -Arguments @{ id = "latest" } -Label "download_status" | Out-Null
Call-Tool -Name "download_verify" -Arguments @{ id = "latest" } -Label "download_verify" | Out-Null

# notifications / web / sandbox
Call-Tool -Name "notification_list" -Arguments @{} -Label "notification_list" | Out-Null
Call-Tool -Name "web_preview" -Arguments @{ url = "https://example.com" } -Label "web_preview" | Out-Null
Call-Tool -Name "web_search" -Arguments @{ query = "Android Magisk" } -Label "web_search" | Out-Null
Call-Tool -Name "sandbox_shell" -Arguments @{ command = "pwd" } -Label "sandbox_shell pwd" | Out-Null
Call-Tool -Name "sandbox_shell" -Arguments @{ command = "echo hello" } -Label "sandbox_shell echo" | Out-Null
Call-Tool -Name "sandbox_shell" -Arguments @{ command = "cat ../x" } -Label "sandbox_shell reject ../x" | Out-Null

# hardware / sensors / gpu
Call-Tool -Name "camera_capture" -Arguments @{} -Label "camera_capture" | Out-Null
Call-Tool -Name "camera_record" -Arguments @{ duration_sec = 1 } -Label "camera_record" | Out-Null
Call-Tool -Name "sensor_read" -Arguments @{ op = "list" } -Label "sensor_read list" | Out-Null
Call-Tool -Name "sensor_read" -Arguments @{ op = "read"; type = "accelerometer" } -Label "sensor_read accel" | Out-Null
Call-Tool -Name "gpu_npu_probe" -Arguments @{} -Label "gpu_npu_probe" | Out-Null

# ftp path reject
Call-Tool -Name "ftp_transfer" -Arguments @{
    protocol = "ftp"
    op = "list"
    host = "127.0.0.1"
    port = 21
    local_path = "/sdcard/not-sandbox"
} -Label "ftp_transfer reject outside sandbox" | Out-Null

# shizuku / assist / events / list_tools
Call-Tool -Name "shizuku_status" -Arguments @{} -Label "shizuku_status" | Out-Null
Call-Tool -Name "assist_status" -Arguments @{} -Label "assist_status" | Out-Null
Call-Tool -Name "list_tools" -Arguments @{} -Label "list_tools" | Out-Null
Call-Tool -Name "subscribe_events" -Arguments @{ op = "start"; events = @("capability_changed"); duration_ms = 1500 } -Label "subscribe_events start" | Out-Null
Call-Tool -Name "subscribe_events" -Arguments @{ op = "stop" } -Label "subscribe_events stop" | Out-Null
Call-Tool -Name "get_capabilities" -Arguments @{} -Label "get_capabilities" | Out-Null

$reportPath = Join-Path $OutDir "smoke-report.json"
$results | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding utf8
$pass = @($results | Where-Object { $_.ok }).Count
$fail = @($results | Where-Object { -not $_.ok }).Count
Write-Host ""
Write-Host "PASS=$pass FAIL=$fail report=$reportPath"
exit $(if ($fail -gt 0) { 1 } else { 0 })
