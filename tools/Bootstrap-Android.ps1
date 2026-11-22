param([switch]$AcceptSdkLicenses)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
    throw 'Set ANDROID_HOME to the installed Android SDK before bootstrapping.'
}
if (-not (Test-Path -LiteralPath $sdkRoot)) {
    throw "Android SDK not found at $sdkRoot."
}
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$platformReady = Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-36\android.jar')
$buildToolsReady = Test-Path -LiteralPath (Join-Path $sdkRoot 'build-tools\35.0.0\aapt2.exe')
if (-not ($platformReady -and $buildToolsReady)) {
    $sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $sdkManager)) {
        throw "Missing $sdkManager. Install the Android command line tools in $sdkRoot first."
    }
    if ($AcceptSdkLicenses) {
        1..50 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses
    } else {
        & $sdkManager "--sdk_root=$sdkRoot" --licenses
    }
    if ($LASTEXITCODE -ne 0) { throw 'SDK license setup failed.' }
    & $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-36' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed.' }
}
$escapedSdkRoot = $sdkRoot.Replace('\', '/').Replace(':', '\:')
"sdk.dir=$escapedSdkRoot" | Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Encoding utf8
Write-Output "Android SDK ready at $sdkRoot"
Write-Output 'Run tools/Build.ps1 to compile, test, lint and package the debug APK.'
