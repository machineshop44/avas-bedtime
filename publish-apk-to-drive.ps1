# Copy the latest Ava Bedtime APK into Google Drive\apks.
# Keeps only the current versioned file for this app (no -latest aliases).
#
# Usage:
#   .\publish-apk-to-drive.ps1
#   .\publish-apk-to-drive.ps1 -Build   # assembleDebug first

param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$DriveApks = "G:\My Drive\apks"
$ApkSource = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
$GradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
$Prefix = "AvaBedtime-"

if (-not (Test-Path "G:\My Drive")) {
    throw "Google Drive not available at G:\My Drive"
}

New-Item -ItemType Directory -Force -Path $DriveApks | Out-Null

if ($Build) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "assembleDebug failed"
    }
}

if (-not (Test-Path -LiteralPath $ApkSource)) {
    throw "APK not found: $ApkSource (build first)"
}

$gradleText = Get-Content -LiteralPath $GradleFile -Raw
if ($gradleText -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw "Could not read versionName from app\build.gradle.kts"
}
$versionName = $Matches[1]
$code = if ($gradleText -match 'versionCode\s*=\s*(\d+)') { $Matches[1] } else { "0" }

$destName = "$Prefix$versionName($code).apk"
$destPath = Join-Path $DriveApks $destName

Get-ChildItem -LiteralPath $DriveApks -Filter "$Prefix*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $destName } |
    Remove-Item -Force

Copy-Item -LiteralPath $ApkSource -Destination $destPath -Force

Write-Host "Drive apks (Ava): $destPath"
