param(
    [string]$DeviceIp = "192.168.1.105:40907",
    [int]$Lines = 100
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect $DeviceIp
& $adb -s $DeviceIp logcat -d -t $Lines -s RivuletPlayback:V RivuletEngine:V RivuletServer:V RivuletStream:V RivuletDB:V AndroidRuntime:E
