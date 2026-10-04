<div align="center">

<img src="logo.svg" width="120" alt="ChimeraWire Logo"/>

# ChimeraWire

**A free, multi-core Android VPN client for bypassing censorship. Built from scratch.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/ChimeraWire)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF9D5C?style=flat-square)](https://github.com/XTLS/Xray-core)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20·%20Material%203-FFB690?style=flat-square)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.2.2-success?style=flat-square)](https://github.com/voksed/ChimeraWire/releases/latest)

**English** · [Русский](README.ru.md) · [Español](README.es.md) · [中文](README.zh.md) · [العربية](README.ar.md) · [Français](README.fr.md)

</div>

---

## What is it

**ChimeraWire** is an Android client for bypassing censorship, written from scratch in Kotlin + Jetpack Compose on top of the native `VpnService`. No forked UI — its own code and architecture. Under the hood it carries several native cores and switches between them depending on the server protocol, so one app speaks VLESS/REALITY, WireGuard, AmneziaWG, Hysteria2, TUIC and WARP without you ever thinking about which engine is running.

---

## What's new in 1.2

- **New look — Material 3 "Live Wire".** A warm ember palette on a full Material 3 color system (tonal surfaces, light / dark / AMOLED), with optional **Material You** dynamic colors on Android 12+.
- **Serverless FREEDOM mode.** A direct connection with TLS fragmentation that bypasses DPI with **no server at all**.
- **DNS-over-HTTPS** — encrypted DNS without a tunnel.
- **Localized update notes** that arrive in the app's own language.
- Lower battery use: the home screen no longer animates while idle.

---

## Cores

| Core | Native library | Handles |
|---|---|---|
| **Xray-core** | `libxray_core.so` | VLESS / VMess / Trojan / Shadowsocks / WireGuard |
| **sing-box** | `libsingbox.so` | Hysteria2 / TUIC / modern transports |
| **AmneziaWG** | `libamneziawg.so` | native AWG obfuscation (Jc / S1-S4 / H1-H4 / I1-I5) |
| **Hysteria2** | `libhysteria2.so` | the Hysteria2 QUIC protocol |
| **hev-socks5-tunnel** | `libhev-socks5-tunnel.so` | tun2socks for proxying protocols |
| **Cloudflare WARP** | (WireGuard) | WARP registration and connection |

---

## Protocols

VLESS (REALITY / TLS / WSS / gRPC), VMess, Trojan, Shadowsocks, WireGuard, AmneziaWG, Hysteria2, TUIC, Cloudflare WARP.

Import via `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`, plus WireGuard/AmneziaWG INI and Amnezia JSON configs, QR codes and subscription links.

---

## Features

- **Black Wall — anti-DPI engine:** TLS ClientHello fragmentation, SNI camouflage, noise traffic; works even **without a server** (FREEDOM mode)
- **Material 3 "Live Wire" interface:** warm ember palette, light / dark / AMOLED themes + Material You dynamic colors (Android 12+)
- **DNS-over-HTTPS** — encrypted DNS without a tunnel
- **Kill Switch** (in-app + system Always-on guidance) and IPv6-leak protection
- **Split Tunneling** with per-app selection
- **Auto-connect** on launch / network change, seamless reconnect on Wi-Fi↔cellular switch
- **Double Tunnel** (multi-hop) to hide your real IP from the exit server
- Server import via URI / QR, subscriptions with auto-update, encrypted backup
- Real-time traffic stats, graph and map; in-app core logs
- **Tools Hub:** DNS Audit, Leak Test, Packet Inspector, Route Tracer, TLS Inspector, Fingerprint Check, Port Scanner, built-in Terminal, root/emulator detection
- **Privacy:** 🆘 Panic (instant disconnect + wipe logs), 🎭 icon disguise (Calculator / Notes), GPS spoofing
- **6 languages:** English, Русский, Español, 中文, العربية, Français

---

## Quick start

**Requirements:** JDK 17+, Android SDK (API 34), ADB

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

Signed APKs land in `android/app/build/outputs/apk/release/`:

| APK | For |
|---|---|
| `app-arm64-v8a-release.apk` | modern phones (recommended) |
| `app-universal-release.apk` | any device, every ABI in one file |
| `app-x86_64-release.apk` | emulators / x86 devices |

Signing secrets are read from `keystore.properties` (git-ignored) or environment variables — no passwords in the repo.

---

## Download

Grab the latest signed APK from the [**Releases**](https://github.com/voksed/ChimeraWire/releases/latest) page. The app checks for updates on launch and can install them in place.

---

## License

GPLv3 — see [LICENSE](LICENSE). Bundled cores and their licenses: [THIRD_PARTY.md](THIRD_PARTY.md).

## Privacy

No analytics, no servers, no accounts. Details in [PRIVACY.md](PRIVACY.md) · security policy in [SECURITY.md](SECURITY.md).
