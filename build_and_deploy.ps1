#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Собирает Carnelia VPN APK (arm64-v8a + x86_64) и опционально устанавливает на телефон.
.USAGE
    .\build_and_deploy.ps1                          # Собрать обе арки + установить
    .\build_and_deploy.ps1 -BuildOnly               # Только собрать
    .\build_and_deploy.ps1 -DeployOnly              # Только установить из releases/android/
    .\build_and_deploy.ps1 -Brand null              # Собрать null vpn вместо carnelia
    .\build_and_deploy.ps1 -Device XXXXXXXX         # Указать конкретное ADB-устройство
    .\build_and_deploy.ps1 -GradleHome D:\gradle_cache  # Gradle кеш на другом диске
#>

param(
    [switch]$BuildOnly,
    [switch]$DeployOnly,
    [ValidateSet("carnelia","null","both")]
    [string]$Brand = "carnelia",
    [string]$Device = "",
    [string]$GradleHome = "D:\gradle_cache"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ROOT        = "D:\carneliavpn\android"
$RELEASES    = "D:\carneliavpn\releases\android"
$ADB         = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$GRADLE      = "$ROOT\gradlew.bat"

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-OK([string]$msg)   { Write-Host "    OK: $msg" -ForegroundColor Green }
function Write-Warn([string]$msg) { Write-Host "    WARN: $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "    FAIL: $msg" -ForegroundColor Red; exit 1 }

# ── Проверки ──────────────────────────────────────────────────────────────
if (-not (Test-Path $GRADLE))    { Write-Fail "gradlew.bat не найден: $GRADLE" }
if (-not (Test-Path $RELEASES))  { New-Item -ItemType Directory -Path $RELEASES -Force | Out-Null }

# Выбираем ADB
if (-not (Test-Path $ADB)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $ADB = $found.Source } else { Write-Warn "ADB не найден — установка пропущена" }
}

# Gradle HOME на D диске (если C переполнен)
if ($GradleHome) {
    New-Item -ItemType Directory -Path $GradleHome -Force | Out-Null
    $env:GRADLE_USER_HOME = $GradleHome
    Write-Host "    Gradle cache: $GradleHome" -ForegroundColor DarkGray
}

# ── Определяем таски для сборки ───────────────────────────────────────────
$tasks = @()
if ($Brand -eq "both" -or $Brand -eq "carnelia") { $tasks += "assembleCarneliaVanillaRelease" }
if ($Brand -eq "both" -or $Brand -eq "null")     { $tasks += "assembleNullVanillaRelease" }

# ── СБОРКА ────────────────────────────────────────────────────────────────
if (-not $DeployOnly) {
    foreach ($task in $tasks) {
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
}

# ── Сбор APK в releases/android/ ─────────────────────────────────────────
Write-Step "Копирование APK в releases/android/"

# Удаляем заглушку
Remove-Item "$RELEASES\.gitkeep" -ErrorAction SilentlyContinue

$copiedApks = @()

# Ищем все APK в outputs (обе арки, все бренды)
$allApks = Get-ChildItem -Recurse -Path "$ROOT\app\build\outputs\apk" -Filter "*.apk" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*unsigned*" -and $_.Name -notlike "*androidTest*" } |
           Sort-Object LastWriteTime -Descending

foreach ($apk in $allApks) {
    # Формируем имя: carnelia-vpn-2.4.1-arm64.apk
    $arch = if ($apk.FullName -like "*arm64*") { "arm64" }
            elseif ($apk.FullName -like "*x86_64*") { "x86_64" }
            else { "universal" }

    $brandTag = if ($apk.FullName -like "*carnelia*") { "carnelia-vpn" } else { "null-vpn" }
    $destName = "$brandTag-2.4.1-$arch.apk"
    $destPath = "$RELEASES\$destName"

    Copy-Item $apk.FullName $destPath -Force
    $sizeMb = [math]::Round($apk.Length / 1MB, 1)
    Write-OK "$destName  ($sizeMb МБ)"
    $copiedApks += $destPath
}

if ($copiedApks.Count -eq 0) {
    Write-Fail "APK не найдены в $ROOT\app\build\outputs\apk"
}

Write-Host "`n    Итого APK: $($copiedApks.Count)" -ForegroundColor White
Write-Host "    Папка: $RELEASES" -ForegroundColor DarkGray

# ── ADB УСТАНОВКА ─────────────────────────────────────────────────────────
if ($BuildOnly) {
    Write-Host "`nGot it: только сборка." -ForegroundColor Yellow
    exit 0
}

if (-not (Test-Path $ADB)) {
    Write-Warn "ADB недоступен — пропускаем установку"
    exit 0
}

Write-Step "ADB: Поиск устройств"
$devices = & $ADB devices 2>&1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Warn "Телефон не подключён или ADB не активирован"
    Write-Host "    APK готовы в: $RELEASES" -ForegroundColor DarkGray
    exit 0
}

$target = if ($Device) { $Device } else { ($devices[0] -split "\t")[0] }
Write-OK "Устройство: $target"

# Устанавливаем arm64 APK (приоритет) или первый найденный
$installApk = $copiedApks | Where-Object { $_ -like "*arm64*" } | Select-Object -First 1
if (-not $installApk) { $installApk = $copiedApks | Select-Object -First 1 }

Write-Step "ADB: Установка $([System.IO.Path]::GetFileName($installApk))"
& $ADB -s $target install -r $installApk
if ($LASTEXITCODE -ne 0) { Write-Fail "Установка не удалась" }
Write-OK "Установлено на $target"

Write-Step "ADB: Запуск"
& $ADB -s $target shell am start -n "com.carnelia.vpn/.MainActivity"
Write-OK "Приложение запущено"
