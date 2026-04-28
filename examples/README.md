# moxian-p2p 配置示例

## ⭐ 一键安装脚本（推荐）

```bash
# VPS 服务端
curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-server.sh | sudo bash

# Linux 客户端（NAS / 树莓派）
curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.sh | sudo bash

# Windows 客户端（管理员 PowerShell）
irm https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.ps1 | iex

# Android: 装 APK 即可 https://github.com/Mo-Xian/moxian-p2p/releases/latest
```

详见 [`scripts/README.md`](../scripts/README.md)。

## 文件一览

### 配置文件参考（仅供查阅 安装脚本会自动生成）

| 文件 | 用途 |
|------|------|
| [`v2/README.md`](v2/README.md) | 配置文件位置、修改方式、卸载步骤 |
| [`v2/server.systemd`](v2/server.systemd) | 服务端 systemd unit 参考 |
| [`v2/client.env.example`](v2/client.env.example) | Linux 客户端凭据文件参考 |
| [`v2/client.systemd`](v2/client.systemd) | Linux 客户端 systemd unit 参考 |

### NAS 应用栈

| 文件 | 用途 |
|------|------|
| [`self-hosted-nas.md`](self-hosted-nas.md) | 端到端自建 NAS 部署指南（Debian + Immich + Jellyfin + moxian-p2p）|
| [`nas-stack/`](nas-stack/) | 自建 NAS 一键 Docker 栈（含 bootstrap.sh / bootstrap.ps1）|

## 典型架构

```
               公网 (VPS)
                moxian-server
              ╱     │     ╲
             ╱      │      ╲
    P2P 打洞      信令    中继兜底
           ╱        │        ╲
          ▼         │         ▼
    ┌─────────┐     │    ┌─────────┐
    │ 家里 PC │─────┼────│  手机   │
    │10.88.0.2│          │  forward│
    └─────────┘          └─────────┘
          ▲
          │
    ┌─────────┐
    │家里 NAS │
    │10.88.0.3│
    └─────────┘
```

## 虚拟 IP 规划

每个用户独立子网 `10.88.<uid%256>.0/24`，server 自动分配。一般无需手动规划：

| 节点 | vIP（示例）| 角色 |
|------|-----------|------|
| 家里 NAS | 10.88.42.2 | 7x24 在线，文件/服务中心 |
| 家里 Windows | 10.88.42.3 | 7x24 在线或开机时 |
| 笔记本 | 10.88.42.4 | 出门时 |
| 手机 | 10.88.42.5 | 移动 |
| 公司电脑 | 10.88.42.10 | 工作时 |

## 故障排查

| 症状 | 排查 |
|------|------|
| `[client] signaling not connected` | VPS server 没跑或防火墙拦 tcp 7788 |
| `nat_type=symmetric` | 移动网络 / 对称 NAT 走中继 (mode=2) |
| `[mesh] connected` 后秒断 | 对端重启了 保持会重新 dial |
| TUN ping 不通但 TCP 通 | Windows 防火墙拦 ICMP `netsh advfirewall firewall add rule name=ICMPv4-In protocol=icmpv4:8,any dir=in action=allow` |
| `create wintun: access denied` | 不是管理员 右键 exe 管理员运行 |
| 想开 debug 日志 | yaml 里 `verbose: true` |
| Web 面板登录失败 `crypto.subtle is undefined` | 必须 https 或 localhost 访问（Web Crypto 限制）|
| APP 自动更新拿不到 | 国内连 GitHub 不稳 用 admin 上传 APK 到 server `/api/admin/release/upload` |
