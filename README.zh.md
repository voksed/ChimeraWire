<div align="center">

<img src="logo.svg" width="120" alt="Carnelia VPN Logo"/>

# Carnelia VPN

**免费、多核心的 Android VPN 客户端，用于突破网络审查。完全从零构建。**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF6B35?style=flat-square)](https://github.com/XTLS/Xray-core)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.1-success?style=flat-square)](https://github.com/voksed/carnelia-vpn/releases)

[English](README.md) · [Русский](README.ru.md) · [Español](README.es.md) · **中文** · [العربية](README.ar.md) · [Français](README.fr.md)

</div>

---

## 这是什么

Carnelia VPN 是一款用于突破审查的 Android 客户端，基于原生 `VpnService`，采用 Kotlin + Jetpack Compose 从零编写。没有套用任何第三方界面——完全自有的代码与架构。其内部集成了多个原生核心，并根据服务器协议在它们之间切换。

---

## 核心

| 核心 | 原生库 | 负责 |
|---|---|---|
| **Xray-core** | `libxray_core.so` | VLESS / VMess / Trojan / Shadowsocks / WireGuard |
| **sing-box** | `libsingbox.so` | Hysteria2 / TUIC / 现代传输 |
| **AmneziaWG** | `libamneziawg.so` | 原生 AWG 混淆（Jc / S1-S4 / H1-H4 / I1-I5） |
| **Hysteria2** | `libhysteria2.so` | Hysteria2 QUIC 协议 |
| **hev-socks5-tunnel** | `libhev-socks5-tunnel.so` | 用于代理协议的 tun2socks |
| **Cloudflare WARP** | (WireGuard) | WARP 注册与连接 |

---

## 协议

VLESS（REALITY / TLS / WSS / gRPC）、VMess、Trojan、Shadowsocks、WireGuard、AmneziaWG、Hysteria2、TUIC、Cloudflare WARP。

支持通过 `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://` 导入，以及 WireGuard/AmneziaWG 的 INI 与 Amnezia JSON 配置。

---

## 功能

- **Black Wall —— 反 DPI 引擎：** TLS ClientHello 分片、SNI 伪装、噪声流量
- **网络断路器**（应用内 + 系统 Always-on 引导）与 IPv6 泄漏防护
- **分应用代理**，可按应用选择
- **自动连接**（启动时 / 网络切换时），Wi-Fi↔移动数据切换时无缝重连
- **双重隧道**（多跳），向出口服务器隐藏你的真实 IP
- 通过 URI 导入服务器、带自动更新的订阅、加密备份
- 实时流量统计、图表与地图；应用内核心日志
- **工具中心：** DNS 审计、泄漏检测、数据包检查器、路由追踪、TLS 检查器、指纹检查、端口扫描器、内置终端、root/模拟器检测
- **隐私：** 🆘 Panic（即时断开 + 清除日志）、🎭 图标伪装（计算器 / 备忘录）、GPS 伪装
- **P2P 聊天**，基于公共 Mainline DHT（BitTorrent 网络）——无需自建服务器
- **6 种语言：** English、Русский、Español、中文、العربية、Français

---

## 快速开始

**要求：** JDK 17+、Android SDK (API 34)、ADB

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

已签名 APK 输出路径：`android/app/build/outputs/apk/release/`（arm64-v8a + x86_64）。

签名密钥从 `keystore.properties`（已被 git 忽略）或环境变量读取——仓库中不包含任何密码。

---

## 许可证

GPLv3。详见 [LICENSE](LICENSE)。

## 隐私

无分析统计、无服务器、无账户。详情见 [PRIVACY.md](PRIVACY.md)。
