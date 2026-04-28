# moxian-p2p

轻量自托管的 P2P 内网穿透工具：UDP 打洞 + KCP + smux + 零知识加密多用户管理。

```
┌─────────┐  WS 信令 + STUN  ┌──────────────┐  WS 信令 + STUN  ┌─────────┐
│ Node A  │ ───────────────▶│ moxian-server│◀───────────────│ Node B  │
│ (内网)  │                 │ 公网 VPS     │                │ (内网)  │
└────┬────┘                 │ + Web 面板   │                └────┬────┘
     │                      │ + APK 分发   │                     │
     │                      └──────────────┘                     │
     │                                                           │
     └────── UDP 打洞直连（KCP+smux+AES 端到端加密）────────────┘
```

- **零知识**：服务器只存 Argon2id(pwdHash)，无法解用户的 P2P 配置 / vault
- **多用户**：邀请码注册，每个用户独立 mesh subnet 与节点列表，互不可见
- **打洞优先 + 中继兜底**：对称 NAT 失败时自动降级 UDP 中继
- **TUN 虚拟局域网**（Linux/macOS/Windows Wintun/Android VpnService）：节点间 vIP 直通
- **Android 内置 NAS 客户端**：Jellyfin / Immich / Navidrome / Vaultwarden / AdGuard 等

---

## 快速开始

### 服务器（公网 VPS，Linux）

```bash
curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-server.sh | sudo bash
```

自动完成：下载 binary、生成 JWT/CI token、自签 TLS（IP SAN 10 年）、systemd unit、防火墙、提示去 GitHub 配 secrets。

完成后浏览器开 `https://你的VPS_IP:7788/`：
1. 选 **注册** Tab → 邮箱 + 用户名 + 主密码 + 邀请码留空 → **首位用户自动成管理员**
2. 之后注册的用户需管理员生成邀请码

### Windows 客户端（GUI）

管理员 PowerShell 一行：

```powershell
irm https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.ps1 | iex
```

询问邮箱 / 主密码 / 节点名，写 `C:\Program Files\moxian-p2p\client.yaml` + 创建桌面快捷方式（带管理员标记）。完成后双击桌面图标启动，托盘出现图标 → 自动登录拉配置 → 连接。

重跑脚本 = 升级 binary（凭据保留 + 已是最新版跳过下载）。

### Linux 客户端

```bash
curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.sh | sudo bash
```

写凭据到 `/etc/moxian/client.env`，systemd unit 自启。

### Android

最简单：浏览器打开服务器 Web 面板（管理员上传 APK 后）→ 装 APK → 用同一账号登录 → 内置自动更新。

或直接下载：
```
https://github.com/Mo-Xian/moxian-p2p/releases/latest
→ moxian-p2p-debug.apk
```

---

## v2 架构

### 零知识加密

```
浏览器/APP 端                            server
─────────────────                       ────────
PBKDF2(password, email, 600k) → masterKey
PBKDF2(masterKey, password, 1) → pwdHash ──→ Argon2id(pwdHash) → users.password_hash
                                              （server 无法反推 password 或 masterKey）

masterKey HKDF-stretch → encKey + macKey
vault JSON ──AES-CBC + HMAC-SHA256──→ encrypted_vault ──→ users.encrypted_vault
                                                          （server 无法解密）
```

- 注册 / 登录 / 解锁 vault 全在客户端做 KDF，服务端只接收 base64(pwdHash)
- 主密码忘了 → 管理员 Web 面板"重置密码"→ vault 清空，P2P 凭据保留

### 配置下发

```
client.yaml（用户面，仅 5 字段）              server 内部（per-user）
──────────────────────────────              ──────────────────────
server: https://vps:7788                     user_mesh_keys.passphrase
email: x@y.com                               nodes.virtual_ip (10.88.<uid>.<n>)
password: ...                                allow_peers (此用户的所有 node_id)
node: laptop                                 server_ws / server_udp（公开）
insecure_tls: true
                  ↓ 启动时登录拉
client.Config（运行时）
──────────────────────
ServerURL / ServerUDP / NodeID / Passphrase / VirtualIP
```

GUI / Android 启动时调 `/api/auth/login` + `/api/config`，**真实 P2P 凭据从不在 yaml 里持久化**（除了 server URL 和登录密码）。

### APK 自托管分发

国内连 GitHub 不稳。server 内置 release 端点：

| 端点 | 用途 |
|------|------|
| `POST /api/release/ci-upload` | CI 流水线推 APK（X-Release-Token header）|
| `POST /api/admin/release/upload` | Web 面板手动上传（multipart）|
| `POST /api/admin/release/promote` | 设某 tag 为 latest |
| `GET  /api/release/latest` | APP 自动更新查这个（公开）|
| `GET  /releases/<tag>/<file>` | 静态文件下载（公开）|

CI 配置：repo Settings → Secrets and variables → Actions 加两条 Repository secrets：
- `MOXIAN_RELEASE_URL` = `https://你的VPS:7788`
- `MOXIAN_RELEASE_TOKEN` = `cat /etc/moxian/ci_release.token` 的输出

之后 `git tag v*` 推送 → CI 自动 build → 自动推到 VPS → APP 端"检查更新"立刻拿到。

---

## CLI 模式（高级 / 不走 v2 登录）

旧版 CLI 仍可用。直接传 P2P 凭据：

```bash
moxian-client \
  -id nodeA \
  -server ws://vps:7788/ws \
  -udp vps:7789 \
  -pass SHARED_SECRET \
  -forward 127.0.0.1:2222=nodeB=127.0.0.1:22
```

或 `-config file.yaml` 加载（含 server/email/password 可触发 v2 login + fetch 自动拉配置）：

```yaml
server: https://vps:7788
email: x@y.com
password: secret
node: laptop
insecure_tls: true
mesh: true
```

服务器：

```bash
moxian-server \
  -host YOUR.VPS.DOMAIN \
  -ws :7788 -udp :7789 -udp2 :7790 \
  -tls-cert cert.pem -tls-key key.pem \
  -db /var/lib/moxian/moxian.db \
  -release-dir /var/lib/moxian/releases \
  -release-ci-token "$(cat /etc/moxian/ci_release.token)" \
  -jwt-secret "$(cat /etc/moxian/jwt.secret)"
```

---

## 从源码构建

需要 Go 1.23+：

```bash
go build -o bin/moxian-server ./cmd/server
go build -o bin/moxian-client ./cmd/client
go build -ldflags "-H=windowsgui" -o bin/moxian-gui.exe ./cmd/moxian-gui
```

Android（需 Android SDK 34 + JDK 17 + Go 1.23 + gomobile）：

```bash
gomobile bind -target=android/arm64 -androidapi 24 \
  -javapkg=com.cp12064.moxianp2p \
  -o android/app/libs/moxian.aar ./mobile
cd android && ./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

CI 自动构建：tag `v*` 推送触发 `.github/workflows/release.yml`，发 GitHub Release + 推 VPS。

---

## TUN 虚拟局域网

每个用户独立 subnet `10.88.<uid%256>.<n>/24`。注册节点后由 server 分配 vIP，启动后 OS 创建 TUN 网卡，A→B 直接走 vIP：

```bash
# Linux 节点 A 注册到 server 后，server 分配比如 10.88.42.2
# 节点 B 也属同一用户 → 10.88.42.3
# A 上：
ping 10.88.42.3
ssh 10.88.42.3
```

平台支持：
- **Linux / macOS**：原生 TUN（需 root 或 CAP_NET_ADMIN）
- **Windows**：Wintun（`wintun.dll` 同目录 + 管理员）
- **Android**：VpnService 内置（无需 root，APP 内一键启用）

---

## 协议（技术细节）

### 信令（WebSocket / JSON）

| type | 方向 | 说明 |
|------|------|------|
| `register` | C→S | 节点注册，携带 STUN 探到的公网 UDP 地址 + 内网候选 |
| `register_ack` | S→C | 回执 + 中继 UDP 端点 |
| `connect` | C→S | 发起方请求连接某 peer |
| `peer_info` | S→C×2 | 下发双方地址 + session_id + role，触发同时打洞 |
| `relay_open` | C→S | 打洞失败 请求开中继 |
| `relay_ready` | S→C | 回中继地址 + token |

### UDP 首字节路由

```
0x01 STUN_REQ      0x02 STUN_RESP
0x10 RELAY_BIND    0x12 RELAY_ACK    0x11 RELAY_DATA
0x20 PUNCH_PING    0x21 PUNCH_PONG
0x30 DATA_DIRECT
```

### 会话加密

```
session_key = sha256(passphrase + session_id)
KCP over UDP（直连 0x30 / 中继 0x11）+ AES-128
中继服务器只看到密文 + 转发头
```

### NAT 检测 + 端口预测

服务器开 N 个 STUN 端点（`-stun-extra`），客户端采样多端点的映射端口：
- 全部相同 → Cone NAT
- 同步增长 → Symmetric Linear（用 stride 预测对端端口）
- 跳跃随机 → Symmetric Random（自动走中继）

---

## 监控 / 运维

### Prometheus 指标

server `/metrics`：
```
moxian_nodes_online
moxian_relay_bytes_total
moxian_relay_packets_total
moxian_stun_requests_total
```

client `-stats-http 127.0.0.1:7800`：
```
moxian_client_sessions_active{node_id="..."}
moxian_client_tx_bytes_total{node_id="..."}
moxian_client_rx_bytes_total{node_id="..."}
```

### Web 面板

`https://VPS:7788/` 含：
- 我的节点（vIP / 在线状态）
- 邀请码（管理员）
- 用户列表 + 重置密码（管理员）
- APK 版本管理 + 上传（管理员）

---

## Android NAS 服务集成

APP 内置常用 NAS 服务的轻量客户端，免重复登录：

| 服务 | 类型 | 凭据存储 |
|------|------|---------|
| Jellyfin | 媒体（电影/剧集）| vault |
| Immich | 相册（照片备份）| vault |
| Navidrome | 音乐 | vault |
| Vaultwarden | 密码管理 | vault |
| AdGuard Home | 广告拦截配置 | vault |
| qBittorrent | 下载 | vault |
| Syncthing | 文件同步 | vault |

- 凭据加密存在用户 vault 中（端到端加密 service 端无法读）
- 通过 P2P 隧道访问内网部署的 NAS 服务（自动用 vIP）
- 复杂功能仍走原生 APP，本 APP 提供常用快速操作

---

## 目录结构

```
cmd/server/        # 信令服务器入口（含 v2 API）
cmd/client/        # CLI 客户端
cmd/moxian-gui/    # Windows 系统托盘 GUI
mobile/            # gomobile bind → Android AAR
internal/protocol  # 信令 JSON
internal/server    # 信令 + UDP + v2 API（auth/vault/config/admin/release）+ Web 面板
internal/nat       # UDP Mux / STUN / 打洞 / Channel
internal/tunnel    # KCP + smux + TCP 转发
internal/client    # 客户端主控 + AuthClient（v2 login）
android/           # Android APP（Kotlin）
scripts/           # 一键安装脚本
examples/          # YAML 配置模板
```

---

## 限制 / TODO

- [ ] 随机端口分配 NAT（仍需中继）
- [ ] 中继整体带宽限流（目前仅 per-session）
- [ ] 信令水平扩展（单机部署，多 VPS 需外部 LB）
- [ ] iOS 客户端

---

License: MIT
