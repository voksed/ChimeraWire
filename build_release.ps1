#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Собирает все релизные артефакты CarneliaVPN и кладёт в releases/.
.DESCRIPTION
    Android (arm64-v8a + x86_64 — оба в одном прогоне через ABI splits):
      - releases/android/carnelia-vpn-2.4.1-arm64.apk
      - releases/android/carnelia-vpn-2.4.1-x86_64.apk

    Windows (x86_64 через MSYS2 MinGW64):
      - releases/windows/CarneliaVPN.exe  + Qt6Network.dll, xray.exe и т.д.

.USAGE
    .\build_release.ps1               # Собрать всё
    .\build_release.ps1 -AndroidOnly  # Только Android
    .\build_release.ps1 -DesktopOnly  # Только Desktop
    .\build_release.ps1 -GradleHome D:\gradle_cache  # Gradle кеш на другом диске
#>

param(
    [switch]$AndroidOnly,
    [switch]$DesktopOnly,
    [string]$GradleHome = "D:\gradle_cache"
)

$ErrorActionPreference = "Stop"
$root        = $PSScriptRoot
$ANDROID_DIR = "$root\android"
$DESKTOP_DIR = "$root\desktop"
$OUT_ANDROID = "$root\releases\android"
$OUT_WINDOWS = "$root\releases\windows"
$GRADLE      = "$ANDROID_DIR\gradlew.bat"
$MSYS2_BIN   = "C:\msys64\mingw64\bin"
$VERSION     = "2.4.1"

function Step([string]$m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function OK([string]$m)   { Write-Host "    OK: $m" -ForegroundColor Green }
function Warn([string]$m) { Write-Host "    WARN: $m" -ForegroundColor Yellow }
function Fail([string]$m) { Write-Host "    FAIL: $m" -ForegroundColor Red; exit 1 }

New-Item -ItemType Directory -Path $OUT_ANDROID,$OUT_WINDOWS -Force | Out-Null
Remove-Item "$OUT_ANDROID\.gitkeep","$OUT_WINDOWS\.gitkeep" -ErrorAction SilentlyContinue

# ══════════════════════════════════════════════════════
#  ANDROID
# ══════════════════════════════════════════════════════
if (-not $DesktopOnly) {
    Step "Android: assembleCarneliaVanillaRelease (arm64-v8a + x86_64)"

    if (-not (Test-Path $GRADLE)) { Fail "gradlew.bat не найден: $GRADLE" }

    if ($GradleHome) {
        New-Item -ItemType Directory -Path $GradleHome -Force | Out-Null
        $env:GRADLE_USER_HOME = $GradleHome
        Write-Host "    Gradle cache: $GradleHome" -ForegroundColor DarkGray
    }

    $t = [Diagnostics.Stopwatch]::StartNew()
    Push-Location $ANDROID_DIR
    try {
        & cmd.exe /c "gradlew.bat assembleCarneliaVanillaRelease --no-daemon 2>&1"
        if ($LASTEXITCODE -ne 0) { Fail "Gradle завершился с ошибкой ($LASTEXITCODE)" }
    } finally { Pop-Location }
    $t.Stop()
    OK "Собрано за $([int]$t.Elapsed.TotalSeconds) сек"

    # Копируем все APK из outputs
    $apks = Get-ChildItem -Recurse "$ANDROID_DIR\app\build\outputs\apk" -Filter "*.apk" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike "*unsigned*" -and $_.Name -notlike "*androidTest*" }

    foreach ($apk in $apks) {
        $arch = if ($apk.FullName -like "*arm64*") { "arm64" }
                elseif ($apk.FullName -like "*x86_64*") { "x86_64" }
                else { "universal" }
        $brandTag = if ($apk.FullName -like "*carnelia*") { "carnelia-vpn" } else { "null-vpn" }
        $dest = "$OUT_ANDROID\$brandTag-$VERSION-$arch.apk"
        Copy-Item $apk.FullName $dest -Force
        OK "$([System.IO.Path]::GetFileName($dest))  ($([math]::Round($apk.Length/1MB,1)) МБ)"
    }

    if (-not $apks) { Fail "APK не найдены" }
}

# ══════════════════════════════════════════════════════
#  DESKTOP WINDOWS x86_64
# ══════════════════════════════════════════════════════
if (-not $AndroidOnly) {
    $cmake = Get-Command cmake -ErrorAction SilentlyContinue
    if (-not $cmake) {
        Warn "cmake не найден — пропускаем Desktop. Установите CMake."
    } elseif (-not (Test-Path "$MSYS2_BIN\g++.exe")) {
        Warn "MSYS2 MinGW64 не найден ($MSYS2_BIN) — пропускаем Desktop."
    } else {
        Step "Desktop: Windows x86_64"
        $env:PATH = "$MSYS2_BIN;$env:PATH"
        $buildDir = "$root\build-desktop-release"

        & cmake -B $buildDir -S $DESKTOP_DIR -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release `
            -DCMAKE_CXX_COMPILER="$MSYS2_BIN\g++.exe" 2>&1 | Where-Object { $_ -notlike "*warning*" }
        if ($LASTEXITCODE -ne 0) { Warn "CMake configure не удался"; return }

        & cmake --build $buildDir -j8 2>&1 | Select-String "error:|warning:|Copying|Built|Linking"
        if ($LASTEXITCODE -ne 0) { Warn "CMake build не удался"; return }

        # Копируем все нужные файлы в releases/windows/
        $toCopy = @("CarneliaVPN.exe","Qt6Network.dll","libssl-3-x64.dll","libcrypto-3-x64.dll","xray.exe","logo.png")
        foreach ($f in $toCopy) {
            if (Test-Path "$buildDir\$f") {
                Copy-Item "$buildDir\$f" "$OUT_WINDOWS\$f" -Force
                OK "Скопирован $f"
            }
        }
        if (Test-Path "$buildDir\tls") {
            New-Item -ItemType Directory -Path "$OUT_WINDOWS\tls" -Force | Out-Null
            Copy-Item "$buildDir\tls\*" "$OUT_WINDOWS\tls\" -Force
            OK "Скопирован tls/"
        }
    }
}

# ══════════════════════════════════════════════════════
#  ИТОГ
# ══════════════════════════════════════════════════════
Step "Готово! Артефакты в releases/"
Get-ChildItem "$root\releases" -Recurse -File |
    Where-Object { $_.Name -ne ".gitkeep" } |
    ForEach-Object {
        $rel = $_.FullName.Replace($root, ".")
        $mb  = [math]::Round($_.Length/1MB, 1)
        Write-Host "    $rel  ($mb МБ)" -ForegroundColor White
    }
