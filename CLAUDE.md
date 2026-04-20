# CLAUDE.md - moxian-p2p

## 项目

- **路径**：`D:\AIwork\projects\moxian-p2p`
- **级别**：个人级（不计入周报）
- **语言/工具**：Go 1.23+，依赖 gorilla/websocket、xtaci/kcp-go、xtaci/smux、google/uuid
- **定位**：类 OpenP2P 的内网穿透工具，UDP 打洞 + KCP + smux，公网 VPS 只做信令和中继兜底

## 构建

```bash
go build -o bin/moxian-server.exe ./cmd/server
go build -o bin/moxian-client.exe ./cmd/client
```

Go 安装路径：`D:\CP12064\install\go\bin\go.exe`（shell 中需 `export PATH="/d/CP12064/install/go/bin:$PATH"` + `export GOPATH="/c/Users/CP12064/go"`，因为系统 GOPATH 被错设为 GOROOT）。

## 目录

- `android/` — Android 测试 APP（Kotlin + 内嵌 Linux arm64 二进制 + ProcessBuilder）

| 路径 | 职责 |
|------|------|
| `cmd/server` | 信令服务器入口（WS + UDP STUN/Relay 合一） |
| `cmd/client` | 客户端入口（同一二进制，通过 `-forward` / `-allow` 切主被动） |
| `internal/protocol` | 信令 JSON 消息定义 + Pack/Unpack |
| `internal/server` | Hub（节点注册表）、Signaling（WS handler）、UDPServer（STUN + Relay） |
| `internal/nat` | Mux（单 UDP socket 多路复用）、Channel（KCP 用 PacketConn）、Punch（打洞收敛逻辑） |
| `internal/tunnel` | KCP 调参、smux 会话、TCP 本地监听/远端 dial |
| `internal/client` | 客户端主控：注册、信令读循环、forward/responder 生命周期 |

## 关键设计

- **单 UDP socket 复用**：客户端 STUN、打洞、数据、中继共用一个 UDP 端口，保证 NAT 映射一致
- **Mux 分发**：UDP 首字节决定消息类型（0x01~0x30），各自路由到 waiter / hook / channel
- **Channel 是 net.PacketConn**：KCP 直接跑在上面，Channel 根据 `ModeDirect`/`ModeRelay` 自动选目标地址和 magic byte
- **双向打洞**：双方同时发 PUNCH_PING，收到 ping 立即回 pong，收到 pong 认为打洞成功；失败后走 RelayOpen
- **会话加密**：KCP AES 密钥 = `sha256(passphrase + session_id)`，中继服务器无法解密

## CLI 约定

```
server: -host PUBLIC_HOST -ws :PORT -udp :PORT [-token SECRET]
client: -id NODEID -server ws://... -udp host:port -pass SHARED
        [-allow host:port,...]                       # 被动侧白名单
        [-forward LOCAL=PEER=TARGET ...]             # 主动侧映射 可多次
```

## 已完成功能

- v0.1 MVP：信令 + STUN + UDP 打洞 + KCP+smux + 端口转发 + 中继兜底
- v0.2：wss/TLS、双 STUN 端口 NAT 检测 + 对称 NAT 端口预测打洞、Web 管理面板、Linux/macOS TUN
- v0.3：Windows Wintun（需 `wintun.dll` 同目录 + 管理员）、节点发现（tags/desc/`-list`）+ 全量广播
- v0.4：UPnP（`-upnp`）、流量统计（Channel 级 rx/tx 字节/包 + HTTP /stats + 服务器中继计数）、Mesh 自动组网（`-mesh` + peerPool 轮询 IsClosed 重连）、非线性 NAT 端口预测（多 STUN 端点采样 + stride 分布）
- v0.5：YAML 配置文件（`-config`，CLI 优先）、信令 WS 断线重连（指数退避 1→60s）、节点 ACL（`allow_peers` 支持 node_id / `tag=value` / `*`，双向校验）、流量限速（`rate_limit` 客户端出站 + `relay_limit` 服务器 per-session，x/time/rate）、Prometheus `/metrics`（server + client）
- v0.5.1：TUN responder 端回包 bug 修复（peerpool 双向注册 + ServeIncoming both sides）、mesh 重连（监听 OnlineSince 变化）、sendTo 帧原子性（hdr+pkt 合并一次 Write 避免并发 race）、Android 前台 Service、连接状态指示器、一键测试/复制、`-v` debug 开关

## 扩展方向（剩余）

- 中继整体带宽限流（目前仅 per-session）
- 信令水平扩展（多服务器分片）
- 随机端口 NAT 的启发式打洞（代价高 收益小）

## 注意

- 双端 `-pass` 必须一致，不一致时 KCP 握手静默失败看起来像"卡住"
- Windows 防火墙可能挡 UDP 入站，测试时要放行
- 中继模式下 VPS 带宽就是瓶颈
