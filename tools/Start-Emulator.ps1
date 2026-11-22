param(
    [string]$Avd = 'T3_AndroidTV_API30',
    [int]$Port = 5554,
    [switch]$Windowed,
    [switch]$NoProxy,
    [int]$TimeoutSeconds = 300
)
$ErrorActionPreference = 'Stop'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
    throw 'Set ANDROID_HOME to the installed Android SDK before starting the emulator.'
}
$emulator = Join-Path $sdkRoot 'emulator\emulator.exe'
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
foreach ($tool in $emulator, $adb) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing $tool. Install the emulator and platform-tools in $sdkRoot." }
}
$avdIni = Join-Path $env:USERPROFILE ".android\avd\$Avd.ini"
if (-not (Test-Path -LiteralPath $avdIni)) {
    throw "AVD $Avd not found. List the global AVDs with `"$emulator -list-avds`" and pass -Avd."
}
$serial = "emulator-$Port"
$tcpPort = $Port + 1
$consolePort = $Port

function Get-HostProxy {
    foreach ($name in 'https_proxy', 'HTTPS_PROXY', 'http_proxy', 'HTTP_PROXY') {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value) { return $value }
    }
    $settings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
    if (-not $settings -or $settings.ProxyEnable -ne 1 -or -not $settings.ProxyServer) { return $null }
    $server = $settings.ProxyServer
    if ($server -match '=') {
        $server = ($server -split ';' | Where-Object { $_ -match '^(https|http)=' } | Select-Object -First 1) -replace '^[^=]+=', ''
    }
    if ($server -match '://') { return $server }
    return "http://$server"
}

function Invoke-Guest([string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $adb -s $serial @arguments 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
    ($output | ForEach-Object { "$_" }) -join "`n"
}

if ((Invoke-Guest @('get-state')).Trim() -eq 'device') {
    $running = ((Invoke-Guest @('emu', 'avd', 'name')) -split "`n")[0].Trim()
    Write-Output "$serial is already running (AVD $running); leaving it as is."
    exit 0
}

$proxy = if ($NoProxy) { $null } else { Get-HostProxy }
$arguments = @('-avd', $Avd, '-port', "$Port", '-no-audio', '-no-boot-anim', '-no-snapshot',
    '-gpu', 'swiftshader', '-memory', '2048', '-cores', '4')
if (-not $Windowed) { $arguments += '-no-window' }
if ($proxy) { $arguments += @('-http-proxy', $proxy) }
$process = Start-Process -FilePath $emulator -ArgumentList $arguments -PassThru
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if ($process.HasExited) { throw "Emulator exited with code $($process.ExitCode)." }
    if ((Invoke-Guest @('shell', 'getprop', 'sys.boot_completed')).Trim() -eq '1') { break }
    Start-Sleep -Seconds 3
}
if ((Invoke-Guest @('shell', 'getprop', 'sys.boot_completed')).Trim() -ne '1') {
    throw "Emulator $serial did not finish booting within $TimeoutSeconds seconds."
}

# The AVD can boot with wifi disassociated, which leaves the guest without a default route.
if ((Invoke-Guest @('shell', 'cmd', 'wifi', 'status')) -notmatch 'connected to') {
    Invoke-Guest @('shell', 'cmd', 'wifi', 'connect-network', 'AndroidWifi', 'open') | Out-Null
}
$wifiDeadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $wifiDeadline) {
    if ((Invoke-Guest @('shell', 'dumpsys', 'connectivity')) -match 'VALIDATED') { break }
    Start-Sleep -Seconds 3
}
if ((Invoke-Guest @('shell', 'dumpsys', 'connectivity')) -notmatch 'VALIDATED') {
    throw "Guest network on $serial is not validated; check the host network and proxy."
}

# -http-proxy leaves the value in AVD userdata, so clear it when running without a proxy.
if ($proxy -and $proxy -match ':(\d+)') {
    $guestProxyValue = "10.0.2.2:$($Matches[1])"
} else {
    $guestProxyValue = ':0'
}
Invoke-Guest @('shell', 'settings', 'put', 'global', 'http_proxy', $guestProxyValue) | Out-Null
$guestProxy = (Invoke-Guest @('shell', 'settings', 'get', 'global', 'http_proxy')).Trim()
$summary = "Emulator ready: $serial (AVD $Avd, console $consolePort, adb $tcpPort)"
if ($guestProxy -and $guestProxy -ne 'null' -and $guestProxy -ne ':0') {
    $summary += ", host proxy $guestProxy"
} else {
    $summary += ', guest proxy cleared'
}
Write-Output $summary
Write-Output "Next: & `"$adb`" -s $serial install -r artifacts/BahaPlusTV-<version>-debug.apk"
