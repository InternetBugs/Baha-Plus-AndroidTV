param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$propertiesPath = Join-Path $projectRoot 'local.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    throw 'Android SDK is not configured. Set sdk.dir in local.properties or run tools/Bootstrap-Android.ps1.'
}
$sdkProperty = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
$sdkRoot = ($sdkProperty -replace '^sdk\.dir=', '').Replace('\\', '\').Replace('\:', ':')
if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-36\android.jar'))) {
    throw 'Configured SDK is missing platforms/android-36/android.jar.'
}
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
$readOnlyCache = Join-Path $env:USERPROFILE '.gradle\caches'
if (Test-Path -LiteralPath (Join-Path $readOnlyCache 'modules-2')) { $env:GRADLE_RO_DEP_CACHE = $readOnlyCache }
$cachedGradleRoot = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.14.3-bin'
$cachedGradle = Get-ChildItem -LiteralPath $cachedGradleRoot -Filter 'gradle.bat' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
$command = if ($cachedGradle) { $cachedGradle.FullName } else { Join-Path $projectRoot 'gradlew.bat' }
$arguments = @('-p', $projectRoot, '--no-daemon', '--console=plain', 'testDebugUnitTest', 'lintDebug', 'assembleDebug')
if ($Offline) { $arguments += '--offline' }
& $command @arguments
if ($LASTEXITCODE -ne 0) { throw 'Android checks/build failed.' }
$artifactDirectory = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
$artifact = Join-Path $artifactDirectory 'BahaPlusTV-0.1.0-debug.apk'
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $artifact -Force
$checksum = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
"$checksum  BahaPlusTV-0.1.0-debug.apk" | Set-Content -LiteralPath "$artifact.sha256" -Encoding ascii
Write-Output "APK: $artifact"
Write-Output "SHA-256: $checksum"
