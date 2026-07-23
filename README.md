<div align="center">

<img src="logo.svg" width="120" alt="Carnelia VPN Logo"/>

# Carnelia VPN

**Свободный мультиядерный Android VPN-клиент для обхода блокировок. Написан с нуля.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF6B35?style=flat-square)](https://github.com/XTLS/Xray-core)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-3.0.0-success?style=flat-square)](https://github.com/voksed/carnelia-vpn/releases)

</div>

---

## Что это

Carnelia VPN — Android-клиент для обхода блокировок, написанный с нуля на Kotlin + Jetpack Compose поверх нативного `VpnService`. Никаких форков чужого UI, свой код и своя архитектура. Под капотом — не одно, а несколько нативных ядер, между которыми клиент переключается в зависимости от протокола сервера.

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
| Cloudflare WARP | WireGuard | WARP | ✅ |

Импорт: `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`, а также INI/JSON-конфиги WireGuard и AmneziaWG.

---

## Возможности

**Black Wall — Anti-DPI движок**
- Фрагментация TLS ClientHello (ломает пересборку пакетов на DPI/ТСПУ)
- SNI-камуфляж (подмена SNI на whitelisted CDN-домен)
- Шумовой трафик для маскировки паттернов

**Управление подключением**
- Kill Switch — режет трафик при падении VPN
- Раздельное туннелирование (Split Tunneling) с выбором приложений
- Автоподключение при старте и при смене сети (Network Change Watcher)
- Горячая смена сервера без обрыва туннеля, авто-failover по пингу
- Quick Settings плитка в шторке + виджет на рабочем столе

**Серверы и конфиги**
- Импорт через URI, подписки (subscription URL) с авто-обновлением, ручной ввод
- Зашифрованный бэкап/восстановление списка серверов

**Мониторинг**
- Статистика трафика в реальном времени (↑↓), график и визуальная карта
- Просмотр логов ядра прямо в приложении

**Инструменты сети и приватности (Tools Hub)**
- DNS Audit, Leak Test, Packet Inspector, Route Tracer, TLS Inspector
- Fingerprint Check, Port Scanner, встроенный Terminal
- Security Checker — детект root / эмулятора / отладки

**Приватность**
- 🆘 Panic — экстренный разрыв VPN и очистка логов/истории
- 🎭 Маскировка иконки — приложение прячется под «Калькулятор» / «Заметки»
- GPS-подмена (GeoSpoof)

**P2P-чат**
- Зашифрованный чат по публичной Mainline DHT (сеть BitTorrent) — без своего сервера

---

## Архитектура

```
android/app/src/main/kotlin/com/carnelia/vpn/
├── MainActivity.kt            # Главный экран (Compose)
├── SettingsActivity.kt        # Настройки
├── ToolsHubActivity.kt        # Хаб сетевых инструментов
├── BlackWallActivity.kt       # Anti-DPI движок
├── ChatActivity.kt            # P2P-чат
├── TerminalActivity.kt        # Встроенный терминал
├── GeoSpoofActivity.kt        # GPS-подмена
├── LogsActivity.kt            # Логи ядра
│
├── core/
│   ├── XrayCoreManager.kt        # Xray: старт/стоп/конфиг
│   ├── SingboxCoreManager.kt     # sing-box (Hysteria2/TUIC)
│   ├── AmneziaWgCoreManager.kt   # нативный AmneziaWG
│   ├── Hysteria2ProcessManager.kt
│   ├── WarpManager.kt            # Cloudflare WARP
│   ├── VpnManager.kt             # Жизненный цикл VPN
│   ├── BlackWallEngine.kt        # Anti-DPI: фрагментация + SNI + шум
│   ├── PanicManager.kt           # Экстренная очистка
│   ├── IconDisguiseManager.kt    # Маскировка иконки/имени
│   ├── NetworkChangeWatcher.kt   # Реакция на смену сети
│   ├── NetworkLockdownManager.kt # Kill Switch
│   ├── chat/                     # Bencode, ChatCrypto, DhtClient, ChatRoomManager
│   └── protocols/                # VpnProtocols.kt (фабрика протоколов)
│
├── data/                      # ServerRepository, SubscriptionManager, SecureBackupManager
├── service/
│   ├── CarheliaVpnService.kt     # VpnService (Android API)
│   ├── ChatService.kt            # Foreground-сервис чата
│   └── VpnTileService.kt         # QS-плитка
├── security/SecurityChecker.kt   # root / эмулятор / отладка
├── widget/                       # Виджет на рабочий стол
└── utils/                        # ConfigUtils, PrefsManager, UpdateManager, ...
```

Нативные ядра лежат в `android/app/src/main/jniLibs/<abi>/` как `.so`.

---

## Быстрый старт

**Требования:** JDK 17+, Android SDK (API 34), ADB

```powershell
# Сборка + установка на подключённый телефон одной командой
.\build_and_deploy.ps1

# Только собрать / только установить
.\build_and_deploy.ps1 -BuildOnly
.\build_and_deploy.ps1 -DeployOnly

# Debug-сборка
.\build_and_deploy.ps1 -Debug
```

Или напрямую через Gradle:

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

Готовый APK: `android/app/build/outputs/apk/release/` (arm64-v8a + x86_64).

---

## ADB — шпаргалка

```powershell
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $ADB devices                                            # Устройства
& $ADB install -r carnelia-vpn.apk                        # Установить
& $ADB shell am start -n "com.carnelia.vpn/.MainActivity" # Запустить
& $ADB logcat --pid=$(& $ADB shell pidof com.carnelia.vpn) -v time  # Логи
```

---

## Лицензия

GPLv3. Смотри [LICENSE](LICENSE).

---

## Конфиденциальность

Никакой аналитики, никаких серверов, никаких аккаунтов. Подробно — в [PRIVACY.md](PRIVACY.md).
