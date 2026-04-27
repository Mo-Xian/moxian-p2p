# v2 配置示例

**v2 不再需要手写 YAML 配置**。安装脚本（`scripts/install-server.sh` / `scripts/install-client.sh`）会自动生成所有需要的配置文件。

此目录文件仅作**参考**：你想自定义某些参数时知道改哪里。

## 实际配置位置（由 install 脚本生成）

### 服务端

| 文件 | 作用 |
|------|------|
| `/etc/systemd/system/moxian-server.service` | 启动参数（systemd unit）|
| `/etc/moxian/jwt.secret` | JWT 签名密钥（重启后所有 token 失效）|
| `/etc/moxian/tls/cert.pem` + `key.pem` | 自签 TLS 证书 |
| `/var/lib/moxian/moxian.db` | SQLite 数据库（用户/邀请码/Vault）|

### Linux 客户端

| 文件 | 作用 |
|------|------|
| `/etc/systemd/system/moxian-client.service` | 启动参数 |
| `/etc/moxian/client.env` | 凭据（mode 600）|

### Windows 客户端

| 文件 | 作用 |
|------|------|
| `C:\Program Files\moxian-p2p\client.yaml` | GUI 用的配置（含 v2 凭据）|
| Task Scheduler `moxian-p2p` 任务 | 开机自启 |

### Android

无配置文件。所有信息（JWT、加密 vault、服务器 URL）由 EncryptedSharedPreferences 存储。

## 改配置的常见操作

### 改服务端启动参数（端口 / 数据库路径 / 关闭 TLS 等）

```bash
sudo vim /etc/systemd/system/moxian-server.service
sudo systemctl daemon-reload
sudo systemctl restart moxian-server
```

### 改 Linux 客户端凭据

```bash
sudo vim /etc/moxian/client.env
sudo systemctl restart moxian-client
```

### 重新生成 JWT 密钥（强制所有用户重新登录）

```bash
sudo openssl rand -hex 32 | sudo tee /etc/moxian/jwt.secret > /dev/null
sudo systemctl restart moxian-server
```

### 重新生成 TLS 证书（IP 变更或 5 年快到期了）

```bash
NEW_IP=$(curl -4 -s ifconfig.me)
sudo openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
  -keyout /etc/moxian/tls/key.pem -out /etc/moxian/tls/cert.pem \
  -subj "/CN=moxian-p2p" \
  -addext "subjectAltName=IP:${NEW_IP},DNS:moxian-p2p"
sudo chmod 600 /etc/moxian/tls/key.pem
sudo systemctl restart moxian-server
```

证书更换后 **APP 端要重新登录**（旧的 cert pinning 可能缓存了）。

## 卸载

```bash
# Linux 服务端
sudo systemctl stop moxian-server
sudo systemctl disable moxian-server
sudo rm /usr/local/bin/moxian-server
sudo rm /etc/systemd/system/moxian-server.service
sudo rm -rf /etc/moxian /var/lib/moxian
sudo systemctl daemon-reload

# Linux 客户端 同上 把 server 换成 client
```

```powershell
# Windows
schtasks /Delete /TN "moxian-p2p" /F
Remove-Item "C:\Program Files\moxian-p2p" -Recurse -Force
Remove-Item "$([Environment]::GetFolderPath('Desktop'))\moxian-p2p.lnk"
```

## 与 v1 的差异

v1 模式（YAML 配置文件）已废弃。如需参考老格式，看 `examples/server.yaml` / `examples/client.yaml`（标记为 LEGACY）。

主要变化：

| v1 | v2 |
|----|----|
| 服务端读 YAML | 服务端只用 CLI flags + SQLite |
| 客户端写 YAML（含 pass/server/udp）| 客户端只填邮箱/密码/服务器 URL |
| `pass` 双方手抄一致 | 服务器自动生成下发 |
| 节点 ID 自定 | 服务器自动注册 + 分配 vIP |
| 无 ACL | 用户系统 + 节点归属 + 邀请码注册 |
| 凭据明文存盘 | 加密 vault 多端同步（零知识）|
