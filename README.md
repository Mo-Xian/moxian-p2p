# moxian-p2p

轻量级内网穿透工具，基于 UDP 打洞 + KCP + smux。类 OpenP2P 架构：**自建信令服务器 + STUN 探测 + P2P 直连 + 中继兜底**。

## 特性

- **不依赖公网 IP**：双方均可在 NAT 后，通过公网 VPS 上的信令/中继协调
- **优先直连**：UDP 打洞成功后 0 流量经过服务器
- **中继兜底**：对称 NAT 打洞失败时自动降级为 UDP 中继
- **NAT 类型检测 + 端口预测**：双 STUN 端点检测 Cone/Symmetric；对称 NAT 对端自动端口扫描打洞
- **可靠传输**：KCP（低延迟可调参）+ smux（多路复用，一个隧道跑多个 TCP 连接）
- **端到端加密**：会话密钥 = `sha256(passphrase + session_id)`，中继服务器看不到明文
- **TUN 虚拟局域网**（Linux/macOS/Windows）：节点分配 vIP 后相互直通，类似 Tailscale。Windows 基于 Wintun
- **节点发现**：`-tags` / `-desc` 元数据，服务器广播全量节点列表；`-list` 仅查询
- **UPnP 自动开端口**：`-upnp` 向家用路由器请求 UDP 映射，作为额外打洞候选
- **Mesh 自动组网**：`-mesh` 启动后自动与所有 peer 建立 P2P 隧道
- **流量统计**：`-stats-log 30s` 定期打印；`-stats-http 127.0.0.1:7800` 暴露 JSON API
- **非线性 NAT 预测**：支持 N 个额外 STUN 端点采样，基于步长分布生成密集打洞候选
- **YAML 配置文件**：`-config file.yaml`（CLI flag 优先），见 `examples/`
- **信令断线重连**：WS 断开后指数退避自动重连（1→2→4→...→60 秒）
- **节点 ACL**：`allow_peers` 支持精确 node_id / `tag=value` / `*`，双向校验
- **流量限速**：客户端 `rate_limit`（全局出站）/ 服务器 `relay_limit`（per-session 中继）
- **Prometheus /metrics**：服务器 + 客户端均暴露标准 metrics 端点
- **wss/TLS**：服务器原生支持 TLS 证书
- **Web 控制台**：`/admin` 查看在线节点、NAT 类型、虚拟 IP
- 单文件部署，Windows/Linux/macOS 通用

## 架构

```
┌─────────┐    注册/信令   ┌──────────────┐   注册/信令   ┌─────────┐
│ Node A  │ ────WS─────▶  │   Server     │  ◀────WS──── │ Node B  │
│ (内网)  │               │ (公网 VPS)   │               │ (内网)  │
└───┬─────┘               │ 7788/tcp WS  │               └─────┬───┘
    │                     │ 7789/udp STUN│                     │
    │                     │      +Relay  │                     │
    │                     └──────────────┘                     │
    │                                                          │
    └──────── UDP 打洞直连（成功时，KCP+smux+AES）─────────────┘
```

## 目录

```
cmd/server/       # 信令服务器入口
cmd/client/       # 客户端入口
internal/protocol # 信令 JSON 消息定义
internal/server   # 信令 + UDP(STUN/Relay) 服务端
internal/nat      # UDP Mux / STUN 探测 / 打洞 / Channel(KCP PacketConn)
internal/tunnel   # KCP + smux + TCP 转发
internal/client   # 客户端主控
```

## 下载

预编译二进制在 [Releases](https://github.com/Mo-Xian/moxian-p2p/releases/latest) 页，按平台下载。

| 平台 | Server | Client | 说明 |
|------|--------|--------|------|
| Linux amd64 | `moxian-server-linux-amd64` | `moxian-client-linux-amd64` | x86_64 VPS / 服务器 |
| Linux arm64 | `moxian-server-linux-arm64` | `moxian-client-linux-arm64` | 树莓派 / 甲骨文 ARM / Graviton |
| Windows amd64 | `moxian-server.exe` | `moxian-client.exe` / `moxian-gui.exe` | Windows 桌面（GUI 版为系统托盘图标） |
| macOS amd64 | - | `moxian-client-darwin-amd64` | Mac Intel（Apple Silicon 走 Rosetta） |
| Android | - | `moxian-p2p-debug.apk` | Android 7.0+（arm64） |
| Windows TUN 驱动 | - | `wintun.dll` | 用 Windows TUN 模式时 放 exe 同目录 |

**命令行下载示例**（Linux）：

```bash
# 替换为 Releases 页最新 tag
VER=v0.5.1
curl -LO https://github.com/Mo-Xian/moxian-p2p/releases/download/$VER/moxian-server-linux-amd64
curl -LO https://github.com/Mo-Xian/moxian-p2p/releases/download/$VER/moxian-client-linux-amd64
chmod +x moxian-*
```

配置文件模板见 [`examples/`](examples/)。

## 从源码构建

需要 Go 1.23+：

```bash
git clone https://github.com/Mo-Xian/moxian-p2p.git
cd moxian-p2p
go build -o bin/moxian-server ./cmd/server
go build -o bin/moxian-client ./cmd/client
# 交叉编译
GOOS=linux GOARCH=amd64 go build -o bin/moxian-server-linux-amd64 ./cmd/server
GOOS=linux GOARCH=arm64 go build -o bin/moxian-client-linux-arm64 ./cmd/client
```

Android APK（需 Android SDK 34 + JDK 17）：

```bash
# 先把 arm64 client 放进 jniLibs（APK 通过这个机制嵌入二进制）
GOOS=linux GOARCH=arm64 go build -o android/app/src/main/jniLibs/arm64-v8a/libmoxianclient.so ./cmd/client
cd android && ./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 部署

### 1. 服务器（公网 VPS）

```bash
# 放行 7788/tcp 以及 7789+7790/udp
moxian-server \
  -host YOUR.VPS.DOMAIN \
  -ws :7788 \
  -udp :7789 -udp2 :7790 \
  [-token GLOBAL_SECRET] \
  [-tls-cert fullchain.pem -tls-key privkey.pem]          # 启用 wss
  [-admin-user admin -admin-pass <pwd>]                   # 开 Web 面板
  [-stun-extra ":7791,:7792,:7793"]                       # 非线性 NAT 预测（3~5 个足够）
```

- `7789/udp` 主端口：STUN + 中继
- `7790/udp` 副端口：仅用于 NAT 类型检测
- 启用 TLS 后用 `wss://...` 连接；Web 面板在 `/admin`（Basic Auth）

### 2. 被动侧（内网机器 B，想被访问的那台）

```bash
moxian-client \
  -id nodeB \
  -server ws://YOUR.VPS:7788/ws \
  -udp YOUR.VPS:7789 \
  -pass SHARED_SECRET \
  -allow 127.0.0.1:22,127.0.0.1:3389
```

`-allow` 是白名单（留空则允许任意目标）。

### 3. 主动侧（机器 A，发起连接）

```bash
moxian-client \
  -id nodeA \
  -server ws://YOUR.VPS:7788/ws \
  -udp YOUR.VPS:7789 \
  -pass SHARED_SECRET \
  -forward 127.0.0.1:2222=nodeB=127.0.0.1:22 \
  -forward 0.0.0.0:3389=nodeB=127.0.0.1:3389
```

A 上访问 `ssh -p 2222 127.0.0.1` 即连到 B 上的 22 端口。

`-forward` 格式：`本地监听地址 = 对端 node_id = 对端要连的 host:port`，可多次。

## 协议细节

### 信令（WebSocket / JSON）

| type | 方向 | 说明 |
|------|------|------|
| `register` | C→S | 节点注册，携带 STUN 探到的公网 UDP 地址 + 内网候选 |
| `register_ack` | S→C | 回执，含中继 UDP 端点 |
| `connect` | C→S | 发起方请求连接某 peer |
| `peer_info` | S→C×2 | 下发双方地址 + session_id + role，触发同时打洞 |
| `relay_open` | C→S | 打洞失败 请求开中继 |
| `relay_ready` | S→C | 回中继地址 + token |
| `ping`/`pong` | 双向 | 保活 |

### UDP 首字节

```
0x01 STUN_REQ      [0x01][4 nonce]
0x02 STUN_RESP     [0x02][4 nonce][1 len][addr text]
0x10 RELAY_BIND    [0x10][1 len][sid][1 len][token]
0x12 RELAY_ACK     [0x12][1 len][sid][1 status]
0x11 RELAY_DATA    [0x11][1 len][sid][payload]    -> 服务端转发给另一侧
0x20 PUNCH_PING    [0x20][1 len][sid][4 nonce]
0x21 PUNCH_PONG    [0x21][1 len][sid][4 nonce]
0x30 DATA_DIRECT   [0x30][1 len][sid][payload]    -> 直连模式下的 KCP 数据
```

## 虚拟局域网（TUN）

以 Linux 为例（需 root 或 CAP_NET_ADMIN）：

```bash
# Node A（vip 10.88.0.2）
sudo ./moxian-client-linux-amd64 \
  -id nodeA -server ws://vps:7788/ws -udp vps:7789 -pass SECRET \
  -vip 10.88.0.2

# Node B（vip 10.88.0.3）
sudo ./moxian-client-linux-amd64 \
  -id nodeB -server ws://vps:7788/ws -udp vps:7789 -pass SECRET \
  -vip 10.88.0.3

# 从 A 访问 B
ping 10.88.0.3
ssh 10.88.0.3
```

- 每个节点启动时分配虚拟 IP，服务器维护路由表广播给所有节点
- IP 包从 TUN 流入，查路由表找到对端 node_id，经 P2P 隧道转发
- 子网默认 `/24`（`-vnet 24`），多节点需在同一网段

### Windows（Wintun）

1. 下载 `wintun.dll` https://www.wintun.net/，放在 `moxian-client.exe` 同目录
2. **以管理员身份**运行 cmd/PowerShell
3. 启动：
   ```
   moxian-client.exe -id winbox -server ws://vps:7788/ws -udp vps:7789 -pass SECRET -vip 10.88.0.4
   ```

程序自动创建名为 `moxian` 的 Wintun 网卡并通过 `netsh` 配 IP。

## 节点发现

```bash
# 注册时带元数据（tags 逗号分隔）
moxian-client -id homeNAS -server ... -pass X \
  -tags "group=home,role=fileserver" -desc "家里的 NAS"

# 仅查询在线节点列表后退出（无需 pass）
moxian-client -id scanner -server ws://vps:7788/ws -udp vps:7789 -list
```

输出：
```
[discovery] 3 node(s) online:
[discovery]     homeNAS      vip=10.88.0.2  nat=cone       [group=home,role=fileserver] 家里的 NAS
[discovery]     laptop       vip=-          nat=symmetric  [group=home]
[discovery]   * scanner      vip=-          nat=cone
```

`*` 标记自己。Web 面板（`/admin`）同时显示所有元数据与上线时间。

## 进阶用法

### Mesh + 统计

```bash
# A 节点：自动与所有在线节点 P2P 连接，流量日志 30s 一次，HTTP 统计接口
moxian-client -id alpha -server ws://vps:7788/ws -udp vps:7789 -pass SECRET \
  -mesh -upnp \
  -vip 10.88.0.2 \
  -stats-log 30s -stats-http 127.0.0.1:7800

curl http://127.0.0.1:7800/stats
# {"node_id":"alpha","sessions":[{"session_id":"...","peer_id":"beta","mode":"direct","rx_bytes":...,"tx_bytes":...,"rx_pkts":...,"tx_pkts":...}]}
```

服务器端：`curl -u user:pass http://vps:7788/api/stats` 返回中继累计字节数和 STUN 请求数。

### 非线性 NAT 预测

```bash
# 服务器开 3 个额外 STUN 端口
moxian-server -host vps.com -udp :7789 -udp2 :7790 -stun-extra ":7791,:7792,:7793" ...

# 客户端自动使用所有端点采样 NAT 行为
# 若 NAT 端口分配抖动（min_stride..max_stride）差异 ≥5，自动生成区间密集候选
# 日志: [client] nat_type=symmetric samples=5 first=... last=...
```

## 配置文件示例

见 `examples/server.yaml` / `examples/client.yaml`。CLI 与配置文件字段对应表：

| CLI flag | YAML key | 说明 |
|----------|----------|------|
| `-id` | `node_id` | 节点 ID |
| `-server` | `server` | 信令 WS 地址 |
| `-udp` | `server_udp` | UDP 服务器地址 |
| `-pass` | `pass` | 共享密钥 |
| `-tags` | `tags` | 标签 |
| `-allow-peers` | `allow_peers` | ACL 规则 |
| `-forward` | `forwards` (list) | 端口转发 |
| `-vip` | `virtual_ip` | TUN 虚拟 IP |
| `-upnp` | `upnp` | UPnP |
| `-mesh` | `mesh` | 自动组网 |
| `-rate-limit` | `rate_limit` | 出站限速 |

服务器端类似：`-host/-ws/-udp/-stun-extra/-tls-cert/-tls-key/-admin-user/-admin-pass/-relay-limit`。

## Prometheus

服务器 `/metrics`（HTTP 端口）：
```
moxian_nodes_online
moxian_relay_bytes_total
moxian_relay_packets_total
moxian_stun_requests_total
```

客户端 `/metrics`（`stats_http` 地址）：
```
moxian_client_sessions_active{node_id="..."}
moxian_client_tx_bytes_total{node_id="..."}
moxian_client_rx_bytes_total{node_id="..."}
```

## 限制 / TODO

- [ ] 随机端口分配 NAT 仍然打不通（需中继）
- [ ] Windows TUN 需要 `wintun.dll` 和管理员
- [ ] 中继无整体带宽限流（只有 per-session）
- [ ] 信令不支持水平扩展（单机），多 VPS 分片需外部 LB

## 开发

```bash
# 本地三方回环测试
./bin/moxian-server.exe -host 127.0.0.1 -ws :17788 -udp :17789
./bin/moxian-client.exe -id nodeB -server ws://127.0.0.1:17788/ws -udp 127.0.0.1:17789 -pass x -allow 127.0.0.1:19999
./bin/moxian-client.exe -id nodeA -server ws://127.0.0.1:17788/ws -udp 127.0.0.1:17789 -pass x -forward 127.0.0.1:15000=nodeB=127.0.0.1:19999
```

License: MIT
