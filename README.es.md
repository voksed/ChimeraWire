<div align="center">

<img src="logo.svg" width="120" alt="Carnelia VPN Logo"/>

# Carnelia VPN

**Cliente VPN de Android gratuito y multinúcleo para evadir la censura. Creado desde cero.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF6B35?style=flat-square)](https://github.com/XTLS/Xray-core)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.1-success?style=flat-square)](https://github.com/voksed/carnelia-vpn/releases)

[English](README.md) · [Русский](README.ru.md) · **Español** · [中文](README.zh.md) · [العربية](README.ar.md) · [Français](README.fr.md)

</div>

---

## ¿Qué es?

Carnelia VPN es un cliente de Android para evadir la censura, escrito desde cero en Kotlin + Jetpack Compose sobre el `VpnService` nativo. Sin interfaz bifurcada: código y arquitectura propios. Bajo el capó lleva varios núcleos nativos y cambia entre ellos según el protocolo del servidor.

---

## Núcleos

| Núcleo | Librería nativa | Se encarga de |
|---|---|---|
| **Xray-core** | `libxray_core.so` | VLESS / VMess / Trojan / Shadowsocks / WireGuard |
| **sing-box** | `libsingbox.so` | Hysteria2 / TUIC / transportes modernos |
| **AmneziaWG** | `libamneziawg.so` | ofuscación AWG nativa (Jc / S1-S4 / H1-H4 / I1-I5) |
| **Hysteria2** | `libhysteria2.so` | el protocolo QUIC Hysteria2 |
| **hev-socks5-tunnel** | `libhev-socks5-tunnel.so` | tun2socks para protocolos con proxy |
| **Cloudflare WARP** | (WireGuard) | registro y conexión con WARP |

---

## Protocolos

VLESS (REALITY / TLS / WSS / gRPC), VMess, Trojan, Shadowsocks, WireGuard, AmneziaWG, Hysteria2, TUIC, Cloudflare WARP.

Importación mediante `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`, además de configuraciones INI de WireGuard/AmneziaWG y JSON de Amnezia.

---

## Características

- **Black Wall — motor anti-DPI:** fragmentación del ClientHello de TLS, camuflaje de SNI, tráfico de ruido
- **Kill Switch** (en la app + guía del Always-on del sistema) y protección contra fugas de IPv6
- **Túnel dividido** con selección por app
- **Conexión automática** al iniciar / al cambiar de red, reconexión fluida al pasar de Wi-Fi a datos móviles
- **Túnel doble** (multisalto) para ocultar tu IP real al servidor de salida
- Importación de servidores por URI, suscripciones con actualización automática, copia cifrada
- Estadísticas de tráfico en tiempo real, gráfico y mapa; registros del núcleo en la app
- **Tools Hub:** Auditoría DNS, Prueba de fugas, Inspector de paquetes, Rastreador de rutas, Inspector TLS, Comprobación de huella, Escáner de puertos, terminal integrada, detección de root/emulador
- **Privacidad:** 🆘 Panic (desconexión instantánea + borrado de registros), 🎭 disfraz de icono (Calculadora / Notas), falseo de GPS
- **Chat P2P** sobre la Mainline DHT pública (red BitTorrent) — sin servidor propio
- **6 idiomas:** English, Русский, Español, 中文, العربية, Français

---

## Inicio rápido

**Requisitos:** JDK 17+, Android SDK (API 34), ADB

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

APK firmado en: `android/app/build/outputs/apk/release/` (arm64-v8a + x86_64).

Los secretos de firma se leen de `keystore.properties` (ignorado por git) o de variables de entorno — no hay contraseñas en el repositorio.

---

## Licencia

GPLv3. Consulta [LICENSE](LICENSE).

## Privacidad

Sin analíticas, sin servidores, sin cuentas. Detalles en [PRIVACY.md](PRIVACY.md).
