<div align="center">

<img src="logo.svg" width="120" alt="Carnelia VPN Logo"/>

# Carnelia VPN

**عميل VPN مجاني ومتعدّد النوى لنظام Android لتجاوز الرقابة. مبنيّ من الصفر.**

[![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/voksed/carnelia-vpn)
[![Cores](https://img.shields.io/badge/Cores-Xray%20%7C%20sing--box%20%7C%20AmneziaWG-FF6B35?style=flat-square)](https://github.com/XTLS/Xray-core)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.0-success?style=flat-square)](https://github.com/voksed/carnelia-vpn/releases)

[English](README.md) · [Русский](README.ru.md) · [Español](README.es.md) · [中文](README.zh.md) · **العربية**

</div>

---

## ما هو؟

Carnelia VPN هو عميل Android لتجاوز الرقابة، مكتوب من الصفر بلغة Kotlin و Jetpack Compose فوق `VpnService` الأصلي. لا واجهة منسوخة — كود ومعمارية خاصّة به. يحمل بداخله عدة نوى أصلية ويبدّل بينها حسب بروتوكول الخادم.

---

## النوى

| النواة | المكتبة الأصلية | مسؤولة عن |
|---|---|---|
| **Xray-core** | `libxray_core.so` | VLESS / VMess / Trojan / Shadowsocks / WireGuard |
| **sing-box** | `libsingbox.so` | Hysteria2 / TUIC / وسائل النقل الحديثة |
| **AmneziaWG** | `libamneziawg.so` | تمويه AWG أصلي (Jc / S1-S4 / H1-H4 / I1-I5) |
| **Hysteria2** | `libhysteria2.so` | بروتوكول Hysteria2 عبر QUIC |
| **hev-socks5-tunnel** | `libhev-socks5-tunnel.so` | tun2socks للبروتوكولات الوكيلة |
| **Cloudflare WARP** | (WireGuard) | تسجيل WARP والاتصال به |

---

## البروتوكولات

VLESS (REALITY / TLS / WSS / gRPC)، VMess، Trojan، Shadowsocks، WireGuard، AmneziaWG، Hysteria2، TUIC، Cloudflare WARP.

الاستيراد عبر `vless://` `vmess://` `ss://` `trojan://` `wireguard://` `hysteria2://` `tuic://`، بالإضافة إلى إعدادات INI لـ WireGuard/AmneziaWG وإعدادات Amnezia JSON.

---

## الميزات

- **Black Wall — محرّك مضاد لـ DPI:** تجزئة TLS ClientHello، تمويه SNI، حركة ضوضاء
- **قاطع الاتصال** (داخل التطبيق + إرشاد Always-on للنظام) وحماية من تسرّب IPv6
- **النفق المُقسّم** مع اختيار لكل تطبيق
- **الاتصال التلقائي** عند التشغيل / تغيّر الشبكة، وإعادة اتصال سلسة عند التبديل بين Wi-Fi وبيانات الجوال
- **النفق المزدوج** (متعدّد القفزات) لإخفاء عنوان IP الحقيقي عن خادم الخروج
- استيراد الخوادم عبر URI، اشتراكات ذات تحديث تلقائي، نسخ احتياطي مشفّر
- إحصاءات حركة البيانات في الوقت الفعلي، رسم وخريطة؛ سجلات النواة داخل التطبيق
- **مركز الأدوات:** تدقيق DNS، اختبار التسرّب، فاحص الحزم، متتبّع المسار، فاحص TLS، فحص البصمة، ماسح المنافذ، طرفية مدمجة، كشف الـ root/المحاكي
- **الخصوصية:** 🆘 Panic (قطع فوري + مسح السجلات)، 🎭 تمويه الأيقونة (آلة حاسبة / ملاحظات)، تزييف GPS
- **دردشة P2P** عبر Mainline DHT العامة (شبكة BitTorrent) — دون خادم خاص
- **5 لغات:** English، Русский، Español، 中文، العربية

---

## البدء السريع

**المتطلبات:** JDK 17+، Android SDK (API 34)، ADB

```powershell
cd android
.\gradlew.bat assembleRelease --no-daemon
```

مخرجات APK الموقّع: `android/app/build/outputs/apk/release/` ‏(arm64-v8a + x86_64).

تُقرأ أسرار التوقيع من `keystore.properties` (المُتجاهَل في git) أو من متغيّرات البيئة — لا كلمات مرور في المستودع.

---

## الترخيص

GPLv3. انظر [LICENSE](LICENSE).

## الخصوصية

لا تحليلات، لا خوادم، لا حسابات. التفاصيل في [PRIVACY.md](PRIVACY.md).
