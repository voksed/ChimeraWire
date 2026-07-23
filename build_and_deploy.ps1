#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Собирает Carnelia VPN (release APK, arm64-v8a + x86_64) и опционально ставит на телефон.
.USAGE
    .\build_and_deploy.ps1                 # Собрать + установить на подключённое устройство
    .\build_and_deploy.ps1 -BuildOnly      # Только собрать
    .\build_and_deploy.ps1 -DeployOnly     # Только установить последний собранный APK
    .\build_and_deploy.ps1 -Debug          # Собрать debug вместо release
    .\build_and_deploy.ps1 -Device XXXX    # Конкретное ADB-устройство
#>

param(
    [switch]$BuildOnly,
    [switch]$DeployOnly,
    [switch]$Debug,
    [string]$Device = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Пути вычисляются от расположения скрипта — работает на любой машине
$ROOT     = Join-Path $PSScriptRoot "android"
$RELEASES = Join-Path $PSScriptRoot "releases\android"
$GRADLE   = Join-Path $ROOT "gradlew.bat"
$ADB      = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$variant  = if ($Debug) { "Debug" } else { "Release" }
$task     = "assemble$variant"

function Write-Step([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-OK([string]$m)   { Write-Host "    OK: $m" -ForegroundColor Green }
function Write-Warn([string]$m) { Write-Host "    WARN: $m" -ForegroundColor Yellow }
function Write-Fail([string]$m) { Write-Host "    FAIL: $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $GRADLE))   { Write-Fail "gradlew.bat не найден: $GRADLE" }
if (-not (Test-Path $RELEASES)) { New-Item -ItemType Directory -Path $RELEASES -Force | Out-Null }

if (-not (Test-Path $ADB)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $ADB = $found.Source } else { Write-Warn "ADB не найден — установка будет пропущена" }
}

# ── СБОРКА ────────────────────────────────────────────────────────────────
if (-not $DeployOnly) {
    Write-Step "Сборка: $task"
    $t = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $ROOT
    try {
        & cmd.exe /c "gradlew.bat $task --no-daemon 2>&1"
        if ($LASTEXITCODE -ne 0) { Write-Fail "Gradle завершился с ошибкой (код $LASTEXITCODE)" }
    } finally { Pop-Location }
    $t.Stop()
    Write-OK "Собрано за $([int]$t.Elapsed.TotalSeconds) сек"
}

# ── Сбор APK в releases/android/ ─────────────────────────────────────────
Write-Step "Копирование APK в releases/android/"
Remove-Item "$RELEASES\.gitkeep" -ErrorAction SilentlyContinue

$copiedApks = @()
$outDir = Join-Path $ROOT "app\build\outputs\apk\$($variant.ToLower())"
$allApks = Get-ChildItem -Recurse -Path $outDir -Filter "*.apk" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*unsigned*" -and $_.Name -notlike "*androidTest*" } |
           Sort-Object LastWriteTime -Descending

foreach ($apk in $allApks) {
    $arch = if ($apk.FullName -like "*arm64*") { "arm64" }
            elseif ($apk.FullName -like "*x86_64*") { "x86_64" }
            else { "universal" }
    $destPath = Join-Path $RELEASES "carnelia-vpn-$arch.apk"
    Copy-Item $apk.FullName $destPath -Force
    $sizeMb = [math]::Round($apk.Length / 1MB, 1)
    Write-OK "carnelia-vpn-$arch.apk  ($sizeMb МБ)"
    $copiedApks += $destPath
}

if ($copiedApks.Count -eq 0) { Write-Fail "APK не найдены в $outDir" }
Write-Host "`n    Итого APK: $($copiedApks.Count)  ->  $RELEASES" -ForegroundColor White

# ── ADB УСТАНОВКА ─────────────────────────────────────────────────────────
if ($BuildOnly) { Write-Host "`nТолько сборка." -ForegroundColor Yellow; exit 0 }
if (-not (Test-Path $ADB)) { Write-Warn "ADB недоступен — пропускаем установку"; exit 0 }

Write-Step "ADB: поиск устройств"
$devices = & $ADB devices 2>&1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Warn "Устройство не подключено или ADB не активирован"
    Write-Host "    APK готовы в: $RELEASES" -ForegroundColor DarkGray
    exit 0
}

$target = if ($Device) { $Device } else { ($devices[0] -split "\t")[0] }
Write-OK "Устройство: $target"

$installApk = $copiedApks | Where-Object { $_ -like "*arm64*" } | Select-Object -First 1
if (-not $installApk) { $installApk = $copiedApks | Select-Object -First 1 }

Write-Step "ADB: установка $([System.IO.Path]::GetFileName($installApk))"
& $ADB -s $target install -r $installApk
if ($LASTEXITCODE -ne 0) { Write-Fail "Установка не удалась" }
Write-OK "Установлено на $target"

Write-Step "ADB: запуск"
& $ADB -s $target shell am start -n "com.carnelia.vpn/.MainActivity"
Write-OK "Приложение запущено"
