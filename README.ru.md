<div align="center">

<img src="logo.svg" width="120" alt="ChimeraWire Logo"/>

# ChimeraWire

**Свободный мультиядерный Android VPN-клиент для обхода блокировок. Написан с нуля.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/ChimeraWire)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF9D5C?style=flat-square)](https://github.com/XTLS/Xray-core)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20·%20Material%203-FFB690?style=flat-square)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.2.2-success?style=flat-square)](https://github.com/voksed/ChimeraWire/releases/latest)

[English](README.md) · **Русский** · [Español](README.es.md) · [中文](README.zh.md) · [العربية](README.ar.md) · [Français](README.fr.md)

</div>

---

## Что это

**ChimeraWire** — Android-клиент для обхода блокировок, написанный с нуля на Kotlin + Jetpack Compose поверх нативного `VpnService`. Никаких форков чужого UI — свой код и своя архитектура. Под капотом несколько нативных ядер, между которыми клиент переключается в зависимости от протокола сервера: одно приложение говорит на VLESS/REALITY, WireGuard, AmneziaWG, Hysteria2, TUIC и WARP, а вам не нужно думать, какой движок сейчас работает.

---

## Что нового в 1.2

- **Новое оформление — Material 3 «Live Wire».** Тёплая янтарная палитра на полной цветовой системе Material 3 (тональные поверхности, светлая / тёмная / AMOLED) и опциональные **динамические цвета Material You** на Android 12+.
- **Serverless-режим FREEDOM.** Прямое подключение с фрагментацией TLS, которое обходит DPI **вообще без сервера**.
- **DNS-over-HTTPS** — шифрованный DNS без туннеля.
- **Локализованный текст обновлений** — приходит на языке приложения.
- Меньше расхода батареи: главный экран больше не анимируется в простое.

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

Импорт: `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`, а также INI-конфиги WireGuard/AmneziaWG и Amnezia JSON, QR-коды и ссылки-подписки.

---

## Возможности

- **Black Wall — анти-DPI движок:** фрагментация TLS ClientHello, SNI-камуфляж, шумовой трафик; работает даже **без сервера** (режим FREEDOM)
- **Интерфейс Material 3 «Live Wire»:** тёплая янтарная палитра, светлая / тёмная / AMOLED-темы + динамические цвета Material You (Android 12+)
- **DNS-over-HTTPS** — шифрованный DNS без туннеля
- **Kill Switch** (в приложении + подсказка системного Always-on) и защита от IPv6-утечки
- **Раздельное туннелирование** с выбором приложений
- **Автоподключение** при старте / смене сети, бесшовный reconnect при Wi-Fi↔моб
- **Двойной туннель** (мульти-хоп) — скрывает реальный IP от выходного сервера
- Импорт по URI / QR, подписки с авто-обновлением, зашифрованный бэкап
- Статистика трафика в реальном времени, график и карта; логи ядра в приложении
- **Tools Hub:** DNS Audit, Leak Test, Packet Inspector, Route Tracer, TLS Inspector, Fingerprint Check, Port Scanner, встроенный терминал, детект root/эмулятора
- **Приватность:** 🆘 Panic (мгновенный разрыв + очистка логов), 🎭 маскировка иконки (Калькулятор / Заметки), подмена GPS
- **6 языков:** English, Русский, Español, 中文, العربية, Français

---

## Быстрый старт

**Требования:** JDK 17+, Android SDK (API 34), ADB

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

Подписанные APK появятся в `android/app/build/outputs/apk/release/`:

| APK | Для чего |
|---|---|
| `app-arm64-v8a-release.apk` | современные телефоны (рекомендуется) |
| `app-universal-release.apk` | любое устройство, все ABI в одном файле |
| `app-x86_64-release.apk` | эмуляторы / x86-устройства |

Секреты подписи читаются из `keystore.properties` (в .gitignore) или из переменных окружения — паролей в репозитории нет.

---

## Скачать

Последний подписанный APK — на странице [**Releases**](https://github.com/voksed/ChimeraWire/releases/latest). Приложение само проверяет обновления при запуске и умеет ставить их на месте.

---

## Лицензия

GPLv3 — смотри [LICENSE](LICENSE). Встроенные ядра и их лицензии: [THIRD_PARTY.md](THIRD_PARTY.md).

## Конфиденциальность

Никакой аналитики, серверов и аккаунтов. Подробнее — в [PRIVACY.md](PRIVACY.md) · политика безопасности — в [SECURITY.md](SECURITY.md).
