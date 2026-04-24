# moxian-nas-stack

**一键可迁移的开源 NAS 应用栈**。目标：新机器 30 分钟从 Debian 裸机到完整 NAS，含 Immich（AI 相册）/ Jellyfin（影视）/ Syncthing（同步）/ Vaultwarden（密码）/ qBittorrent（下载）/ moxian-p2p（远程接入）。

## 包含组件

| 应用 | 用途 | Web 端口 |
|------|------|---------|
| **Immich** | AI 相册（人脸/物体/地理识别）| 2283 |
| **Jellyfin** | 家庭影视（支持硬件转码）| 8096 |
| **Syncthing** | 多端同步（OneDrive/Dropbox 替代）| 8384 |
| **Vaultwarden** | 密码管理（Bitwarden 服务端）| 8080 |
| **qBittorrent** | BT/PT 下载 | 8081 |
| **moxian-p2p** | 远程访问（P2P 打洞 + TUN mesh）| 无 Web |

## 快速开始

**Windows 用户**：Docker Desktop 一键跑起来，不用碰 Linux。看 [`README-windows.md`](README-windows.md)。

**Linux 用户（推荐真机部署）**：继续往下看。

### 前置

- Debian 12 / Ubuntu 22.04+（其他发行版自行调整 `bootstrap.sh` 的 apt 命令）
- `/mnt/pool` 已挂载（RAID 或单盘 ext4；[主指南](../self-hosted-nas.md) 有组 RAID 步骤）
- sudo 权限

### 三步部署（Linux）

```bash
# 1. 拉取本仓库到 NAS
git clone https://github.com/Mo-Xian/moxian-p2p.git
cd moxian-p2p/examples/nas-stack

# 2. 宿主机初始化（自动装 Docker/Samba/UFW 建目录 自动生成随机密码）
sudo ./bootstrap.sh

# 3. 启动（默认不含 moxian 容器）
docker compose up -d

# 4.（可选）想把 moxian-p2p 也用容器跑？加 overlay
# 注意：仅 Linux 真机稳定 不要在 Docker Desktop/WSL2 下用
cp configs/moxian/client.yaml.example configs/moxian/client.yaml
vim configs/moxian/client.yaml          # 填 server 和 passphrase
docker compose -f docker-compose.yml -f docker-compose.moxian.yml up -d
```

约 3-5 分钟镜像拉完，`docker compose ps` 全绿即成功。

### 三步部署（Windows）

```powershell
# 1. 装 Docker Desktop（需管理员一次）
winget install Docker.DockerDesktop
# 重启系统 等 Docker Desktop 启动变绿

# 2. 拉代码 + 一键启动（自动建目录 + 自动生成密码 + docker compose up -d）
git clone https://github.com/Mo-Xian/moxian-p2p
cd moxian-p2p\examples\nas-stack
.\bootstrap.ps1

# 3. 浏览器 http://localhost:2283 (Immich) / :8096 (Jellyfin) 等
```

无需手动改任何 CHANGEME 密码，脚本自动生成并打印到终端。

**想加远程访问？** 最简路径是把 moxian-p2p 也跑成容器（Windows / Linux 都适用）：

```powershell
# 编辑 NAS 端配置 填 server/udp/pass
copy configs\moxian\client.yaml.example configs\moxian\client.yaml
notepad configs\moxian\client.yaml

# 加 overlay 一起启动（首次本地 build 约 1 分钟）
docker compose -f docker-compose.yml -f docker-compose.moxian.yml up -d
```

详见 [`README-windows.md` 第五节](README-windows.md#五moxian-p2p-远程访问)。

### 访问

浏览器：
- Immich: `http://<NAS-IP>:2283`（首次注册为管理员）
- Jellyfin: `http://<NAS-IP>:8096`
- Syncthing: `http://<NAS-IP>:8384`
- Vaultwarden: `http://<NAS-IP>:8080`
- qBittorrent: `http://<NAS-IP>:8081`（默认用户 `admin` 密码 `docker compose logs qbittorrent` 找）

Samba：资源管理器 `\\<NAS-IP>\pool`

## 目录结构

```
nas-stack/
├── README.md                 # 本文档
├── docker-compose.yml        # 统一应用编排
├── .env.example              # 配置模板（TZ/端口/密码/路径）
├── bootstrap.sh              # 宿主机一次性初始化脚本
└── configs/
    └── moxian/
        └── client.yaml.example  # moxian-p2p 客户端配置模板
```

运行后数据分布：

```
/mnt/pool/
├── photos/                   # Immich 相册
├── media/
│   ├── movies/               # Jellyfin 电影库
│   ├── tv/                   # 剧集
│   └── music/                # 音乐
├── docs/                     # Syncthing 同步目录
├── downloads/                # qBittorrent 下载
├── backups/                  # Restic 备份
├── appdata/                  # 各应用数据库/缓存
│   ├── immich-db/
│   ├── immich-ml/
│   ├── jellyfin/
│   ├── syncthing/
│   ├── vaultwarden/
│   └── qbittorrent/
└── stacks/                   # 预留其他 docker-compose
```

## 迁移到新机器

**整套系统的唯一"状态"**：
- `/mnt/pool/` 下所有数据（用户数据 + 应用数据库 + 应用配置）
- `.env` 和 `configs/` 下的配置文件

迁移流程：

```bash
# ---- 在新机器上 ----

# 1. 装裸 Debian + 挂载存储到 /mnt/pool（若新机器 RAID 结构不同 自行调整）

# 2. 拉代码 + bootstrap（不装应用 只装宿主层）
git clone https://github.com/Mo-Xian/moxian-p2p.git
cd moxian-p2p/examples/nas-stack
sudo ./bootstrap.sh

# 3. 从旧机器 rsync 所有数据
sudo rsync -avP --delete \
  nas-old:/mnt/pool/ /mnt/pool/

# 4. 复制配置
scp nas-old:/path/to/nas-stack/.env .
scp nas-old:/path/to/nas-stack/configs/moxian/client.yaml configs/moxian/

# 5. 拉起应用 历史数据自动识别
docker compose up -d
```

**30 分钟内完成迁移**（时间主要花在 rsync 数据上）。

## 日常操作

```bash
# 启动/停止/重启
docker compose up -d
docker compose down
docker compose restart

# 查看状态和日志
docker compose ps
docker compose logs -f immich-server
docker compose logs -f --tail=50

# 更新镜像（手动，避免自动更新破环境）
docker compose pull
docker compose up -d

# 彻底清理（数据仍在 /mnt/pool/appdata/ 不会丢）
docker compose down -v  # 注意 -v 会删除命名卷 本 compose 未用命名卷 安全
```

## 备份

主指南 [10. Restic 异地备份](../self-hosted-nas.md#10-restic异地备份) 详细介绍了加密增量备份到 Cloudflare R2 / B2 / 阿里云 OSS 的 systemd timer 配置。核心命令：

```bash
# 每日 3:00 自动备份 photos + docs（appdata 也建议备份 但体积大）
restic -r s3:... backup /mnt/pool/photos /mnt/pool/docs
```

## 自定义 / 扩展

### 加应用

在 `docker-compose.yml` 末尾加 service，数据卷指向 `${DATA_ROOT}/appdata/<app>`，端口在 `.env` 里加变量。例如加 AdGuard Home：

```yaml
  adguard:
    image: adguard/adguardhome:latest
    container_name: adguard
    restart: unless-stopped
    ports:
      - "53:53/tcp"
      - "53:53/udp"
      - "${ADGUARD_PORT}:80"
    volumes:
      - ${DATA_ROOT}/appdata/adguard/work:/opt/adguardhome/work
      - ${DATA_ROOT}/appdata/adguard/conf:/opt/adguardhome/conf
    networks: [nas]
```

`.env` 加 `ADGUARD_PORT=8090`，`docker compose up -d` 即生效。

### 开启硬件转码（Jellyfin）

取消 `docker-compose.yml` 里 Jellyfin 的 `devices: - /dev/dri:/dev/dri` 注释（Intel/AMD iGPU），进 Jellyfin 管理台 → 播放 → 硬件加速选 VA-API 或 QSV。

### 改端口

只改 `.env` 里的 `*_PORT` 变量，`docker compose up -d` 生效。compose 里容器内端口保持不变。

## 常见问题

| 症状 | 解决 |
|------|------|
| `docker compose` 找不到命令 | bootstrap.sh 没跑；或者是老版 `docker-compose` 改用 `docker compose`（两个单词） |
| Immich 启动报 DB 连接失败 | 密码改过但 postgres 数据卷保留了旧密码 → `docker compose down && sudo rm -rf /mnt/pool/appdata/immich-db && docker compose up -d` |
| Jellyfin 转码 CPU 100% | 没开硬件加速 见上面"开启硬件转码" |
| moxian-p2p 容器反复重启 | `docker logs moxian-client` 看错误 多数是 client.yaml 的 server/passphrase 填错 |
| Samba 访问被拒 | bootstrap 里漏了设密码 `sudo smbpasswd -a <用户名>` 补上 |
| UFW 开了后远程 SSH 断了 | bootstrap 已放行 22；若 SSH 走非标端口需手动 `ufw allow <port>/tcp` |

## 和主指南的关系

本目录是 [主指南 `self-hosted-nas.md`](../self-hosted-nas.md) 的**自动化落地版**。主指南讲原理和逐步操作，适合第一次搭建学习用；本目录一条命令跑起来，适合后续复用和快速迁移。

两份文档的关系：
- 主指南 = 教材（为什么这么做）
- 本目录 = 成品（拿来就能用）

推荐先读主指南第 1-2 节理解 RAID/挂载，后面全走本目录。

## License

MIT，同 moxian-p2p 主仓库。
