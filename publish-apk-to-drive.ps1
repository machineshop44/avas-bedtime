# Copy the latest Ava Bedtime APK into Google Drive\apks.
# Keeps only the current versioned APK for this app (no zip/bin, no subfolders).
#
# Ships release (non-debuggable) APKs — same debug signing key so phone↔tablet
# updates still install over each other. Debuggable builds trip Drive / Play Protect.
#
# IMPORTANT: Do NOT unzip/rezip the APK. Re-packing with .NET ZipArchive compresses
# resources.arsc (DEFLATED). Working sideload APKs keep resources.arsc STORED;
# Package Installer / Files / Nearby Share often reject compressed resources.arsc.
# adb install is more forgiving, which hid this bug.
#
# Usage:
#   .\publish-apk-to-drive.ps1
#   .\publish-apk-to-drive.ps1 -Build

param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$DriveApks = "G:\My Drive\apks"
$ApkSource = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
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
    & .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "assembleRelease failed"
    }
}

if (-not (Test-Path -LiteralPath $ApkSource)) {
    throw "APK not found: $ApkSource (build first with assembleRelease)"
}

$buildToolsDir = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$apksigner = Join-Path $buildToolsDir "apksigner.bat"

function Test-ResourcesArscStored([string]$apkPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        $arsc = $z.Entries | Where-Object { $_.FullName -eq "resources.arsc" } | Select-Object -First 1
        if (-not $arsc) { throw "APK missing resources.arsc" }
        return ($arsc.CompressedLength -eq $arsc.Length)
    } finally {
        $z.Dispose()
    }
}

if (-not (Test-ResourcesArscStored $ApkSource)) {
    throw "resources.arsc is compressed (DEFLATED). Sideload install will fail. Do not re-zip the APK."
}

if (Test-Path $apksigner) {
    Write-Host "=== apksigner verify ==="
    & $apksigner verify -v --print-certs $ApkSource 2>&1 |
        Select-String "Verified using|certificate DN|Error|DOES NOT VERIFY|Number of signers"
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner verify failed"
    }
}

$gradleText = Get-Content -LiteralPath $GradleFile -Raw
if ($gradleText -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw "Could not read versionName from app\build.gradle.kts"
}
$versionName = $Matches[1]
$code = if ($gradleText -match 'versionCode\s*=\s*(\d+)') { $Matches[1] } else { "0" }

$destName = "$Prefix$versionName($code).apk"
$destPath = Join-Path $DriveApks $destName

$removed = @()
Get-ChildItem -LiteralPath $DriveApks -Filter "AvaBedtime*" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $destName } |
    ForEach-Object {
        $removed += $_.Name
        Remove-Item -LiteralPath $_.FullName -Force
    }

# Byte-for-byte copy only - never modify zip structure.
Copy-Item -LiteralPath $ApkSource -Destination $destPath -Force

if (-not (Test-ResourcesArscStored $destPath)) {
    throw "Drive copy has compressed resources.arsc - aborting"
}

Write-Host "resources.arsc: STORED (ok for Package Installer)"
Write-Host "Drive apks (Ava release): $destPath"
if ($removed.Count -gt 0) {
    Write-Host "Removed older Ava files: $($removed -join ', ')"
}
