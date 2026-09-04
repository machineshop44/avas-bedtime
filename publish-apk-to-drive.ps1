# Copy the latest Ava Bedtime APK into Google Drive\apks.
# Keeps ONLY the current versioned file for this app:
#   AvaBedtime-0.6.1(43).apk
# Deletes any older AvaBedtime-*.apk (including leftover *-latest.apk).
# Does not create a -latest alias.
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
    $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
    if (-not (Test-Path $env:JAVA_HOME)) {
        $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    }
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

$destName = "$Prefix$versionName-$code.apk"
$destPath = Join-Path $DriveApks $destName

# Also accept legacy "name(code).apk" as the current file so we don't delete-then-copy races.
$legacyName = "$Prefix$versionName($code).apk"

# Remove every other AvaBedtime APK first (old versions + any -latest aliases).
$removed = @()
Get-ChildItem -LiteralPath $DriveApks -Filter "AvaBedtime*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $destName } |
    ForEach-Object {
        $removed += $_.Name
        Remove-Item -LiteralPath $_.FullName -Force
    }

Copy-Item -LiteralPath $ApkSource -Destination $destPath -Force

# Zip companion — Android Drive often blocks raw .apk download/open.
$zipName = "$Prefix$versionName-$code.zip"
$zipPath = Join-Path $DriveApks $zipName
$stagingApk = Join-Path $env:TEMP $destName
Copy-Item -LiteralPath $ApkSource -Destination $stagingApk -Force
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -LiteralPath $stagingApk -DestinationPath $zipPath -Force
Remove-Item -LiteralPath $stagingApk -Force -ErrorAction SilentlyContinue

Get-ChildItem -LiteralPath $DriveApks -Filter "AvaBedtime*.zip" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $zipName } |
    ForEach-Object {
        $removed += $_.Name
        Remove-Item -LiteralPath $_.FullName -Force
    }

Write-Host "Drive apks (Ava): $destPath"
Write-Host "Drive zip (Ava):  $zipPath"
if ($removed.Count -gt 0) {
    Write-Host "Removed older Ava files: $($removed -join ', ')"
}
