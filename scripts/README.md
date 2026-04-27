# moxian-p2p 一键安装脚本

不想手动填配置？跑下面对应平台的命令，全自动搞定。

## 服务端（VPS）

```bash
# 必须 root（要装 systemd unit + 配置防火墙）
curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-server.sh | sudo bash
```

自动完成：

| 步骤 | 内容 |
|------|------|
| 公网 IP 检测 | 自动从 `ifconfig.me` 等查 也支持 `MOXIAN_PUBLIC_IP=x.x.x.x` 强制指定 |
| 下载 binary | GitHub Release 最新版（支持 amd64 / arm64）|
| JWT 密钥 | `openssl rand -hex 32` 写入 `/etc/moxian/jwt.secret` 700 权限 |
| TLS 证书 | 自签 RSA-2048 SHA-256 10 年有效 含 IP SAN |
| systemd unit | `moxian-server.service` 自动 daemon-reload + enable + start |
| 防火墙 | firewalld / ufw 自动放行 7788/tcp + 7789-7790/udp |

完成后浏览器开 `https://<你VPS IP>:7788/` 注册首位管理员（邀请码留空）。

**升级**：重跑同一命令即可。密钥/证书/数据库保留，只换 binary。

**自定义改 systemd 参数**：
```bash
sudo vim /etc/systemd/system/moxian-server.service
sudo systemctl restart moxian-server
```

## Linux 客户端（NAS / 服务器）

```bash
curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.sh | sudo bash
```

交互式询问：
- 服务器 URL（如 `https://1.2.3.4:7788`）
- 邮箱 / 主密码
- 节点名（默认机器 hostname）
- 是否跳过 TLS 证书验证（自签证书选 y）

凭据存 `/etc/moxian/client.env` (mode 600)。systemd 自启 + 自动重连。

## Windows 客户端

```powershell
# 管理员 PowerShell
irm https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.ps1 | iex
```

自动完成：
- 下 `moxian-gui.exe` + `wintun.dll` 到 `C:\Program Files\moxian-p2p\`
- 询问连接信息 写 `client.yaml`
- 桌面 + 开始菜单快捷方式（带管理员标记 双击直接 UAC 跳启动）
- 注册开机自启任务（登录后自动 tray 跑起来）

## Android 客户端

不需要脚本。装 APK 一次：

<https://github.com/Mo-Xian/moxian-p2p/releases/latest>

之后 **长按桌面图标 → 检查更新** 一键升级。

## 完整安装顺序（推荐）

```
1. VPS:         install-server.sh
2. 浏览器:       https://VPS_IP:7788/ 注册管理员
3. 管理员页面:   生成邀请码（给家人 / 其他设备）
4. 家里 Linux:  install-client.sh（NAS / 树莓派）
5. 家里 Windows: install-client.ps1（笔记本 / 台式）
6. 手机:         装 APK 登录（用注册的账号）
```

## 文件位置参考

### Linux 服务端
- binary: `/usr/local/bin/moxian-server`
- systemd: `/etc/systemd/system/moxian-server.service`
- JWT 密钥: `/etc/moxian/jwt.secret` (mode 600)
- TLS 证书: `/etc/moxian/tls/cert.pem` + `key.pem`
- 数据库: `/var/lib/moxian/moxian.db` (SQLite)
- 日志: `journalctl -u moxian-server`

### Linux 客户端
- binary: `/usr/local/bin/moxian-client`
- systemd: `/etc/systemd/system/moxian-client.service`
- 凭据: `/etc/moxian/client.env` (mode 600)
- 日志: `journalctl -u moxian-client`

### Windows 客户端
- 安装目录: `C:\Program Files\moxian-p2p\`
- 配置: `C:\Program Files\moxian-p2p\client.yaml`
- 计划任务: `moxian-p2p`（开机自启）
- 桌面: `moxian-p2p.lnk`

## 常见问题

**Q: 服务端怎么改密码？**
A: v2 不存明文密码 用户改密码必须从 Web 面板登录后操作（暂未实现 计划 v2.1）

**Q: 服务端怎么备份？**
A: 备份 `/var/lib/moxian/moxian.db` + `/etc/moxian/jwt.secret` 即可。还原后所有用户密码继续有效。

**Q: 客户端密码改了怎么同步？**
A: Linux: 编辑 `/etc/moxian/client.env` 改 `MOXIAN_PASSWORD=` 然后 `systemctl restart moxian-client`
   Windows: 编辑 `client.yaml` 改 `v2_password:` 然后重启 GUI

**Q: 升级会丢数据吗？**
A: 不会。脚本只换 binary 不动配置/数据。但**先备份再升级**永远是好习惯。

**Q: 卸载？**
```bash
# Linux
sudo systemctl stop moxian-server moxian-client 2>/dev/null || true
sudo systemctl disable moxian-server moxian-client 2>/dev/null || true
sudo rm /usr/local/bin/moxian-server /usr/local/bin/moxian-client
sudo rm /etc/systemd/system/moxian-server.service /etc/systemd/system/moxian-client.service
sudo rm -rf /etc/moxian /var/lib/moxian
sudo systemctl daemon-reload
```
```powershell
# Windows
schtasks /Delete /TN "moxian-p2p" /F
Remove-Item -Path "C:\Program Files\moxian-p2p" -Recurse -Force
Remove-Item -Path "$([Environment]::GetFolderPath('Desktop'))\moxian-p2p.lnk" -Force
```
