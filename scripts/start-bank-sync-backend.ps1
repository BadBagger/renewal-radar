param(
    [int]$Port = 8788,
    [switch]$ForceMock
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot "backend"
$logsDir = Join-Path $backendDir "logs"
$toolsDir = Join-Path $repoRoot ".tools"
$cloudflared = Join-Path $toolsDir "cloudflared.exe"

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required to run the Renewal Radar bank sync backend."
}
if (-not (Test-Path $cloudflared)) {
    Write-Host "Downloading local cloudflared tunnel helper..."
    $downloadUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    curl.exe -L $downloadUrl -o $cloudflared
}
if (-not (Test-Path $cloudflared)) {
    throw "cloudflared.exe is required to start the HTTPS tunnel."
}

$clientId = [Environment]::GetEnvironmentVariable("PLAID_CLIENT_ID", "User")
if (-not $clientId) { $clientId = [Environment]::GetEnvironmentVariable("PLAID_CLIENT_ID", "Machine") }
$secret = [Environment]::GetEnvironmentVariable("PLAID_SECRET", "User")
if (-not $secret) { $secret = [Environment]::GetEnvironmentVariable("PLAID_SECRET", "Machine") }
$mockMode = if ($ForceMock -or -not $clientId -or -not $secret) { "true" } else { "false" }

$env:PORT = "$Port"
$env:PLAID_ANDROID_PACKAGE_NAME = "com.renewalradar.app"
$env:PLAID_MOCK_MODE = $mockMode
$env:SEED_MOCK_DATA = "true"

$backendLog = Join-Path $logsDir "backend.log"
$backendErrorLog = Join-Path $logsDir "backend-error.log"
$tunnelLog = Join-Path $logsDir "tunnel.log"
$tunnelErrorLog = Join-Path $logsDir "tunnel-error.log"
Remove-Item -LiteralPath $tunnelLog, $tunnelErrorLog -ErrorAction SilentlyContinue

$backend = $null
$existingBackendHealthy = $false
try {
    $existingHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 3
    $existingBackendHealthy = $existingHealth.ok -eq $true
} catch {
    $existingBackendHealthy = $false
}

if (-not $existingBackendHealthy) {
    $backend = Start-Process -FilePath "node" `
        -ArgumentList "src/server.js" `
        -WorkingDirectory $backendDir `
        -RedirectStandardOutput $backendLog `
        -RedirectStandardError $backendErrorLog `
        -WindowStyle Hidden `
        -PassThru

    Start-Sleep -Seconds 2
}

$health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 8
if (-not $health.ok) {
    throw "Backend did not pass /health."
}

$tunnelArgs = @("tunnel", "--url", "http://127.0.0.1:$Port", "--no-autoupdate")
$tunnel = Start-Process -FilePath $cloudflared `
    -ArgumentList $tunnelArgs `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput $tunnelLog `
    -RedirectStandardError $tunnelErrorLog `
    -WindowStyle Hidden `
    -PassThru

$url = $null
for ($attempt = 0; $attempt -lt 30 -and -not $url; $attempt++) {
    Start-Sleep -Seconds 1
    $logText = @(
        Get-Content $tunnelLog -ErrorAction SilentlyContinue
        Get-Content $tunnelErrorLog -ErrorAction SilentlyContinue
    ) -join "`n"
    $match = [regex]::Match($logText, "https://[a-zA-Z0-9-]+\.trycloudflare\.com")
    if ($match.Success) {
        $url = $match.Value
    }
}
if (-not $url) {
    throw "Cloudflare tunnel did not report a public URL. Check $tunnelErrorLog."
}

$env:BACKEND_PUBLIC_BASE_URL = $url
$urlFile = Join-Path $repoRoot "bank-backend-url.txt"
Set-Content -Path $urlFile -Value $url -Encoding ASCII

Write-Host "Renewal Radar bank backend is running."
if ($backend) {
    Write-Host "Backend PID: $($backend.Id)"
} else {
    Write-Host "Backend PID: already running on port $Port"
}
Write-Host "Tunnel PID: $($tunnel.Id)"
Write-Host "Backend URL: $url"
Write-Host "Mock mode: $mockMode"
Write-Host "The Android app default backend URL is $url."
Write-Host "Logs:"
Write-Host "  $backendLog"
Write-Host "  $backendErrorLog"
Write-Host "  $tunnelLog"
Write-Host "  $tunnelErrorLog"
