# 自建开源 NAS 部署指南（含 moxian-p2p 远程接入）

本文演示如何用 **Debian + Docker + 开源组件** 从零搭一台功能对标"飞牛 OS / 群晖"的家庭 NAS，并用 moxian-p2p 打通外网访问。**全套零订阅、零闭源、数据不出本地**。

> 💡 **已有 Debian 环境，想跳过原理直接跑起来？** 见 [`nas-stack/`](nas-stack/) 目录 —— 一键 `bootstrap.sh` + 统一 `docker-compose.yml`，30 分钟从裸机到完整 NAS，迁移新机器只需 `rsync + docker compose up -d`。本文档讲原理，`nas-stack` 是成品化的自动脚本。

## 目录

- [硬件参考](#硬件参考)
- [零成本预演（无硬件时的测试方案）](#零成本预演无硬件时的测试方案)
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

## 零成本预演（无硬件时的测试方案）

还没买硬件？先用 **家里已有的 Windows 笔记本** 完整跑一遍本方案，验证功能和操作流程。等熟悉了再决定要不要上二手主机。

三条路线，按"贴近真实程度"从低到高：

| 方案 | 起步时间 | 贴近真机 | 适合 |
|------|---------|---------|------|
| A. WSL2 + Docker | 10 分钟 | ★★★ | 只测应用栈（Immich/Jellyfin/moxian）|
| B. Hyper-V / VirtualBox 虚拟机 | 1 小时 | ★★★★★ | 完整预演 含 RAID、systemd、Samba |
| C. Docker Desktop（裸 Windows）| 5 分钟 | ★★ | 极速体验单个应用 |

**推荐路径**：WSL2 快速试水 → 虚拟机完整预演 → 迁移到真机

### 方案 A：WSL2（推荐快速测试）

零配置，Linux 生态全套可用，和真 Debian NAS 体验几乎一致。

#### A.1 安装 WSL2 Debian

```powershell
# 管理员 PowerShell
wsl --install -d Debian
# 重启后自动进入 Debian shell 设置用户名密码
```

#### A.2 WSL 内装 Docker + 基础工具

```bash
# Debian WSL 里执行
sudo apt update && sudo apt upgrade -y
sudo apt install -y docker.io docker-compose-v2 curl git
sudo usermod -aG docker $USER

# 退出重进 WSL 生效
exit
# PowerShell: wsl -d Debian

# 验证
docker run --rm hello-world
```

#### A.3 一键拉起核心应用栈（Immich + Jellyfin + qBittorrent）

```bash
mkdir -p ~/nas-test/{photos,media,downloads,appdata}
cd ~/nas-test

cat > docker-compose.yml <<'EOF'
# 测试专用 简化版 NAS 应用栈
services:
  # ---- Immich 相册 ----
  immich-server:
    image: ghcr.io/immich-app/immich-server:release
    ports: ["2283:2283"]
    environment:
      UPLOAD_LOCATION: /usr/src/app/upload
      DB_HOSTNAME: immich-db
      DB_USERNAME: postgres
      DB_PASSWORD: testpassword
      DB_DATABASE_NAME: immich
      REDIS_HOSTNAME: immich-redis
    volumes:
      - ./photos:/usr/src/app/upload
    depends_on: [immich-db, immich-redis, immich-ml]
    restart: unless-stopped

  immich-ml:
    image: ghcr.io/immich-app/immich-machine-learning:release
    volumes:
      - ./appdata/immich-ml:/cache
    restart: unless-stopped

  immich-redis:
    image: docker.io/redis:6.2-alpine
    restart: unless-stopped

  immich-db:
    image: docker.io/tensorchord/pgvecto-rs:pg14-v0.2.0
    environment:
      POSTGRES_PASSWORD: testpassword
      POSTGRES_USER: postgres
      POSTGRES_DB: immich
    volumes:
      - ./appdata/immich-db:/var/lib/postgresql/data
    restart: unless-stopped

  # ---- Jellyfin 影视 ----
  jellyfin:
    image: jellyfin/jellyfin:latest
    ports: ["8096:8096"]
    volumes:
      - ./appdata/jellyfin/config:/config
      - ./appdata/jellyfin/cache:/cache
      - ./media:/media:ro
    restart: unless-stopped

  # ---- qBittorrent 下载 ----
  qbittorrent:
    image: lscr.io/linuxserver/qbittorrent:latest
    ports:
      - "8081:8081"
      - "6881:6881"
      - "6881:6881/udp"
    environment:
      PUID: "1000"
      PGID: "1000"
      WEBUI_PORT: "8081"
    volumes:
      - ./appdata/qbittorrent:/config
      - ./downloads:/downloads
    restart: unless-stopped
EOF

docker compose up -d
docker compose ps
```

首次拉镜像约 3-5G，耐心等。完成后：

- Immich: `http://localhost:2283`（首次注册管理员账号）
- Jellyfin: `http://localhost:8096`
- qBittorrent: `http://localhost:8081`（默认账号 `admin` / 密码见 `docker logs qbittorrent`）

#### A.4 让局域网其他设备也能访问（可选）

WSL2 默认 NAT 模式，服务只监听 WSL 内部 IP。Windows 端口转发一下：

```powershell
# 管理员 PowerShell
$wslIp = (wsl hostname -I).Trim()
foreach ($port in 2283,8096,8081) {
  netsh interface portproxy add v4tov4 listenport=$port listenaddress=0.0.0.0 connectport=$port connectaddress=$wslIp
}
# 防火墙放行
New-NetFirewallRule -DisplayName "WSL NAS" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 2283,8096,8081
```

局域网其他设备 `http://<笔记本 IP>:2283` 即可访问。

> **注意**：WSL 每次重启 IP 可能变，需要重跑 portproxy。可以写个启动脚本自动化。

#### A.5 在 WSL 里跑 moxian-p2p

```bash
cd ~/nas-test
wget https://github.com/Mo-Xian/moxian-p2p/releases/latest/download/moxian-client-linux-amd64
chmod +x moxian-client-linux-amd64

# 从 examples/client.yaml 改一份 改 node_id / server / passphrase
cp /path/to/moxian-p2p/examples/client.yaml ./client.yaml
vim client.yaml

# 前台跑 看日志
./moxian-client-linux-amd64 -config ./client.yaml -v
```

成功打通后，手机装 moxian-p2p APP，填相同 passphrase + 不同 node_id，即可通过虚拟 IP 访问 WSL 里的 Jellyfin/Immich。

### 方案 B：Hyper-V 虚拟机（完整预演）

用 VM 跑一台完整 Debian，和真机部署 **100% 一致**，可以把本文档每一步都练一遍。

#### B.1 前置检查

```powershell
# PowerShell 普通权限即可
systeminfo | findstr /i "Hyper-V"
```

要求：
- Windows 10 / 11 **专业版 / 企业版 / 教育版**（家庭版**没**这功能 → 用 VirtualBox 替代 见 B.11）
- BIOS 开启 **虚拟化技术**（Intel VT-x / AMD-V）
- 64 位 CPU、8G+ 内存

如果 `已检测到虚拟化 - 是` 即可。BIOS 未开的话进 BIOS 找 `Intel Virtualization Technology` 或 `SVM Mode` 启用。

#### B.2 启用 Hyper-V 功能

**方法一：PowerShell**

```powershell
# 管理员 PowerShell
Enable-WindowsOptionalFeature -Online -FeatureName Microsoft-Hyper-V -All
Restart-Computer
```

**方法二：图形界面**

控制面板 → 程序 → 启用或关闭 Windows 功能 → 勾选 **Hyper-V**（含"管理工具"和"平台"两项）→ 重启。

重启后开始菜单搜 **Hyper-V 管理器** 即可打开。

#### B.3 创建虚拟交换机（网络桥接）

**关键步骤**。要让 VM 在局域网里有独立 IP（和真 NAS 一样），必须桥接物理网卡。

Hyper-V 管理器 → 右侧 **虚拟交换机管理器** → 新建虚拟网络交换机：

- 类型：**外部**
- 名称：`LAN-Bridge`
- 外部网络：选物理网卡（**有线优先**；WiFi 可用但可能不稳）
- 建议勾选 "允许管理操作系统共享此网络适配器"（家用场景 主机仍能联网）

> ⚠️ 创建时网络会短暂断开 5-10 秒 别在视频会议时搞。

#### B.4 下载 Debian ISO

```powershell
# PowerShell 任意目录
Invoke-WebRequest `
  -Uri "https://mirrors.tuna.tsinghua.edu.cn/debian-cd/current/amd64/iso-cd/debian-12.9.0-amd64-netinst.iso" `
  -OutFile "D:\iso\debian-12-netinst.iso"
```

版本号可能变，去 <https://www.debian.org/distrib/> 取最新 `netinst`。约 700M。

#### B.5 新建 VM

Hyper-V 管理器 → **操作 → 新建 → 虚拟机**：

| 步骤 | 选项 |
|------|------|
| 名称 | `nas-test` |
| 代 | **第二代**（UEFI） |
| 内存 | **4096 MB** 勾选"使用动态内存" |
| 网络连接 | `LAN-Bridge` |
| 虚拟硬盘 | **40 GB** 路径 `D:\Hyper-V\nas-test\system.vhdx` |
| 安装选项 | 从可启动映像文件安装 → 选上一步 ISO |

完成后**先别启动**，继续下一步。

#### B.6 关闭安全启动（Linux 第二代 VM 必做）

第二代 VM 默认开 Secure Boot，会阻止 Debian 启动。

右键 `nas-test` → **设置 → 安全** → 取消勾选 **启用安全启动**。

（或模板改选 "Microsoft UEFI 证书颁发机构"，Debian 官方 ISO 也能过。）

#### B.7 （可选）加虚拟盘模拟 RAID

想练 `mdadm` 组 RAID 1 的话，添加两块虚拟盘：

设置 → **SCSI 控制器 → 添加硬盘驱动器 → 新建** →
- 类型：**动态扩展**
- 大小：**10 GB**
- 路径：`D:\Hyper-V\nas-test\raid-1.vhdx`

重复一次建 `raid-2.vhdx`。VM 里就有 3 块盘：系统盘 + 2 块 RAID 盘。

#### B.8 启动 + 装 Debian

启动 `nas-test` → 右键 → 连接（打开控制台）。

Debian 安装要点：
- 网络：DHCP 自动从家里路由器拿 IP
- 主机名：`nas-test`
- 分区：**使用整个磁盘** → 选 40G 那块（别误选 10G RAID 盘）
- 软件选择：**只勾 SSH server + standard system utilities**，桌面环境全不选
- 约 5-10 分钟 → 重启 → 出登录提示

#### B.9 拿 IP 并 SSH 连入

VM 控制台登录后：

```bash
ip addr show
# 记下 eth0 的 IP 例如 192.168.1.50
```

回 Windows 用任意 SSH 客户端（PowerShell 内置 `ssh`、Windows Terminal、MobaXterm 等）：

```powershell
ssh nas@192.168.1.50
```

**此后所有操作都在 SSH 里做**，比 Hyper-V 控制台方便太多（可复制粘贴、上下滚动、tmux 分屏）。

#### B.10 快照保护（随便折腾）

Hyper-V 管理器 → 右键 VM → **检查点** 即快照。

推荐快照节点：
- `fresh-install` — 刚装完 Debian
- `after-casaos` — 装完 CasaOS
- `before-raid` — 组 RAID 前
- `working-nas` — 整套跑通后

折腾失败右键检查点 → **应用** → 秒回。

#### B.11 家庭版 Windows 替代：VirtualBox

没专业版跳过 B.2-B.3，改用 **VirtualBox**：

1. 下载 <https://www.virtualbox.org>（免费）
2. 新建 → Linux → Debian 64-bit
3. 内存 4G、硬盘 40G
4. 设置 → 网络 → 方式选 **桥接网卡** → 选物理网卡（等同 Hyper-V 的外部交换机）
5. 存储 → 光驱 → 挂载 `debian-12-netinst.iso`
6. 启动 → 走 B.8 装 Debian

之后步骤和 Hyper-V 一样。

#### B.12 完整预演 NAS

VM 装好 Debian 后，从本文档 [1. 系统底座](#1-系统底座debian-12) 开始照做，每一步命令都能验证。**优势**：
- 随时快照回滚，操作失败不心疼
- 和真机一模一样的环境
- 笔记本休眠时 VM 保存状态，恢复继续

#### B.13 迁移到真机

VM 里的 `/mnt/pool/appdata/` 直接 rsync 到真机：

```bash
# 在真机上
rsync -avP --rsh=ssh nas@<VM-IP>:/mnt/pool/appdata/ /mnt/pool/appdata/

# 新机器上重新 docker compose up -d
# 数据卷路径一致 应用自动识别历史数据
```

#### B.14 常见问题

| 症状 | 解决 |
|------|------|
| 启动 "Boot Failed" | 关安全启动（B.6）|
| VM 启动卡住 / 极慢 | 禁用"动态内存"改固定 4G 或检查主机内存占用 |
| VM 拿不到 IP | 虚拟交换机没选对物理网卡 或路由器 DHCP 没开 |
| 装完 Hyper-V 后 VirtualBox 用不了 | Hyper-V 独占 CPU 虚拟化 → 二选一；或 Windows 11 用 Hyper-V Platform 兼容子集 |
| WiFi 建交换机后掉线 | 网卡驱动不完整支持桥接 → 升级驱动 或改 NAT（但 VM 不在局域网内）|
| 启动报 "无法建立与虚拟机的连接" | Hyper-V 服务没启动 `Start-Service vmms` |

### 方案 C：Docker Desktop（最快体验）

装 [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop)，把上面 A.3 的 `docker-compose.yml` 里路径改成 Windows 盘符：

```yaml
volumes:
  - D:/nas-test/photos:/usr/src/app/upload
```

`docker compose up -d` 即可。**缺点**：没法装 CasaOS，Samba/Immich 的性能比 WSL 低一截，仅适合快速看单个应用长什么样。

### 笔记本长期当 NAS 的避坑

测试阶段用完即撤最干净。如果想接着用笔记本做临时 NAS：

| 坑 | 解决 |
|----|------|
| 合盖休眠 | 设置 → 电源 → "合上盖子时不采取任何操作" |
| 屏幕一直亮 | 设置 → 电源 → "从不关闭屏幕"但**可以关屏不锁屏** |
| Windows 强制重启 | 组策略 `gpedit.msc` → 计算机配置 → 管理模板 → Windows 组件 → Windows 更新 → "禁用自动重启" |
| 单硬盘无冗余 | 外挂 USB 硬盘定时 rsync 镜像（不是实时 RAID）|
| 长期插电电池鼓包 | Lenovo Vantage / ThinkPad 设电池充电阈值 60-80% |
| 系统更新触发重启 | `powercfg /h off` 彻底关休眠 + 禁用自动更新 |

### 测试阶段建议路径

1. **Day 1**：方案 A（WSL2）跑通 Immich + Jellyfin + moxian-p2p，手机能备份照片、外网能看电影 ✅
2. **Day 2-3**：方案 B（Hyper-V VM）完整预演，包括 RAID、Samba、systemd 服务、Restic 备份
3. **买硬件后**：照 VM 里的笔记直接搬到真机，**半天完工**

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
