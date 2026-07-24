<div align="center">

<img src="logo.svg" width="120" alt="Carnelia VPN Logo"/>

# Carnelia VPN

**Un client VPN Android gratuit et multi-cœur pour contourner la censure. Conçu de zéro.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF6B35?style=flat-square)](https://github.com/XTLS/Xray-core)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.1-success?style=flat-square)](https://github.com/voksed/carnelia-vpn/releases)

[English](README.md) · [Русский](README.ru.md) · [Español](README.es.md) · [中文](README.zh.md) · [العربية](README.ar.md) · **Français**

</div>

---

## Qu'est-ce que c'est

Carnelia VPN est un client Android pour contourner la censure, écrit de zéro en Kotlin + Jetpack Compose sur le `VpnService` natif. Aucune interface reprise d'ailleurs — code et architecture propres. Sous le capot, il embarque plusieurs cœurs natifs et bascule entre eux selon le protocole du serveur.

---

## Cœurs

| Cœur | Bibliothèque native | Prend en charge |
|---|---|---|
| **Xray-core** | `libxray_core.so` | VLESS / VMess / Trojan / Shadowsocks / WireGuard |
| **sing-box** | `libsingbox.so` | Hysteria2 / TUIC / transports modernes |
| **AmneziaWG** | `libamneziawg.so` | obfuscation AWG native (Jc / S1-S4 / H1-H4 / I1-I5) |
| **Hysteria2** | `libhysteria2.so` | le protocole QUIC Hysteria2 |
| **hev-socks5-tunnel** | `libhev-socks5-tunnel.so` | tun2socks pour les protocoles proxy |
| **Cloudflare WARP** | (WireGuard) | inscription et connexion à WARP |

---

## Protocoles

VLESS (REALITY / TLS / WSS / gRPC), VMess, Trojan, Shadowsocks, WireGuard, AmneziaWG, Hysteria2, TUIC, Cloudflare WARP.

Import via `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`, ainsi que les configurations INI WireGuard/AmneziaWG et JSON Amnezia.

---

## Fonctionnalités

- **Black Wall — moteur anti-DPI :** fragmentation du ClientHello TLS, camouflage du SNI, trafic de bruit
- **Kill Switch** (dans l'appli + guide de l'Always-on système) et protection contre les fuites IPv6
- **Tunnel divisé** avec sélection par application
- **Connexion auto** au lancement / au changement de réseau, reconnexion fluide lors du passage Wi-Fi↔données mobiles
- **Double tunnel** (multi-sauts) pour masquer votre IP réelle au serveur de sortie
- Import de serveurs par URI, abonnements avec mise à jour auto, sauvegarde chiffrée
- Statistiques de trafic en temps réel, graphique et carte ; journaux du cœur dans l'appli
- **Tools Hub :** Audit DNS, Test de fuite, Inspecteur de paquets, Traceur de route, Inspecteur TLS, Vérification d'empreinte, Scanner de ports, terminal intégré, détection root/émulateur
- **Confidentialité :** 🆘 Panic (déconnexion instantanée + effacement des journaux), 🎭 déguisement d'icône (Calculatrice / Notes), falsification GPS
- **Chat P2P** via la Mainline DHT publique (réseau BitTorrent) — sans serveur propre
- **6 langues :** English, Русский, Español, 中文, العربية, Français

---

## Démarrage rapide

**Prérequis :** JDK 17+, Android SDK (API 34), ADB

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

APK signé en sortie : `android/app/build/outputs/apk/release/` (arm64-v8a + x86_64).

Les secrets de signature sont lus depuis `keystore.properties` (ignoré par git) ou des variables d'environnement — aucun mot de passe dans le dépôt.

---

## Licence

GPLv3. Voir [LICENSE](LICENSE).

## Confidentialité

Aucune analyse, aucun serveur, aucun compte. Détails dans [PRIVACY.md](PRIVACY.md).
