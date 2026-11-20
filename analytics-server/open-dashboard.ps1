$ErrorActionPreference = 'Stop'
$port = 8786
$usbPort = 8787
$forward = "127.0.0.1:${port}:127.0.0.1:${port}"
$usbForward = "127.0.0.1:${usbPort}:127.0.0.1:${usbPort}"
$existing = Get-CimInstance Win32_Process -Filter "Name='ssh.exe'" |
    Where-Object { $_.CommandLine -like "*$forward*$usbForward*sintence-dev*" } | Select-Object -First 1
if (-not $existing) {
    $process = Start-Process ssh.exe -WindowStyle Hidden -PassThru -ArgumentList @(
        '-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=30',
        '-L', $forward, '-L', $usbForward, 'sintence-dev'
    )
    for ($i = 0; $i -lt 25 -and -not $process.HasExited; $i++) {
        $tcp = [Net.Sockets.TcpClient]::new()
        try {
            $tcp.Connect('127.0.0.1', $port)
            break
        } catch {
            Start-Sleep -Milliseconds 200
        } finally {
            $tcp.Dispose()
        }
    }
    if ($process.HasExited -or $i -eq 25) { throw 'Could not open the MyLife SSH tunnel.' }
}

$adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $properties = Join-Path (Split-Path $PSScriptRoot) 'local.properties'
    $sdk = if (Test-Path -LiteralPath $properties) {
        $match = Select-String -LiteralPath $properties -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($match) { $match.Matches[0].Groups[1].Value.Replace('\:', ':').Replace('\\', '\') }
    }
    $candidates = @($sdk, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    $adb = $candidates | Where-Object { $_ } | ForEach-Object {
        Join-Path $_ 'platform-tools\adb.exe'
    } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if ($adb) {
    try { & $adb -d reverse 'tcp:8786' "tcp:$usbPort" 2>$null | Out-Null } catch { }
}

Start-Process "http://127.0.0.1:$port/"
