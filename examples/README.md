# moxian-p2p 配置示例

## ⭐ v2 推荐方式：一键安装脚本（不用手填配置）

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

### v2（推荐）

| 文件 | 用途 |
|------|------|
| [`v2/README.md`](v2/README.md) | v2 配置文件位置、修改方式、卸载步骤 |
| [`v2/server.systemd`](v2/server.systemd) | 服务端 systemd unit 参考 |
| [`v2/client.env.example`](v2/client.env.example) | Linux 客户端凭据文件参考 |
| [`v2/client.systemd`](v2/client.systemd) | Linux 客户端 systemd unit 参考 |

### NAS 应用栈

| 文件 | 用途 |
|------|------|
| [`self-hosted-nas.md`](self-hosted-nas.md) | 端到端自建 NAS 部署指南（Debian + Immich + Jellyfin + moxian-p2p）|
| [`nas-stack/`](nas-stack/) | 自建 NAS 一键 Docker 栈（含 bootstrap.sh / bootstrap.ps1）|

### v1 LEGACY（不推荐 仅参考）

| 文件 | 状态 |
|------|------|
| `server.yaml` | ⚠️ v1 服务端 YAML（v2 不再读 YAML）|
| `client.yaml` | ⚠️ v1 客户端 YAML（v2 客户端从服务器拉配置）|
| `moxian-server.service` | ⚠️ v1 systemd unit（v2 用 install 脚本生成的）|
| `moxian-client.service` | ⚠️ v1 |

## 典型架构

```
               公网 (VPS 139.224.1.83)
                   moxian-server
                  ╱     │     ╲
                 ╱      │      ╲
        P2P 打洞      信令    中继兜底
               ╱        │        ╲
              ▼         │         ▼
        ┌─────────┐     │    ┌─────────┐
        │ 家里 PC │─────┼────│  手机   │
        │ winpc   │          │  phone  │
        │10.88.0.2│          │（forward│
        │ (TUN)   │          │  模式） │
        └─────────┘          └─────────┘
              ▲
              │
        ┌─────────┐
        │家里 NAS │
        │  nas    │
        │10.88.0.3│
        │ (TUN)   │
        └─────────┘
```

## VPS 部署（server）

```bash
# Windows 本地 上传二进制和配置
scp bin/moxian-server-linux-amd64 root@139.224.1.83:/usr/local/bin/moxian-server
scp examples/server.yaml           root@139.224.1.83:/etc/moxian/server.yaml
scp examples/moxian-server.service root@139.224.1.83:/etc/systemd/system/

# VPS 上
ssh root@139.224.1.83 << 'EOF'
chmod +x /usr/local/bin/moxian-server
chown root:nobody /etc/moxian/server.yaml
chmod 640 /etc/moxian/server.yaml
systemctl daemon-reload
systemctl enable --now moxian-server
systemctl status moxian-server
EOF
```

之后管理：
```bash
systemctl restart moxian-server    # 改配置后重启
journalctl -u moxian-server -f     # 看日志
```

## Linux 客户端部署（家里 NAS / 办公室电脑 / 树莓派）

```bash
# 目标机器 (以 home-nas 为例)
scp bin/moxian-client-linux-amd64 user@home-nas:/usr/local/bin/moxian-client
scp examples/client.yaml           user@home-nas:/etc/moxian/client.yaml
scp examples/moxian-client.service user@home-nas:/etc/systemd/system/

ssh user@home-nas << 'EOF'
chmod +x /usr/local/bin/moxian-client
# 编辑 client.yaml 改 node_id 和 virtual_ip
sudo vi /etc/moxian/client.yaml
# 启用
sudo systemctl daemon-reload
sudo systemctl enable --now moxian-client
sudo systemctl status moxian-client
EOF
```

## Windows 客户端部署

```
D:\moxian-p2p\
├── moxian-client.exe
├── wintun.dll              ← 从 https://www.wintun.net/ 下载
├── client.yaml             ← 复制 examples/client.yaml 过来 改 node_id 等
└── start-client.bat        ← 双击启动（需右键管理员身份运行）
```

改 `client.yaml` 里三个关键字段：
- `node_id: winpc`
- `virtual_ip: 10.88.0.2`
- `tags:` 自定义

**右键** `start-client.bat` → **以管理员身份运行**。

## Android 客户端

不需要 yaml。APP 里填写 UI 表单。`bin/moxian-p2p-debug.apk` adb install 即可。

## 虚拟 IP 规划

建议一个 `/24` 子网，例如 `10.88.0.0/24`：

| 节点 | vIP | 角色 |
|------|-----|------|
| 家里 NAS | 10.88.0.2 | 7x24 在线，文件/服务中心 |
| 家里 Windows | 10.88.0.3 | 7x24 在线或开机时 |
| 笔记本 | 10.88.0.4 | 出门时 |
| 手机 | -（不用 TUN，走 forward） | 只做主动连接 |
| 公司电脑 | 10.88.0.10 | 工作时 |

## 故障排查

| 症状 | 排查 |
|------|------|
| `[client] signaling not connected` | VPS server 没跑或防火墙拦 tcp 7788 |
| `nat_type=symmetric` | 移动网络 / 对称 NAT，会走中继 (mode=2) |
| `[mesh] connected` 后秒断 | 对端重启了 保持会重新 dial |
| TUN ping 不通但 TCP 通 | Windows 防火墙拦 ICMP，`netsh advfirewall firewall add rule name=ICMPv4-In protocol=icmpv4:8,any dir=in action=allow` |
| `create wintun: access denied` | 不是管理员 右键 bat 管理员运行 |
| 想开 debug 日志 | yaml 里 `verbose: true` |
