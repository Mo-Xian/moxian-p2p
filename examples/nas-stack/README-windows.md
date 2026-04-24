# moxian-nas-stack Windows 部署指南

在你的 Windows 笔记本 / 台式机上直接跑这套 NAS 应用栈，所有数据在**一个文件夹**，迁移到其他 Windows 或 Linux 真机时只需拷贝。

**优点**：不改 Windows 系统、不装几十个独立软件、卸载时一条命令回到干净状态。

## 前置条件

- Windows 10 21H2 以上 / Windows 11
- 4 核 CPU、**16G 内存推荐**（Immich ML 吃内存）
- 100G+ 空闲磁盘（建议放在 D 盘或独立盘）
- 本机管理员权限（**装 Docker Desktop 需要 一次** 之后日常操作不需要）
- 能访问 Docker Hub 和 GitHub（首次拉镜像约 3-5G）

## 一、装 Docker Desktop

```powershell
# 管理员 PowerShell（仅此一次）
winget install Docker.DockerDesktop
```

装完**重启系统** →  从开始菜单启动 Docker Desktop → 等右下角图标变绿（约 30 秒）。

首次启动会提示启用 WSL2，照着点确认即可。

**验证**：

```powershell
docker --version
# Docker version 24.x.x ...

docker compose version
# Docker Compose version v2.x.x
```

## 二、拉代码 + 一键启动

```powershell
# 任意普通 PowerShell 不用管理员
cd C:\Users\你\
git clone https://github.com/Mo-Xian/moxian-p2p
cd moxian-p2p\examples\nas-stack

# 一条命令搞定
.\bootstrap.ps1
```

脚本会交互式问你：
1. 数据根目录（默认 `D:\nas-data`，回车接受）
2. 是否立刻启动（默认 Y，回车接受）

然后自动完成：
- 检查 Docker 是否可用
- 创建数据目录骨架
- 从 `.env.example` 生成 `.env`，**自动生成所有密码**（无需手动改 CHANGEME）
- `docker compose up -d` 启动 6 个应用

**首次**启动要 3-5 分钟拉镜像（约 5G）。完成后所有服务可用。

## 三、密码（已自动生成 无需手动改）

`bootstrap.ps1` 会**自动生成随机强密码**写入 `.env`（终端会打印出来，记下来备用）。想查看所有密码：

```powershell
notepad .env
```

想自己改也可以，改完后：

```powershell
docker compose up -d
```

## 四、访问服务

**本机浏览器**：
- Immich 相册：http://localhost:2283
- Jellyfin 影视：http://localhost:8096
- Syncthing：http://localhost:8384
- Vaultwarden 密码：http://localhost:8080
- qBittorrent：http://localhost:8081

**局域网其他设备**（手机/平板）：把 `localhost` 换成电脑的局域网 IP，比如 `http://192.168.1.100:2283`。

IP 查询：
```powershell
ipconfig | Select-String "IPv4"
```

## 五、moxian-p2p 远程访问

两种选法：**容器版 responder**（最简 跟其他应用一起管）或 **原生 exe TUN**（最透明 要管理员）。

### 5.1 容器版 responder（Windows 也能跑）⭐

**原理**：moxian-client 容器不开 TUN，只做"对端来访问我 转发给 Docker 服务"。手机在 APP 里配 forward 规则指向 NAS 的 Docker 服务名。

**一次性启动**：

```powershell
# 编辑 NAS 端配置
copy configs\moxian\client.yaml.example configs\moxian\client.yaml
notepad configs\moxian\client.yaml
# 改三个字段：
#   server: wss://你的VPS:8443/ws
#   udp:    你的VPS:7000
#   pass:   和手机端相同的强密码

# 启动应用栈 + moxian 容器（首次本地 build Dockerfile 约 1 分钟）
docker compose -f docker-compose.yml -f docker-compose.moxian.yml up -d
```

**手机 APP 配置**（moxian-p2p Android APK）：

主页"Forwards"字段一行一条：

```
127.0.0.1:18096=home-nas=jellyfin:8096
127.0.0.1:12283=home-nas=immich-server:2283
127.0.0.1:18384=home-nas=syncthing:8384
```

手机启动 VPN 后：
- 看 Jellyfin：`http://127.0.0.1:18096`
- 看 Immich：`http://127.0.0.1:12283`

本质上手机本地端口 → moxian mesh → NAS 容器 → Docker 服务名解析 → 对应容器。**零 TUN 任何 Android 都能跑**。

### 5.2 原生 moxian-gui.exe（需要管理员 但体验更透明）

想在电脑上像接入 Tailscale 那样直接 ping 家里所有设备？用原生 TUN 版：

1. 从 [Release 页](https://github.com/Mo-Xian/moxian-p2p/releases/latest) 下载 `moxian-gui.exe` + `wintun.dll`
2. 放到同一文件夹 + 准备好 `client.yaml`（要开 `mesh: true` + `virtual_ip: auto`）
3. **右键 `moxian-gui.exe` → 以管理员身份运行**
4. 托盘图标右键菜单：启动 / 停止 / 编辑配置

启动后本机有 TUN 虚拟网卡，直接 ping mesh 里其他节点（手机、其他 NAS）。

### 5.3 两种方式对比

| 维度 | 容器版 responder | 原生 exe TUN |
|------|----------------|-------------|
| 安装 | `docker compose` 一条 | 下载 exe + wintun.dll |
| 需要管理员 | ❌ | ✅（TUN 驱动）|
| 手机访问 NAS 服务 | ✅ 配 forward 规则 | ✅ 直接用虚拟 IP |
| PC 访问家里路由器 Web / 其他局域网设备 | ❌ | ✅ |
| Windows Docker Desktop | ✅ | N/A |
| 管理方便 | ⭐ 和 Docker 应用一起 | 独立进程 |

**多数家用场景推荐 5.1 容器版** —— 和 NAS 应用栈统一管理，迁移时只搬 `D:\nas-data`。

---

## 六、文件共享（SMB 原生）⭐ 比 Samba 简单

Windows 自带 SMB，不用装 Samba：

1. 右键 `D:\nas-data` → **属性** → **共享** → **高级共享**
2. 勾选"共享此文件夹"，共享名填 `nas-data`
3. 点**权限** → 添加你的 Windows 账号 → 勾"完全控制"
4. 点**安全**选项卡 → 编辑 → 添加同账号 → 完全控制
5. 确定，完成

**其他设备访问**：
- Windows：资源管理器 `\\<你电脑 IP>\nas-data`
- Mac：Finder → 连接服务器 → `smb://<ip>/nas-data`
- 手机：SMB 客户端 APP 填同样地址

## 七、性能优化

### 7.1 Windows Defender 排除 Docker 卷 ⭐ 必做

Docker I/O 经过 Defender 扫描会慢 3-5 倍，加排除：

```powershell
# 管理员 PowerShell
Add-MpPreference -ExclusionPath "D:\nas-data"
Add-MpPreference -ExclusionPath "$env:USERPROFILE\AppData\Local\Docker"
Add-MpPreference -ExclusionPath "\\wsl.localhost\docker-desktop-data"
```

### 7.2 限制 WSL2 内存（可选）

Docker Desktop 默认可占用一半系统内存，想限制：

创建 `C:\Users\你\.wslconfig`：

```ini
[wsl2]
memory=8GB
processors=4
swap=2GB
```

重启 Docker Desktop 生效。

### 7.3 Jellyfin 硬件转码

笔记本 Intel iGPU（你的 1065G7 是 Iris Plus Gen11）在 Docker Desktop 下**没法直接用**。两个选项：

- **不转码**：Jellyfin APP 设置"原画直出"，让手机/TV 自己解码（多数现代设备 4K HEVC 都支持）
- **装原生 Jellyfin Windows 服务版**，关掉 Docker 的 Jellyfin 容器 这样能用 QSV 硬解

多数家用场景选"不转码"即可。

## 八、开机自启

### 8.1 Docker Desktop 自启

Docker Desktop 设置 → General → 勾"Start Docker Desktop when you sign in to your computer"。

### 8.2 容器自启

compose 里已有 `restart: unless-stopped`，Docker Desktop 启动后容器自动恢复。

### 8.3 Windows 登录后自动启 Docker（不要求登录）

默认 Docker Desktop 需要用户登录 Windows 才启动。想**无人登录也跑**，要装 Windows Server 版 Docker，超出家用场景，不推荐。

笔记本当临时 NAS 的话：**让 Windows 自动登录账号**（但会降低安全性）：

```powershell
# 管理员 PowerShell
control userpasswords2
# 取消勾"要使用本计算机 用户必须输入用户名和密码"
```

## 九、日常操作

```powershell
cd C:\Users\你\moxian-p2p\examples\nas-stack

# 查状态
docker compose ps

# 看日志
docker compose logs -f jellyfin
docker compose logs -f --tail=50

# 重启单个服务
docker compose restart immich-server

# 停所有服务（容器删掉 数据保留）
docker compose down

# 完全启动
docker compose up -d

# 更新镜像
docker compose pull
docker compose up -d
```

## 十、迁移到另一台机器

**迁移到另一台 Windows**：

```powershell
# 旧机器：停服务 + 拷数据
docker compose down
robocopy D:\nas-data \\新机名\D$\nas-data /MIR /Z /MT:16

# 新机器：装 Docker Desktop + 拉代码 + 同样 .env
git clone ...
cd moxian-p2p\examples\nas-stack
.\bootstrap.ps1  # 选同样 DATA_ROOT
# 把旧机的 .env 也拷过来（密码要一致）
docker compose up -d
```

**迁移到 Linux 真机（最终 NAS 方案）**：

```bash
# 新 Linux 机器装完 Debian + 挂载 /mnt/pool
# 从 Windows 机器 rsync 数据
rsync -avP --rsh="ssh -o StrictHostKeyChecking=no" \
  user@win-machine:/d/nas-data/ /mnt/pool/

# 改 .env
DATA_ROOT=/mnt/pool

# 启动
docker compose up -d
```

应用自动识别历史数据，**照片库、影视元数据、密码库、下载任务全部继承**，**0 重做**。

## 十一、完全卸载

停服务、清容器、清镜像：

```powershell
cd moxian-p2p\examples\nas-stack
docker compose down --rmi all --volumes
```

删数据（确认不再需要！）：

```powershell
Remove-Item D:\nas-data -Recurse -Force
```

卸 Docker Desktop：

```powershell
winget uninstall Docker.DockerDesktop
```

清理 WSL2（可选 彻底）：

```powershell
wsl --unregister docker-desktop
wsl --unregister docker-desktop-data
```

取消 Windows Defender 排除：

```powershell
Remove-MpPreference -ExclusionPath "D:\nas-data"
# ... 其他几条
```

**系统完全回到装 Docker Desktop 之前的状态，零残留**。

## 十二、常见问题

| 症状 | 解决 |
|------|------|
| `docker: command not found` | Docker Desktop 没启动或 PATH 没生效 重启 PowerShell |
| Docker Desktop 启动后一直转圈 | WSL2 虚拟化没开 BIOS 里启用 `Intel VT-x` / `AMD-V` |
| 容器启动后秒退出 | `docker compose logs <服务名>` 看日志 多半 .env 里密码或路径错 |
| 浏览器 `localhost:2283` 连不上 | `docker compose ps` 看容器是否 running 然后看端口是否被其他进程占用 `netstat -ano \| findstr 2283` |
| 局域网其他设备访问不到 | Windows 防火墙拦了 入站规则放行 2283/8096/8080/8081/8384 |
| Immich 扫照片极慢 | 给 Docker Desktop 加内存到 8G；关掉 Smart Search 只留人脸识别 |
| 磁盘 I/O 飙高卡顿 | Windows Defender 没加排除（第 7.1 节） |
| 电脑合盖服务就断 | 电源设置 → "合盖时" 改"不采取任何操作"（插电时） |
| 想手机访问 Windows 的 NAS | 用 moxian-p2p APP 接入同一 mesh 手机填 NAS 的虚拟 IP |

## 十三、和主文档的关系

- **主文档 `self-hosted-nas.md`** — Linux 真机部署指南（RAID / Samba / systemd）
- **本文档 `README-windows.md`** — Windows 快速体验版
- **`README.md`** — nas-stack 本身的说明

Windows 方案是真机方案的**过渡**，用来快速验证能否满足需求。**长期使用**还是推荐 N100 / M720q 迷你主机装 Debian，功耗低、稳定性高、可扩展。
