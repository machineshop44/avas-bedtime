# Stress-test Ava Bedtime on a connected device (debug APK).
# Hammers start / restart / stop and fails on FATAL / ANR / native crash.

param(
    [string]$Serial = "",
    [int]$RestartBursts = 40,
    [int]$SessionCycles = 12,
    [int]$MediaKeyBursts = 20
)

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$pkg = "com.avas.bedtime"
$svc = "$pkg/.session.BedtimeService"
$serialArgs = @()
if ($Serial) { $serialArgs = @("-s", $Serial) }

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Cmd)
    & $adb @serialArgs @Cmd
    return $LASTEXITCODE
}

function Get-AdbOut {
    param([Parameter(Mandatory)][string[]]$Cmd)
    return (& $adb @serialArgs @Cmd 2>&1 | Out-String)
}

function RequireDevice {
    $devices = Get-AdbOut -Cmd @("devices")
    if ($devices -notmatch "\tdevice\b") {
        throw "No device in 'device' state.`n$devices"
    }
    Write-Host $devices.Trim()
}

function AppVersion {
    $dump = Get-AdbOut -Cmd @("shell", "dumpsys", "package", $pkg)
    $code = ([regex]::Match($dump, "versionCode=(\d+)")).Groups[1].Value
    $name = ([regex]::Match($dump, "versionName=([^\s]+)")).Groups[1].Value
    return "$name ($code)"
}

function PidOf {
    $p = (Get-AdbOut -Cmd @("shell", "pidof", $pkg)).Trim()
    if ([string]::IsNullOrWhiteSpace($p)) { return $null }
    return ($p -split "\s+")[0]
}

function SendAction([string]$Action) {
    # Debug APK only: StressReceiver forwards to the non-exported BedtimeService.
    $debugAction = switch ($Action) {
        "com.avas.bedtime.START" { "com.avas.bedtime.DEBUG_START" }
        "com.avas.bedtime.STOP" { "com.avas.bedtime.DEBUG_STOP" }
        "com.avas.bedtime.RESTART" { "com.avas.bedtime.DEBUG_RESTART" }
        default { $Action }
    }
    Invoke-Adb -Cmd @(
        "shell", "am", "broadcast",
        "-a", $debugAction,
        "-n", "$pkg/.debug.StressReceiver"
    ) | Out-Null
}

function DispatchMedia([string]$Key) {
    Invoke-Adb -Cmd @("shell", "input", "keyevent", $Key) | Out-Null
}

function ClearLogs {
    Invoke-Adb -Cmd @("logcat", "-c") | Out-Null
}

function CollectCrashSignals {
    $log = Get-AdbOut -Cmd @(
        "logcat", "-d", "-v", "brief",
        "*:E", "AndroidRuntime:E", "ActivityManager:E", "libc:F"
    )
    $hits = @()
    foreach ($line in ($log -split "`r?`n")) {
        if ($line -match "FATAL EXCEPTION|ANR in $pkg|Fatal signal|Process $pkg.*has died|has died.*$pkg") {
            $hits += $line
        }
        if ($line -match $pkg -and $line -match "AndroidRuntime|FATAL|ANR") {
            $hits += $line
        }
    }
    return $hits | Select-Object -Unique
}

function AssertAlive([string]$Phase) {
    Start-Sleep -Milliseconds 400
    $pidNow = PidOf
    $crashes = CollectCrashSignals
    if ($crashes.Count -gt 0) {
        Write-Host ""
        Write-Host "--- crash log excerpt ---"
        $crashes | Select-Object -First 30 | ForEach-Object { Write-Host $_ }
        throw "FAIL [$Phase]: crash/ANR signals found"
    }
    if (-not $pidNow) {
        return $false
    }
    return $true
}

Write-Host "=== Ava Bedtime stress test ==="
RequireDevice
$ver = AppVersion
Write-Host "Installed: $ver"
if ($ver -notmatch "0\.6\.7") {
    Write-Warning "Expected 0.6.7; continuing anyway with $ver"
}

Invoke-Adb -Cmd @("shell", "am", "start", "-n", "$pkg/.MainActivity") | Out-Null
Start-Sleep -Seconds 2
ClearLogs

Write-Host ""
Write-Host "[1/3] Start session"
SendAction "com.avas.bedtime.START"
Start-Sleep -Seconds 3
if (-not (AssertAlive "start")) { throw "FAIL [start]: process not running after START" }
$pidStart = PidOf
Write-Host "  pid=$pidStart OK"

Write-Host ""
Write-Host "[2/3] Restart burst x$RestartBursts"
for ($i = 1; $i -le $RestartBursts; $i++) {
    SendAction "com.avas.bedtime.RESTART"
    if ($i % 10 -eq 0) {
        if (-not (AssertAlive "restart#$i")) { throw "FAIL [restart#$i]: process died" }
        Write-Host "  $i/$RestartBursts ok (pid=$(PidOf))"
    } else {
        Start-Sleep -Milliseconds 150
    }
}
if (-not (AssertAlive "restart-end")) { throw "FAIL [restart-end]: process died" }

Write-Host ""
Write-Host "[3/3] Start/Stop cycles x$SessionCycles"
for ($i = 1; $i -le $SessionCycles; $i++) {
    SendAction "com.avas.bedtime.STOP"
    Start-Sleep -Milliseconds 800
    CollectCrashSignals | Out-Null
    SendAction "com.avas.bedtime.START"
    Start-Sleep -Milliseconds 1200
    if (-not (AssertAlive "cycle#$i")) {
        SendAction "com.avas.bedtime.START"
        Start-Sleep -Seconds 2
        if (-not (AssertAlive "cycle#$i-retry")) {
            throw "FAIL [cycle#$i]: could not keep process alive"
        }
    }
    SendAction "com.avas.bedtime.RESTART"
    Start-Sleep -Milliseconds 300
    if ($i % 3 -eq 0) { Write-Host "  cycle $i/$SessionCycles ok" }
}

SendAction "com.avas.bedtime.STOP"
Start-Sleep -Seconds 1
$finalCrashes = CollectCrashSignals
if ($finalCrashes.Count -gt 0) {
    Write-Host ""
    Write-Host "--- crash log excerpt ---"
    $finalCrashes | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
    throw "FAIL [final]: crash/ANR signals found"
}

$runtime = Get-AdbOut -Cmd @("logcat", "-d", "-s", "AndroidRuntime:E")
if ($runtime -match $pkg -and $runtime -match "FATAL EXCEPTION") {
    throw "FAIL [final]: AndroidRuntime FATAL for $pkg"
}

Write-Host ""
Write-Host "PASS - $ver survived restart burst and $SessionCycles start/stop cycles"
