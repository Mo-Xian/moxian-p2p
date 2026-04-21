# 自建开源 NAS 部署指南（含 moxian-p2p 远程接入）

本文演示如何用 **Debian + Docker + 开源组件** 从零搭一台功能对标"飞牛 OS / 群晖"的家庭 NAS，并用 moxian-p2p 打通外网访问。**全套零订阅、零闭源、数据不出本地**。

## 目录

- [硬件参考](#硬件参考)
- [系统底座](#1-系统底座debian-12)
- [存储：RAID + 挂载](#2-存储raid-1--ext4)
- [CasaOS 管理面板](#3-casaos管理面板)
- [Samba 文件共享](#4-samba文件共享)
- [Immich 相册（AI 人脸识别）](#5-immich相册人脸识别)
- [Jellyfin 影视](#6-jellyfin影视库)
- [Syncthing 多端同步](#7-syncthing多端同步)
- [Vaultwarden 密码管理](#8-vaultwarden密码管理)
- [moxian-p2p 远程接入](#9-moxian-p2p远程接入)
- [Restic 异地备份](#10-restic异地备份)
- [维护与监控](#11-维护与监控)

---

## 硬件参考

| 档位 | 推荐配置 | 适合场景 |
|------|---------|---------|
| 极简 | 树莓派 5 8G + USB3 硬盘柜 + 4T HDD | 单用户 文件 + 轻度服务 |
| 推荐 | 二手 ThinkCentre M720q (i5-8500T/16G) + 2×4T HDD | 家庭主力 + AI 相册 |
| 进阶 | N100 主机（双 M.2 + SATA）+ 16G + 2×8T + NVMe 缓存 | 多用户 + 影视转码 |

硬盘建议 **西数红盘 Pro / 希捷酷狼**（CMR 不要 SMR）。

---

## 1. 系统底座：Debian 12

Debian 十年稳定，包最全，社区问题多的全能搜到。

### 1.1 安装

1. 下载 `debian-12-netinst.iso`，Rufus 烧录 U 盘
2. 安装时**只勾选 `SSH server` 和 `standard system utilities`**，其他不选
3. 分区：SSD 全给根分区，HDD 先不分（后面组 RAID）

### 1.2 基础配置

```bash
# 换国内源（可选 DebCDN 镜像，清华/中科大/阿里都可）
sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list

apt update && apt upgrade -y
apt install -y curl wget vim htop tmux smartmontools lm-sensors \
               mdadm parted rsync git sudo ufw fail2ban

# 添加普通用户（后续操作用它，不用 root）
adduser nas
usermod -aG sudo nas

# 开启防火墙（只放 SSH，后面按需加端口）
ufw allow 22/tcp
ufw enable
```

### 1.3 关闭图形界面自启（若误装）

```bash
systemctl set-default multi-user.target
```

---

## 2. 存储：RAID 1 + ext4

两块 HDD 做镜像，一块挂了不丢数据。想要 RAID 5/6 至少准备 4 块盘。

### 2.1 组 RAID 1

```bash
# 假设两块 HDD 是 /dev/sda /dev/sdb（用 lsblk 确认，别搞错）
lsblk

# 清残留分区表
wipefs -a /dev/sda /dev/sdb

# 创建 RAID 1
mdadm --create /dev/md0 --level=1 --raid-devices=2 /dev/sda /dev/sdb

# 查看构建进度
cat /proc/mdstat
# 4T 盘约 6-10 小时同步完（期间可正常用）

# 格式化
mkfs.ext4 -L pool /dev/md0
```

### 2.2 挂载 + 开机自动

```bash
mkdir -p /mnt/pool
blkid /dev/md0
# 复制 UUID

cat >> /etc/fstab <<EOF
UUID=<上面的-UUID> /mnt/pool ext4 defaults,noatime 0 2
EOF

mount -a

# 保存 mdadm 配置
mdadm --detail --scan | tee -a /etc/mdadm/mdadm.conf
update-initramfs -u
```

### 2.3 建目录骨架

```bash
cd /mnt/pool
mkdir -p media/{movies,tv,music} photos docs backups downloads appdata
chown -R nas:nas /mnt/pool
```

---

## 3. CasaOS：管理面板

CasaOS 是一层轻量 Web UI + Docker 编排，底层仍是 Debian，随时可卸载不污染系统。

```bash
curl -fsSL https://get.casaos.io | sudo bash
```

装完浏览器访问 `http://<NAS-IP>`，注册账号。应用商店里后续组件都能一键装。

> **警告**：CasaOS 默认开 80/443，公网暴露有风险。外网访问**一定**走 moxian-p2p 或 Tailscale，别开路由器端口映射。

---

## 4. Samba：文件共享

Windows/Mac 最通用的协议。

```bash
apt install -y samba

# 加用户（Linux 账号要先存在）
smbpasswd -a nas
```

编辑 `/etc/samba/smb.conf`，文件尾部追加：

```ini
[pool]
   path = /mnt/pool
   browseable = yes
   writable = yes
   valid users = nas
   create mask = 0664
   directory mask = 0775
   force user = nas
   force group = nas

[media]
   path = /mnt/pool/media
   browseable = yes
   read only = yes
   guest ok = yes
```

```bash
systemctl restart smbd
ufw allow 445/tcp   # 只放给内网 不暴露公网
```

- Windows：资源管理器地址栏 `\\<NAS-IP>\pool`
- Mac：Finder → 前往 → 连接服务器 → `smb://<NAS-IP>/pool`

---

## 5. Immich：相册（人脸识别）

全本地推理、全量人脸/物体/地理索引，取代 Google Photos / iCloud。**最低 4G 内存专给它**。

### 5.1 部署

```bash
mkdir -p /mnt/pool/appdata/immich
cd /mnt/pool/appdata/immich

# 下载官方 compose
wget https://github.com/immich-app/immich/releases/latest/download/docker-compose.yml
wget -O .env https://github.com/immich-app/immich/releases/latest/download/example.env
```

编辑 `.env`：

```env
UPLOAD_LOCATION=/mnt/pool/photos
DB_DATA_LOCATION=/mnt/pool/appdata/immich/postgres
TZ=Asia/Shanghai
IMMICH_VERSION=release
DB_PASSWORD=<改成强随机密码>
```

```bash
docker compose up -d
# 浏览器访问 http://<NAS-IP>:2283 初始化管理员账号
# 手机装 Immich APP 登录 打开自动备份
```

### 5.2 性能调优（可选）

- GPU 硬件加速：N100/Intel iGPU 可开启 OpenVINO，`.env` 里 `HWACCEL_TRANSCODING=vaapi`
- 限制机器学习内存：`immich-machine-learning` 容器加 `mem_limit: 3G`

---

## 6. Jellyfin：影视库

```bash
mkdir -p /mnt/pool/appdata/jellyfin
```

CasaOS 应用商店一键装 Jellyfin，或手动 compose：

```yaml
# /mnt/pool/appdata/jellyfin/docker-compose.yml
services:
  jellyfin:
    image: jellyfin/jellyfin:latest
    container_name: jellyfin
    restart: unless-stopped
    user: "1000:1000"
    devices:
      - /dev/dri:/dev/dri     # Intel/AMD 硬解
    volumes:
      - ./config:/config
      - ./cache:/cache
      - /mnt/pool/media:/media:ro
    ports:
      - "8096:8096"
```

```bash
docker compose up -d
# 浏览器 http://<NAS-IP>:8096 首次设置 添加媒体库指向 /media
```

**硬件转码**（关键）：设置 → 播放 → 选择"Intel QuickSync (QSV)"或"VA-API"。4K HEVC → 1080p H.264 实时转码只需 5-10% CPU。

---

## 7. Syncthing：多端同步

替代 OneDrive/Dropbox，P2P 同步，不过第三方服务器。

```yaml
# /mnt/pool/appdata/syncthing/docker-compose.yml
services:
  syncthing:
    image: syncthing/syncthing:latest
    container_name: syncthing
    hostname: nas-syncthing
    restart: unless-stopped
    user: "1000:1000"
    volumes:
      - ./config:/var/syncthing/config
      - /mnt/pool/docs:/var/syncthing/docs
    ports:
      - "8384:8384"       # Web UI
      - "22000:22000/tcp" # 同步协议
      - "22000:22000/udp"
      - "21027:21027/udp" # 本地发现
```

浏览器 `http://<NAS-IP>:8384`，设置管理员密码后，电脑/手机各装 Syncthing 客户端，扫二维码配对。

---

## 8. Vaultwarden：密码管理

自托管 Bitwarden 服务端，Rust 写的，只吃 30M 内存。

```yaml
# /mnt/pool/appdata/vaultwarden/docker-compose.yml
services:
  vaultwarden:
    image: vaultwarden/server:latest
    container_name: vaultwarden
    restart: unless-stopped
    environment:
      - ADMIN_TOKEN=<openssl rand -base64 48 生成>
      - SIGNUPS_ALLOWED=false   # 只自己用 初次注册后关闭
      - DOMAIN=http://<NAS-IP>:8080
    volumes:
      - ./data:/data
    ports:
      - "8080:80"
```

浏览器访问，注册主账号后，把 compose 里 `SIGNUPS_ALLOWED=true` 改 false 重启，防止别人注册。Bitwarden 官方客户端（手机/浏览器插件）登录时填自建服务器地址即可。

---

## 9. moxian-p2p：远程接入

这一步是本 NAS 方案的灵魂 —— **用自己的 P2P 工具打通外网访问，彻底告别"飞牛会员加速"和 Tailscale**。

### 9.1 场景

- 出差在外地，手机访问家里 NAS 的 Jellyfin / Immich / Samba
- 老家另一台 NAS 定时同步到家里主 NAS（异地灾备）
- 同事家设备加入你的 mesh（私有组网）

### 9.2 NAS 端配置

下载 `moxian-client` 二进制到 NAS：

```bash
mkdir -p /opt/moxian
cd /opt/moxian
wget https://github.com/cp12064/moxian-p2p/releases/latest/download/moxian-client-linux-amd64
mv moxian-client-linux-amd64 moxian-client
chmod +x moxian-client
```

写配置 `/opt/moxian/nas.yaml`：

```yaml
node_id: home-nas
server: wss://your-signal-vps.example.com:8443
udp: your-signal-vps.example.com:7000
passphrase: <和其他节点保持一致的强密码>

# 可选 tag 方便其他节点识别
tags:
  - role=nas
  - location=home

# ACL 只允许可信节点连入
allow_peers:
  - role=owner

# 虚拟 IP 自动分配 用于 TUN mesh
virtual_ip: auto

# mesh 模式 自动与 pool 里所有节点互联
mesh: true
mesh_pool:
  - phone-android
  - laptop
  - remote-nas

# 暴露 NAS 服务给其他 mesh 节点（通过 TUN 直接访问 IP 即可）
# 不需要额外 forward 规则 TUN 透明承载所有 TCP/UDP
```

### 9.3 systemd 开机自启

```bash
cat > /etc/systemd/system/moxian.service <<'EOF'
[Unit]
Description=moxian-p2p client for NAS mesh
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/moxian
ExecStart=/opt/moxian/moxian-client -config /opt/moxian/nas.yaml
Restart=always
RestartSec=5
# TUN 需要网络权限
AmbientCapabilities=CAP_NET_ADMIN
CapabilityBoundingSet=CAP_NET_ADMIN

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now moxian
systemctl status moxian
```

### 9.4 手机 / 笔记本接入

- **Android**：装 moxian-p2p APK，填信令地址 + passphrase + node_id，开 VPN。之后在家外通过虚拟 IP（如 `10.99.0.10`）直接访问 NAS 服务：
  - Jellyfin: `http://10.99.0.10:8096`
  - Immich: `http://10.99.0.10:2283`
  - SMB: `smb://10.99.0.10/pool`
- **Windows / Mac / Linux**：用 `moxian-client.exe` + Wintun/utun，同理

### 9.5 信令服务器（0 成本方案）

本教程需要一台有公网 IP 的小机器做信令。选一：

- **Oracle Cloud Free Tier**：ARM 4 核 24G 永久免费（主力推荐）
- **朋友家有公网的闲置主机**
- **家里有 IPv6 公网**（家宽电信/联通常送 `/64`）的话，可以一台 NAS 同时兼任 server

信令流量极小（KB/天级别），带宽不是问题。具体 server 部署见 [examples/server.yaml](server.yaml)。

---

## 10. Restic：异地备份

**核心数据（相册/文档）必须异地备份**。RAID 只防硬盘挂，不防误删、勒索病毒、火灾。

### 10.1 安装

```bash
apt install -y restic
```

### 10.2 选备份目标（选一）

| 服务 | 免费额度 | 特点 |
|------|---------|------|
| **Cloudflare R2** | 10G | 无出站费用 推荐 |
| **Backblaze B2** | 10G | 老牌便宜 $6/T/月 |
| **阿里云 OSS 低频** | 0 | 约 1 毛/G/月 国内快 |
| **另一台 NAS** | 0 | 真·异地（放公司或老家）|

### 10.3 初始化 + 首次备份

以 R2 为例（S3 兼容）：

```bash
export AWS_ACCESS_KEY_ID="<你的 R2 access key>"
export AWS_SECRET_ACCESS_KEY="<你的 R2 secret>"
export RESTIC_REPOSITORY="s3:https://<account-id>.r2.cloudflarestorage.com/<bucket>"
export RESTIC_PASSWORD="<备份加密密码 牢记 丢了数据找不回>"

restic init

# 首次全量备份
restic backup /mnt/pool/photos /mnt/pool/docs --exclude-caches

# 查看快照
restic snapshots
```

### 10.4 定时增量备份

```bash
cat > /etc/restic.env <<EOF
AWS_ACCESS_KEY_ID=<key>
AWS_SECRET_ACCESS_KEY=<secret>
RESTIC_REPOSITORY=s3:https://<account-id>.r2.cloudflarestorage.com/<bucket>
RESTIC_PASSWORD=<备份密码>
EOF
chmod 600 /etc/restic.env

cat > /etc/systemd/system/restic-backup.service <<'EOF'
[Unit]
Description=Restic backup to R2

[Service]
Type=oneshot
EnvironmentFile=/etc/restic.env
ExecStart=/usr/bin/restic backup /mnt/pool/photos /mnt/pool/docs --exclude-caches
ExecStartPost=/usr/bin/restic forget --keep-daily 7 --keep-weekly 4 --keep-monthly 12 --prune
EOF

cat > /etc/systemd/system/restic-backup.timer <<'EOF'
[Unit]
Description=Daily Restic backup

[Timer]
OnCalendar=*-*-* 03:00:00
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now restic-backup.timer
```

### 10.5 恢复测试（每月一次）

```bash
# 恢复某个快照的某个文件到 /tmp 验证
restic restore latest --target /tmp/restore-test --include /mnt/pool/photos/2025
ls -la /tmp/restore-test
rm -rf /tmp/restore-test
```

> **血泪教训**：只做备份不做恢复测试，真到灾难那天发现备份损坏就晚了。

---

## 11. 维护与监控

### 11.1 监控（Netdata，2 分钟装）

```bash
bash <(curl -Ss https://my-netdata.io/kickstart.sh) --dont-wait
# 浏览器 http://<NAS-IP>:19999 看 CPU/内存/磁盘 SMART/Docker 实时图表
```

### 11.2 关键 cron / timer

| 任务 | 频率 | 命令 |
|------|------|------|
| Restic 备份 | 每日 3:00 | 上面 systemd timer |
| APT 安全更新 | 每日 4:00 | `unattended-upgrades` |
| SMART 长检测 | 每月 1 号 | `smartctl -t long /dev/sda` |
| mdadm 数据校验 | 每月 1 号 | `echo check > /sys/block/md0/md/sync_action` |
| Docker 镜像清理 | 每周日 | `docker system prune -af` |

### 11.3 邮件告警（可选）

```bash
apt install -y msmtp mailutils
# 配置 /etc/msmtprc 指向 QQ/163 SMTP
# mdadm 已内置 MAILADDR 配置 RAID 降级时自动发邮件
vim /etc/mdadm/mdadm.conf
# 改 MAILADDR you@example.com
```

---

## 附录：与飞牛对比

| 功能 | 飞牛 OS | 本方案 |
|------|---------|--------|
| 成本 | 成品机 3500+ / 会员 150 年 | 二手硬件 2500 / 软件 0 |
| 文件共享 | ✅ | Samba ✅ |
| AI 相册 | ✅ 会员 | Immich ✅ 免费 |
| 影视库 | ✅ | Jellyfin ✅ |
| 远程访问 | ✅ 会员 | moxian-p2p ✅ 免费 |
| 同步盘 | ✅ | Syncthing ✅ |
| Docker | ✅ | ✅ |
| 数据主权 | ⚠️ 部分走云 | ✅ 完全本地 |
| 开源可审计 | ❌ | ✅ 全套 |
| 折腾成本 | 低 | 中（一次性 2-3 天）|

---

## 推荐阅读

- CasaOS <https://www.casaos.io>
- Immich <https://immich.app>
- Jellyfin <https://jellyfin.org>
- awesome-selfhosted <https://github.com/awesome-selfhosted/awesome-selfhosted>

---

欢迎 PR 改进本文档。遇到问题提 Issue 讨论。
