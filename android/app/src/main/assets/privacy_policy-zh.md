# 隐私政策 — Carnelia VPN

**最后更新：** 2026 年 7 月

---

## 简述

Carnelia VPN 不收集、不存储、不传输任何个人数据。
没有分析服务器。没有遥测。无需注册。没有账户。

应用保存的一切都留在你的设备上。我们没有自己的服务器，因此无从得知你是谁、你在做什么。

---

## 我们不收集什么

我们不收集、也无法访问：

- 你的网络流量、DNS 查询或访问过的网站
- 你的 IP 地址、位置或设备信息
- 连接日志（时间戳、会话时长）
- 任何用户标识符
- 崩溃报告、事件或使用指标

应用中不含任何分析 SDK（没有 Firebase、Amplitude、Mixpanel 等）。

---

## 本地存储在你设备上的内容

应用**仅在你的设备上**通过 Android SharedPreferences 和内部存储保存数据：

| 数据 | 存储位置 | 目的 |
|---|---|---|
| 服务器配置（名称、地址、端口、协议、UUID） | SharedPreferences | 运行 VPN |
| 订阅 URL | SharedPreferences | 更新服务器列表 |
| 设置（网络断路器、分片、噪声等） | SharedPreferences | 应用偏好 |
| 核心日志（可选） | 内部存储 | 调试 — 卸载应用时删除 |
| 加密的服务器备份（可选） | 你选择的文件 | AES-256，由你的密码保护 |

这些数据**绝不离开你的设备**，在任何情况下我们都无法访问。

---

## 订阅（Subscription URL）

如果你添加了订阅 URL，应用会定期访问该地址以获取最新的服务器列表。此请求**直接**从你的设备发往订阅服务器 — 我们不是中间人，既看不到请求也看不到响应。该订阅服务器如何处理数据由其提供者负责，与我们无关。

---

## VPN 流量

Carnelia VPN 使用你选择的协议（VLESS、VMess、Trojan、Hysteria2、WireGuard 等）建立加密隧道。你的流量通过**你自己指定的 VPN 服务器**传输。我们不运营这些服务器，也不对其运营者的政策负责。请选择你信任的服务器。

---

## Android 权限

应用请求以下权限：

| 权限 | 原因 |
|---|---|
| `BIND_VPN_SERVICE` | 通过 Android VpnService API 建立 VPN 隧道 |
| `INTERNET` | 连接 VPN 服务器并下载订阅 |
| `FOREGROUND_SERVICE` | 在屏幕关闭时让 VPN 在后台运行 |
| `RECEIVE_BOOT_COMPLETED` | 设备开机时自动连接（若已启用） |
| `ACCESS_FINE_LOCATION` *（可选）* | 仅用于 GPS 伪装功能（GeoSpoof）— 单独请求 |
| `MOCK_LOCATION` *（可选）* | 仅用于 GPS 伪装功能 |

这些权限均不用于收集你的数据。

---

## GPS 伪装（GeoSpoof）

GPS 伪装功能会更改 Android 报告给各应用的坐标。它完全在本地运行 — 任何坐标（真实或虚假）都不会发送给我们或任何第三方。它需要在 Android 开发者选项中启用“模拟位置应用”。

---

## P2P 聊天

可选的 P2P 聊天基于公共 Mainline DHT（BitTorrent 网络）运行，采用端到端加密。我们没有任何聊天服务器；消息在对等端之间直接交换。我们无法读取、存储或转发它们。

---

## 儿童

本应用不面向 13 岁以下人群。我们不收集任何数据，包括来自儿童的数据。

---

## 政策变更

若政策发生重大变更，我们会更新本文顶部的日期。完整的变更历史可在仓库的 git 历史中查看。

---

## 联系方式

隐私相关问题：在 [github.com/voksed/carnelia-vpn/issues](https://github.com/voksed/carnelia-vpn/issues) 提交 issue。
