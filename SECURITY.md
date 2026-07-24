# Security Policy

## Supported versions

Only the latest release on the [Releases](https://github.com/voksed/carnelia-vpn/releases) page is supported. Please update before reporting an issue.

## Reporting a vulnerability

Please report security vulnerabilities **privately** — do **not** open a public issue for them.

- **Preferred:** use GitHub's private vulnerability reporting — open the
  [Security tab](https://github.com/voksed/carnelia-vpn/security) → **Report a vulnerability**.
- For non-sensitive matters, a regular [issue](https://github.com/voksed/carnelia-vpn/issues) is fine.

When reporting, please include:

- affected version (see Settings → About in the app)
- steps to reproduce
- the security impact

We aim to respond within a reasonable time and will credit reporters unless they prefer to stay anonymous.

## Scope

Carnelia VPN collects no data and runs no servers of its own — see the [Privacy Policy](PRIVACY.md).

**In scope:** the client app — traffic leaks (IPv6/DNS/WebRTC), kill-switch bypass, signature/update-channel issues, local data exposure, crashes with a security impact.

**Out of scope:** third-party VPN servers you choose to connect to, and the upstream cores bundled in the app (report those to their own projects — see [THIRD_PARTY.md](THIRD_PARTY.md)).
