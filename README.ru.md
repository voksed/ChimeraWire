<div align="center">

<img src="logo.svg" width="120" alt="Carnelia VPN Logo"/>

# Carnelia VPN

**Свободный мультиядерный Android VPN-клиент для обхода блокировок. Написан с нуля.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF6B35?style=flat-square)](https://github.com/XTLS/Xray-core)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.0-success?style=flat-square)](https://github.com/voksed/carnelia-vpn/releases)

[English](README.md) · **Русский** · [Español](README.es.md) · [中文](README.zh.md) · [العربية](README.ar.md)

</div>

---

## Что это

Carnelia VPN — Android-клиент для обхода блокировок, написанный с нуля на Kotlin + Jetpack Compose поверх нативного `VpnService`. Никаких форков чужого UI — свой код и своя архитектура. Под капотом несколько нативных ядер, между которыми клиент переключается в зависимости от протокола сервера.

---

## Ядра

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

VLESS (REALITY / TLS / WSS / gRPC), VMess, Trojan, Shadowsocks, WireGuard, AmneziaWG, Hysteria2, TUIC, Cloudflare WARP.

Импорт: `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`, а также INI-конфиги WireGuard/AmneziaWG и Amnezia JSON.

---

## Возможности

- **Black Wall — анти-DPI движок:** фрагментация TLS ClientHello, SNI-камуфляж, шумовой трафик
- **Kill Switch** (в приложении + подсказка системного Always-on) и защита от IPv6-утечки
- **Раздельное туннелирование** с выбором приложений
- **Автоподключение** при старте / смене сети, бесшовный reconnect при Wi-Fi↔моб
- **Двойной туннель** (мульти-хоп) — скрывает реальный IP от выходного сервера
- Импорт по URI, подписки с авто-обновлением, зашифрованный бэкап
- Статистика трафика в реальном времени, график и карта; логи ядра в приложении
- **Tools Hub:** DNS Audit, Leak Test, Packet Inspector, Route Tracer, TLS Inspector, Fingerprint Check, Port Scanner, встроенный терминал, детект root/эмулятора
- **Приватность:** 🆘 Panic (мгновенный разрыв + очистка логов), 🎭 маскировка иконки (Калькулятор / Заметки), подмена GPS
- **P2P-чат** по публичной Mainline DHT (сеть BitTorrent) — без своего сервера
- **5 языков:** English, Русский, Español, 中文, العربية

---

## Быстрый старт

**Требования:** JDK 17+, Android SDK (API 34), ADB

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

Готовый APK: `android/app/build/outputs/apk/release/` (arm64-v8a + x86_64).

Секреты подписи читаются из `keystore.properties` (в .gitignore) или из переменных окружения — паролей в репозитории нет.

---

## Лицензия

GPLv3. Смотри [LICENSE](LICENSE).

## Конфиденциальность

Никакой аналитики, серверов и аккаунтов. Подробнее — в [PRIVACY.md](PRIVACY.md).
