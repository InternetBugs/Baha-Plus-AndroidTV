param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') |
    Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
$sdkRoot = ($sdkLine -replace '^sdk\.dir=', '').Replace('\\', '\').Replace('\:', ':')
if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-36\android.jar'))) {
    throw 'Configure the existing Android SDK in local.properties first.'
}
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$cachedGradle = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.14.3-bin') `
    -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
$command = if ($cachedGradle) { $cachedGradle.FullName } else { Join-Path $projectRoot 'gradlew.bat' }
$arguments = @('-p', $projectRoot, '--no-daemon', '--console=plain', '-Pkotlin.compiler.execution.strategy=in-process',
    'testNativeApiUnitTest', 'lintNativeApi', 'assembleNativeApi')
if ($Offline) { $arguments += '--offline' }
& $command @arguments
if ($LASTEXITCODE -ne 0) { throw 'Native API experiment checks/build failed.' }
$destination = Join-Path $projectRoot 'artifacts\BahaPlusTV-native-api-debug.apk'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\nativeApi\app-nativeApi.apk') -Destination $destination -Force
$stream = [IO.File]::OpenRead($destination)
try {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    $hash = (-join ($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }))
} finally {
    if ($sha256) { $sha256.Dispose() }
    $stream.Dispose()
}
"$hash  BahaPlusTV-native-api-debug.apk" | Set-Content -LiteralPath "$destination.sha256" -Encoding ascii
Write-Output "APK: $destination"
Write-Output "SHA-256: $hash"
