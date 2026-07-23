<div align="center">

<img src="CarneliaVPN_.svg" width="120" alt="Carnelia VPN Logo"/>

# Carnelia VPN

**Свободный мультиядерный VPN-клиент для обхода блокировок. Написан с нуля.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Desktop](https://img.shields.io/badge/Desktop-Windows%20%7C%20Linux-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF6B35?style=flat-square)](https://github.com/XTLS/Xray-core)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-3.0.0-success?style=flat-square)](https://github.com/voksed/carnelia-vpn/releases)

</div>

---

## Что это

Carnelia VPN — полноценный VPN-клиент для обхода блокировок, написанный с нуля. Никаких форков чужого UI, свой код и своя архитектура. Под капотом — не одно, а несколько нативных ядер, между которыми клиент переключается в зависимости от протокола сервера.

Две платформы:

- **Android** — Kotlin + Jetpack Compose, нативный `VpnService`, ядра как `.so`-библиотеки внутри APK
- **Desktop** — C++17 + Qt6 + GTK3, standalone-бинарник, Xray рядом

---

## Ядра

Клиент несёт сразу несколько движков и выбирает нужный под конкретный протокол:

| Ядро | Нативная библиотека | За что отвечает |
|---|---|---|
| **Xray-core** | `libxray_core.so` | VLESS / VMess / Trojan / Shadowsocks / WireGuard |
| **sing-box** | `libsingbox.so` | Hysteria2 / TUIC / современные транспорты |
| **AmneziaWG** | `libamneziawg.so` | нативный AWG-обфускатор (Jc / S1-S4 / H1-H4 / I1-I5) |
| **Hysteria2** | `libhysteria2.so` | QUIC-протокол Hysteria2 |
| **hev-socks5-tunnel** | `libhev-socks5-tunnel.so` | tun2socks для проксирующих протоколов |
| **Cloudflare WARP** | (WireGuard) | регистрация и подключение к WARP |

---

## Протоколы

| Протокол | Транспорт | Ядро | Статус |
|---|---|---|---|
| VLESS | REALITY | Xray | ✅ |
| VLESS | TLS / WSS / gRPC | Xray | ✅ |
| VMess | TLS / WSS | Xray | ✅ |
| Trojan | TLS | Xray | ✅ |
| Shadowsocks | tun2socks | Xray | ✅ |
| WireGuard | outbound | Xray | ✅ |
| AmneziaWG | нативный AWG | AmneziaWG | ✅ |
| Hysteria2 | QUIC | sing-box / Hysteria2 | ✅ |
| TUIC | QUIC | sing-box | ✅ |
| OpenVPN | ics-openvpn | vpnLib | ✅ |
| Cloudflare WARP | WireGuard | WARP | ✅ |

---

## Возможности

**Black Wall — Anti-DPI движок**
- Фрагментация TLS ClientHello (ломает пересборку пакетов на DPI/ТСПУ)
- SNI-камуфляж (подмена SNI на whitelisted CDN-домен)
- Шумовой трафик для маскировки паттернов
- Доступен прямо из главного меню

**Управление подключением**
- Kill Switch — режет трафик при падении VPN
- Раздельное туннелирование (Split Tunneling) с выбором приложений
- Автоподключение при старте и при смене сети (Network Change Watcher)
- Горячая смена сервера без обрыва туннеля
- Quick Settings плитка в шторке + виджет на рабочем столе

**Серверы и конфиги**
- Импорт через URI: `vless://`, `vmess://`, `ss://`, `trojan://`, `wireguard://`, `hysteria2://`, `tuic://`
- Подписки (subscription URL) с авто-обновлением
- Ручной ввод конфига
- Зашифрованный бэкап/восстановление списка серверов

**Мониторинг**
- Статистика трафика в реальном времени (↑↓)
- График и визуальная карта трафика
- Просмотр логов ядра прямо в приложении

**Инструменты сети и приватности (Tools Hub)**
- DNS Audit — проверка DNS и утечек резолвера
- Leak Test — тест на утечки IP/DNS/WebRTC
- Packet Inspector — просмотр пакетов
- Route Tracer — трассировка маршрута
- TLS Inspector — анализ TLS-рукопожатия
- Fingerprint Check — проверка сетевого отпечатка
- Port Scanner — сканер портов
- Terminal — встроенный терминал
- Security Checker — детект root / эмулятора / отладки

**Приватность**
- 🆘 Panic — экстренный разрыв VPN и очистка логов/истории (по флагу — стирание серверов)
- 🎭 Маскировка иконки — приложение прячется под «Калькулятор» / «Заметки» через activity-alias
- GPS-подмена (GeoSpoof)

**P2P-чат**
- Зашифрованный чат по публичной Mainline DHT (та же сеть, что у BitTorrent) — без своего сервера

**Бонус**
- Miner — мини-игра / пасхалка

---

## Редакции сборки

Сборка комбинируется по двум измерениям:

| Измерение | Варианты |
|---|---|
| Бренд | `carnelia` (Carnelia VPN) / `null` (null vpn) |
| Редакция | `vanilla` (без кошелька) / `wallet` (с крипто-кошельком) |

Итоговый вариант, например: `assembleCarneliaVanillaRelease`.

---

## Архитектура (Android)

```
android/app/src/main/kotlin/com/carnelia/vpn/
├── MainActivity.kt            # Главный экран (Compose)
├── SettingsActivity.kt        # Настройки
├── ToolsHubActivity.kt        # Хаб сетевых инструментов
├── BlackWallActivity.kt       # Anti-DPI движок
├── ChatActivity.kt            # P2P-чат
├── TerminalActivity.kt        # Встроенный терминал
├── PortScannerActivity.kt     # Сканер портов
├── DnsAuditActivity.kt        # DNS-аудит
├── LeakTestActivity.kt        # Тест утечек
├── PacketInspectorActivity.kt # Инспектор пакетов
├── RouteTracerActivity.kt     # Трассировка
├── TlsInspectorActivity.kt    # TLS-инспектор
├── FingerprintCheckActivity.kt
├── GeoSpoofActivity.kt        # GPS-подмена
├── LogsActivity.kt            # Логи ядра
├── StatisticsActivity.kt      # Статистика трафика
├── TrafficGraphActivity.kt    # График трафика
│
├── core/
│   ├── XrayCoreManager.kt        # Xray: старт/стоп/конфиг
│   ├── SingboxCoreManager.kt     # sing-box (Hysteria2/TUIC)
│   ├── AmneziaWgCoreManager.kt   # нативный AmneziaWG
│   ├── Hysteria2ProcessManager.kt
│   ├── WarpManager.kt            # Cloudflare WARP
│   ├── VpnManager.kt             # Жизненный цикл VPN
│   ├── BlackWallEngine.kt        # Anti-DPI: фрагментация + SNI + шум
│   ├── NoiseModeManager.kt       # Шумовой трафик
│   ├── PanicManager.kt           # Экстренная очистка
│   ├── IconDisguiseManager.kt    # Маскировка иконки/имени
│   ├── NetworkChangeWatcher.kt   # Реакция на смену сети
│   ├── NetworkLockdownManager.kt # Kill Switch
│   ├── SniffingGuard.kt
│   ├── TrafficStatsManager.kt
│   ├── SecurityScanner.kt
│   ├── chat/                     # Bencode, ChatCrypto, DhtClient, ChatRoomManager
│   └── protocols/                # VpnProtocols.kt
│
├── data/
│   ├── ServerRepository.kt
│   ├── SubscriptionManager.kt
│   ├── SecureBackupManager.kt    # Зашифрованный бэкап
│   └── BackupManager.kt
│
├── service/
│   ├── CarheliaVpnService.kt     # VpnService (Android API)
│   ├── ChatService.kt            # Foreground-сервис чата
│   ├── BootReceiver.kt           # Автозапуск
│   ├── NetworkMonitor.kt
│   ├── VpnScheduleManager.kt
│   └── VpnTileService.kt         # QS-плитка
│
├── security/SecurityChecker.kt   # root / эмулятор / отладка
├── widget/                       # Виджет на рабочий стол
├── games/MinerActivity.kt        # Пасхалка
└── utils/
    ├── ConfigUtils.kt            # Парсинг URI конфигов
    ├── PrefsManager.kt           # Настройки (SharedPreferences)
    ├── UpdateManager.kt          # Авто-обновление APK
    └── LeakTestManager.kt
```

Нативные ядра лежат в `android/app/src/main/jniLibs/<abi>/` как `.so`.

Desktop-клиент — в [desktop/](desktop/) (C++17 / Qt6 / GTK3, CMake).

---

## Быстрый старт

### Android

**Требования:** JDK 17+, Android SDK (API 34), ADB

```powershell
# Сборка release APK (бренд Carnelia, без кошелька)
cd android
.\gradlew.bat assembleCarneliaVanillaRelease --no-daemon

# Сборка + установка одной командой
.\build_and_deploy.ps1

# Только сборка / только установка
.\build_and_deploy.ps1 -BuildOnly
.\build_and_deploy.ps1 -DeployOnly
```

Готовый APK: `android/app/build/outputs/apk/carneliaVanilla/release/`

**Варианты сборки:**
- `assembleCarneliaVanillaRelease` — Carnelia VPN, стандарт
- `assembleCarneliaWalletRelease` — Carnelia VPN с криптокошельком
- `assembleNullVanillaRelease` — бренд null vpn
- `assembleCarneliaVanillaDebug` — debug (подписывается автоматически)

### Desktop (Windows / Linux)

**Требования:** CMake 3.22+, Qt6, GTK3, pkg-config, Xray binary

```bash
# Linux
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)
```

```powershell
# Windows (MSYS2 MinGW64)
cmake -B build -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release
cmake --build build -j8
```

Бинарник Xray должен лежать в `xray-extracted/xray[.exe]` — CMake скопирует его рядом с `CarneliaVPN.exe` автоматически.

---

## ADB — шпаргалка

```powershell
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $ADB devices                                            # Устройства
& $ADB install -r carnelia-vpn.apk                        # Установить
& $ADB shell am start -n "com.carnelia.vpn/.MainActivity" # Запустить
& $ADB logcat --pid=$(& $ADB shell pidof com.carnelia.vpn) -v time  # Логи
& $ADB uninstall com.carnelia.vpn                         # Удалить
```

---

## Импорт конфига

```kotlin
// Из URI напрямую
val config = ConfigUtils.parseAccessKey("vless://uuid@host:port?security=reality&...")

// Структура сервера
data class VpnServerConfig(
    val id: String,
    val name: String,
    val protocol: VpnProtocol,   // VLESS, VMESS, SS, WG, AWG, TROJAN, HYSTERIA2, TUIC, OPENVPN
    val host: String,
    val port: Int,
    val config: Map<String, String>
)
```

Поддерживаемые схемы: `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`

---

## Известные нюансы

**Gradle sync: "SDK not found"**
```powershell
Get-Content android\local.properties
# Должно быть: sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

**ADB: device not found**
```
Настройки → О телефоне → 7 раз тапнуть "Номер сборки"
Настройки → Для разработчиков → Отладка по USB → вкл
adb kill-server ; adb start-server ; adb devices
```

**conflicting classes из Go (tun2socks)**
```
Обработано в build.gradle.kts через pickFirsts для go/**
```

---

## Лицензия

GPLv3. Смотри [LICENSE](LICENSE).

---

## Конфиденциальность

Никакой аналитики, никаких серверов, никаких аккаунтов. Подробно — в [PRIVACY.md](PRIVACY.md).
