#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Собирает все релизные артефакты CarneliaVPN 2.4.1 и кладёт в папку releases/.
.DESCRIPTION
    Android:
      - releases/android/CarneliaVPN_v2.4.1-arm64-v8a-release.apk
      - releases/android/CarneliaVPN_v2.4.1-armeabi-v7a-release.apk
    Windows (если установлен CMake + MSYS2):
      - releases/windows/CarneliaVPN-x86_64.exe
      - releases/windows/CarneliaVPN-arm64.exe   (если есть clangarm64)

.USAGE
    .\build_release.ps1                   # Собрать всё
    .\build_release.ps1 -AndroidOnly      # Только Android APK
    .\build_release.ps1 -DesktopOnly      # Только десктоп
    .\build_release.ps1 -Arm64Only        # Только ARMv7 + ARM64 APK
#>

param(
    [switch]$AndroidOnly,
    [switch]$DesktopOnly,
    [switch]$Arm64Only
)

$ErrorActionPreference = "Stop"
$root   = $PSScriptRoot
$outAndroid = "$root\releases\android"
$outWindows = "$root\releases\windows"
$androidDir = "$root\android"
$gradle = "$androidDir\gradlew.bat"

function Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function OK([string]$msg)   { Write-Host "    OK: $msg" -ForegroundColor Green }
function Warn([string]$msg) { Write-Host "    WARN: $msg" -ForegroundColor Yellow }

# ========== ANDROID ==========
if (-not $DesktopOnly) {
    Step "Android: сборка APK v2.4.1"

    if (-not (Test-Path $gradle)) { Write-Error "gradlew.bat не найден: $gradle" }

    # Авто-поиск JAVA_HOME если не задан
    if (-not $env:JAVA_HOME) {
        $jdkPath = Get-ChildItem "C:\Program Files\Microsoft" -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -like "jdk*" } |
                   Sort-Object Name -Descending |
                   Select-Object -First 1 -ExpandProperty FullName
        if (-not $jdkPath) {
            $javaCmd = Get-Command java -ErrorAction SilentlyContinue
            if ($javaCmd) { $jdkPath = $javaCmd.Source -replace "\\bin\\java.exe", "" }
        }
        if ($jdkPath) {
            $env:JAVA_HOME = $jdkPath
            $env:Path = "$jdkPath\bin;$env:Path"
            OK "JAVA_HOME автоматически: $jdkPath"
        } else {
            Write-Error "JDK не найден. Установите JDK 17 или задайте JAVA_HOME."
        }
    }

    $abis = if ($Arm64Only) { @("armeabi-v7a", "arm64-v8a") } else { @("armeabi-v7a", "arm64-v8a") }

    foreach ($abi in $abis) {
        Step "  Сборка для $abi"
        $savedLocation = Get-Location
        Set-Location $androidDir
        & "$gradle" assembleVanillaRelease "-PtargetAbi=$abi" "--no-daemon"
        $exitCode = $LASTEXITCODE
        Set-Location $savedLocation
        if ($exitCode -ne 0) { Write-Error "Gradle завершился с ошибкой для $abi (код $exitCode)" }

            $apkAll = Get-ChildItem -Recurse "$androidDir\app\build\outputs\apk" -Filter "*release*.apk" -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notlike "*unsigned*" }

        $apk = $apkAll | Where-Object { $_.FullName -like "*$abi*" } |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1

        if (-not $apk) {
            $apk = $apkAll | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        }

        if ($apk) {
            $dest = "$outAndroid\CarneliaVPN_v2.4.1-$abi-release.apk"
            Copy-Item $apk.FullName $dest -Force
            OK "APK скопирован: $dest  ($([math]::Round($apk.Length/1MB,1)) МБ)"
        } else {
            Warn "APK не найден для $abi"
        }
    }
}

# ========== DESKTOP ==========
if (-not $AndroidOnly -and -not $Arm64Only) {
    $cmakeCmd = Get-Command cmake -ErrorAction SilentlyContinue
    $cmake = if ($cmakeCmd) { $cmakeCmd.Source } else { $null }
    if (-not $cmake) {
        Warn "cmake не найден — пропускаем десктоп. Установите CMake и MSYS2."
    } else {
        foreach ($arch in @("x86_64", "arm64")) {
            $msys2env = if ($arch -eq "arm64") { "C:\msys64\clangarm64" } else { "C:\msys64\mingw64" }
            if (-not (Test-Path $msys2env)) {
                Warn "MSYS2 окружение не найдено: $msys2env — пропускаем $arch"
                continue
            }

            Step "Desktop: сборка для Windows $arch"
            $buildDir = "$root\build-desktop-$arch"

            & cmake -B $buildDir -DBUILD_ARCH=$arch -DCMAKE_BUILD_TYPE=Release -S "$root\desktop"
            if ($LASTEXITCODE -ne 0) { Warn "CMake configure не удался для $arch"; continue }

            & cmake --build $buildDir --config Release
            if ($LASTEXITCODE -ne 0) { Warn "CMake build не удался для $arch"; continue }

            $exe = "$buildDir\CarneliaVPN.exe"
            if (Test-Path $exe) {
                $dest = "$outWindows\CarneliaVPN-windows-$arch.exe"
                Copy-Item $exe $dest -Force
                OK "EXE скопирован: $dest  ($([math]::Round((Get-Item $exe).Length/1MB,1)) МБ)"
            } else {
                Warn "EXE не найден: $exe"
            }
        }
    }
}

Step "Готово! Артефакты в папке releases/"
Get-ChildItem "$root\releases" -Recurse -File | Where-Object { $_.Name -ne ".gitkeep" } |
    ForEach-Object { Write-Host "    $_".Replace($root, ".") -ForegroundColor White }
