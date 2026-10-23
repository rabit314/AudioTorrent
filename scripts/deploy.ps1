param(
    [string]$DeviceIp = "192.168.1.105:40907",
    [switch]$FollowLogs
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb.exe not found at $adb"
    exit 1
}

Write-Host "Connecting to $DeviceIp..."
& $adb connect $DeviceIp

Write-Host "Installing Rivulet.apk..."
& $adb -s $DeviceIp install -r Rivulet.apk

Write-Host "Launching Rivulet..."
& $adb -s $DeviceIp shell am start -n com.example.rivulet/.MainActivity

if ($FollowLogs) {
    Write-Host "Streaming logs (Ctrl+C to stop)..."
    & $adb -s $DeviceIp logcat -v time -s RivuletPlayback:V RivuletEngine:V RivuletServer:V RivuletStream:V RivuletDB:V AndroidRuntime:E
}
