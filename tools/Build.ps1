param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules\esbuild\package.json')) -or
    -not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules\acorn\package.json')) -or
    -not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules\@babel\standalone\package.json')) -or
    -not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules\core-js\package.json'))) {
    throw 'WebView adapter build dependencies are missing. Run npm ci --ignore-scripts first.'
}
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
$cachedGradleRoot = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.14.3-bin'
$cachedGradle = Get-ChildItem -LiteralPath $cachedGradleRoot -Filter 'gradle.bat' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
$command = if ($cachedGradle) { $cachedGradle.FullName } else { Join-Path $projectRoot 'gradlew.bat' }
$arguments = @('-p', $projectRoot, '--no-daemon', '--console=plain', 'testDebugUnitTest', 'lintDebug', 'assembleDebug')
if ($Offline) { $arguments += '--offline' }
& $command @arguments
if ($LASTEXITCODE -ne 0) { throw 'Android checks/build failed.' }
$artifactDirectory = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
$builtApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$aapt2 = Join-Path $sdkRoot 'build-tools\35.0.0\aapt2.exe'
$badging = (& $aapt2 dump badging $builtApk) -join "`n"
$versionName = if ($badging -match "versionName='([^']+)'") { $Matches[1] } else { throw 'Cannot read versionName from the debug APK.' }
$artifactName = "BahaPlusTV-$versionName-debug.apk"
$artifact = Join-Path $artifactDirectory $artifactName
Copy-Item -LiteralPath $builtApk -Destination $artifact -Force
$stream = [IO.File]::OpenRead($artifact)
try {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    $checksum = (-join ($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }))
} finally {
    if ($sha256) { $sha256.Dispose() }
    $stream.Dispose()
}
"$checksum  $artifactName" | Set-Content -LiteralPath "$artifact.sha256" -Encoding ascii
Write-Output "APK: $artifact"
Write-Output "SHA-256: $checksum"
