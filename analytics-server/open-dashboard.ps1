$ErrorActionPreference = 'Stop'
$url = 'http://127.0.0.1:8786'

function Test-Dashboard {
    try {
        return (Invoke-RestMethod -Uri "$url/v1/usb" -TimeoutSec 1 -UseBasicParsing).app -eq 'MyLifeAnalyticsUSB'
    } catch {
        return $false
    }
}

if (-not (Test-Dashboard)) {
    $python = (Get-Command python.exe).Source
    $data = Join-Path $PSScriptRoot 'data'
    New-Item -ItemType Directory -Path $data -Force | Out-Null
    $log = Join-Path $data 'launcher.log'
    $process = Start-Process $python -WorkingDirectory $PSScriptRoot -ArgumentList '-B', 'server.py' `
        -WindowStyle Hidden -RedirectStandardError $log -PassThru
    $ready = $false
    for ($i = 0; $i -lt 25; $i++) {
        if (Test-Dashboard) { $ready = $true; break }
        if ($process.HasExited) { break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) { throw "MyLife Analytics could not start. See $log." }
}

try {
    Invoke-RestMethod -Uri "$url/v1/usb/start" -Method Post -Headers @{ Origin = $url } -UseBasicParsing | Out-Null
} catch {
    # The dashboard exposes the latest ADB startup error and still serves local history.
    Write-Warning $_.Exception.Message
}
Start-Process "$url/"
