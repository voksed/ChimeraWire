# Third-party components

Carnelia VPN bundles the following native cores and libraries. Their licenses
apply to the respective binaries shipped inside the APK (`android/app/src/main/jniLibs/`).

| Component | File | License | Upstream |
|---|---|---|---|
| sing-box | `libsingbox.so` | **GPL-3.0-or-later** | https://github.com/SagerNet/sing-box |
| Xray-core | `libxray_core.so` | MPL-2.0 | https://github.com/XTLS/Xray-core |
| Hysteria2 | `libhysteria2.so` | MIT | https://github.com/apernet/hysteria |
| AmneziaWG (amneziawg-go) | `libamneziawg.so` | MIT | https://github.com/amnezia-vpn/amneziawg-go |
| hev-socks5-tunnel | `libhev-socks5-tunnel.so` | MIT | https://github.com/heiher/hev-socks5-tunnel |
| tun2socks bridge | `libgojni.so`, `tun2socks.aar` | see upstream | https://github.com/xjasonlyu/tun2socks |

Because **sing-box is licensed under GPL-3.0**, the combined work (the Carnelia VPN
APK) is distributed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).
This is a copyleft ("share-alike") obligation: any redistributed or modified build
must also be offered under GPL-3.0 with source available.

> Note: license identifiers above reflect each project's upstream license at the time
> of writing. If you fork or redistribute, verify each dependency against its current
> upstream license.
