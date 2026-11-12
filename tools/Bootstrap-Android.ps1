param([switch]$AcceptSdkLicenses)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolsRoot = Join-Path $projectRoot '.tools'
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $toolsRoot 'android-user'
$platformReady = Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-36\android.jar')
$buildToolsReady = Test-Path -LiteralPath (Join-Path $sdkRoot 'build-tools\35.0.0\aapt2.exe')
if (-not ($platformReady -and $buildToolsReady)) {
    $sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $sdkManager)) {
        $archive = Join-Path $toolsRoot 'commandlinetools-win.zip'
        $uri = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
        Invoke-WebRequest -Uri $uri -OutFile $archive
        $expected = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
            throw 'Android command line tools checksum mismatch.'
        }
        $unpack = Join-Path $toolsRoot 'sdk-unpack'
        Expand-Archive -LiteralPath $archive -DestinationPath $unpack -Force
        New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot 'cmdline-tools\latest') | Out-Null
        Copy-Item -Path (Join-Path $unpack 'cmdline-tools\*') -Destination (Join-Path $sdkRoot 'cmdline-tools\latest') -Recurse -Force
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
