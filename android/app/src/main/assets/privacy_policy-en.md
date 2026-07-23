# Privacy Policy — Carnelia VPN

**Last updated:** July 2026

---

## In short

Carnelia VPN does not collect, store, or transmit any personal data.
No analytics servers. No telemetry. No sign-up. No accounts.

Everything the app stores stays on your device. We have no servers of our own and therefore no way to see who you are or what you do.

---

## What we do NOT collect

We do not collect and have no access to:

- your internet traffic, DNS queries, or visited sites
- your IP address, location, or device information
- connection logs (timestamps, session duration)
- any user identifier
- crash reports, events, or usage metrics

The app contains no analytics SDK whatsoever (no Firebase, Amplitude, Mixpanel, or similar).

---

## What is stored locally on your device

The app saves data **only on your device** via Android SharedPreferences and internal storage:

| Data | Stored in | Purpose |
|---|---|---|
| Server configurations (name, address, port, protocol, UUID) | SharedPreferences | Running the VPN |
| Subscription URLs | SharedPreferences | Updating the server list |
| Settings (Kill Switch, fragmentation, noise, etc.) | SharedPreferences | App preferences |
| Core logs (optional) | Internal storage | Debugging — removed when the app is uninstalled |
| Encrypted server backup (optional) | File you choose | AES-256, protected by your password |

This data **never leaves your device** and is not accessible to us under any circumstances.

---

## Subscriptions (Subscription URL)

If you add a subscription URL, the app periodically contacts that address to fetch an up-to-date server list. This request goes **directly** from your device to the subscription server — we are not a middleman and see neither the request nor the response. How that subscription server handles data is the responsibility of its provider, not ours.

---

## VPN traffic

Carnelia VPN builds an encrypted tunnel using the protocol you chose (VLESS, VMess, Trojan, Hysteria2, WireGuard, etc.). Your traffic passes **through the VPN server you specified yourself**. We do not operate these servers and are not responsible for their operators' policies. Choose servers you trust.

---

## Android permissions

The app requests the following permissions:

| Permission | Reason |
|---|---|
| `BIND_VPN_SERVICE` | Building the VPN tunnel via the Android VpnService API |
| `INTERNET` | Connecting to the VPN server and downloading subscriptions |
| `FOREGROUND_SERVICE` | Keeping the VPN running in the background while the screen is off |
| `RECEIVE_BOOT_COMPLETED` | Auto-connect at device boot (if enabled) |
| `ACCESS_FINE_LOCATION` *(optional)* | Only for the GPS spoofing feature (GeoSpoof) — requested separately |
| `MOCK_LOCATION` *(optional)* | Only for the GPS spoofing feature |

None of these permissions is used to collect data about you.

---

## GPS spoofing (GeoSpoof)

The GPS spoofing feature changes the coordinates Android reports to apps. It works entirely locally — no coordinates (real or fake) are ever sent to us or any third party. It requires enabling "Mock location app" in Android developer options.

---

## P2P chat

The optional P2P chat runs over the public Mainline DHT (the BitTorrent network) with end-to-end encryption. There is no chat server of ours; messages are exchanged directly between peers. We cannot read, store, or relay them.

---

## Children

The app is not intended for anyone under 13. We collect no data, including from children.

---

## Changes to this policy

If the policy changes materially, we will update the date at the top of this document. The full change history is available in the repository's git history.

---

## Contact

Questions about privacy: open an issue at [github.com/voksed/carnelia-vpn/issues](https://github.com/voksed/carnelia-vpn/issues).
