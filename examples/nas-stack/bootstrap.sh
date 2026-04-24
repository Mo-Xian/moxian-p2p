#!/usr/bin/env bash
# moxian-nas-stack 宿主机一次性初始化脚本
#
# 作用：
#   1. 基础工具 + Docker 安装
#   2. 创建 /mnt/pool 目录骨架
#   3. 可选：配置 Samba 文件共享
#   4. 可选：配置 UFW 防火墙
#   5. 检查 .env 和 moxian/client.yaml 就绪
#
# 用法：
#   sudo ./bootstrap.sh
#
# 前置：
#   - Debian 12（Ubuntu 22.04+ 也兼容）
#   - /mnt/pool 已挂载（RAID 或单盘 ext4 均可 组 RAID 参见主文档）
#
# 幂等：可重复跑 不会破坏已有配置

set -euo pipefail

# ---- 配色输出 ----
C_OK="\033[32m"
C_WARN="\033[33m"
C_ERR="\033[31m"
C_INFO="\033[36m"
C_OFF="\033[0m"
info() { echo -e "${C_INFO}[info]${C_OFF} $*"; }
ok()   { echo -e "${C_OK}[ ok ]${C_OFF} $*"; }
warn() { echo -e "${C_WARN}[warn]${C_OFF} $*"; }
err()  { echo -e "${C_ERR}[err ]${C_OFF} $*"; exit 1; }

# ---- 前置检查 ----
[[ $EUID -eq 0 ]] || err "需要 root  请 sudo 运行"

if ! command -v apt-get &>/dev/null; then
  err "仅支持 Debian/Ubuntu 系 apt-get 不可用"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---- Step 1: 装基础工具 + Docker ----
info "=== Step 1/5 装基础工具 ==="

export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get install -y -qq \
  curl wget vim htop tmux \
  ca-certificates gnupg lsb-release \
  smartmontools lm-sensors \
  rsync cron ufw \
  restic

if ! command -v docker &>/dev/null; then
  info "装 Docker..."
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg \
    -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc

  echo "deb [arch=$(dpkg --print-architecture) \
signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/debian $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list

  apt-get update -qq
  apt-get install -y -qq \
    docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
  ok "Docker 安装完成"
else
  ok "Docker 已安装 跳过"
fi

# ---- Step 2: 创建目录骨架 ----
info "=== Step 2/5 创建 /mnt/pool 目录骨架 ==="

if ! mountpoint -q /mnt/pool; then
  warn "/mnt/pool 未挂载  本脚本假定你已手动组好 RAID/单盘并挂载到 /mnt/pool"
  warn "如果只是测试 可以: mkdir -p /mnt/pool && chown \$USER /mnt/pool"
  read -rp "继续创建目录到当前 /mnt/pool (可能是根分区)? [y/N] " ans
  [[ "${ans,,}" == "y" ]] || err "已退出"
fi

REAL_USER="${SUDO_USER:-$USER}"
REAL_UID=$(id -u "$REAL_USER")
REAL_GID=$(id -g "$REAL_USER")

mkdir -p /mnt/pool/{photos,media/{movies,tv,music},docs,downloads,backups,appdata,stacks}
chown -R "$REAL_UID:$REAL_GID" /mnt/pool
chmod 755 /mnt/pool
ok "目录骨架 OK  属主 $REAL_USER ($REAL_UID:$REAL_GID)"

# ---- Step 3: 检查配置文件 ----
info "=== Step 3/5 检查配置文件 ==="

if [[ ! -f .env ]]; then
  if [[ -f .env.example ]]; then
    cp .env.example .env

    # 自动生成随机密码替换所有 CHANGEME 占位符
    IMMICH_PWD=$(openssl rand -hex 16 2>/dev/null || head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
    VW_TOKEN=$(openssl rand -base64 48 2>/dev/null | tr -d '\n' || head -c 48 /dev/urandom | base64 | tr -d '\n')

    sed -i "s|CHANGEME-immich-db-strong-password|$IMMICH_PWD|" .env
    sed -i "s|CHANGEME-use-openssl-rand-base64-48|$VW_TOKEN|" .env
    # 兜底 替换剩余 CHANGEME-* 为随机
    sed -i "s|CHANGEME-[a-zA-Z0-9-]*|$(openssl rand -hex 12)|g" .env

    ok ".env 从模板创建 + 所有密码已自动生成"
    info "  Immich DB 密码: $IMMICH_PWD"
    info "  Vaultwarden ADMIN_TOKEN: $VW_TOKEN"
    info "  如需查看所有: cat .env"
  else
    err "找不到 .env 和 .env.example"
  fi
else
  ok ".env 已存在 跳过"
  if grep -q CHANGEME .env; then
    warn ".env 中仍有 CHANGEME 占位符 建议替换为强密码"
    grep -n CHANGEME .env || true
  fi
fi

if [[ ! -f configs/moxian/client.yaml ]]; then
  if [[ -f configs/moxian/client.yaml.example ]]; then
    cp configs/moxian/client.yaml.example configs/moxian/client.yaml
    warn "moxian client.yaml 不存在 已复制模板  请编辑填写 server/passphrase"
  fi
fi

# ---- Step 4: Samba（可选） ----
info "=== Step 4/5 Samba 共享（可选）==="
read -rp "配置 Samba 让 Windows/Mac 访问 /mnt/pool? [y/N] " setup_samba
if [[ "${setup_samba,,}" == "y" ]]; then
  apt-get install -y -qq samba

  # 备份原配置
  [[ -f /etc/samba/smb.conf.orig ]] || cp /etc/samba/smb.conf /etc/samba/smb.conf.orig

  # 追加 pool 共享（幂等：存在则跳过）
  if ! grep -q "^\[pool\]" /etc/samba/smb.conf; then
    cat >> /etc/samba/smb.conf <<EOF

[pool]
   path = /mnt/pool
   browseable = yes
   writable = yes
   valid users = $REAL_USER
   create mask = 0664
   directory mask = 0775
   force user = $REAL_USER
   force group = $REAL_USER

[media]
   path = /mnt/pool/media
   browseable = yes
   read only = yes
   guest ok = yes
EOF
    ok "追加 [pool] 和 [media] 共享段"
  else
    ok "Samba pool 共享段已存在 跳过"
  fi

  # 添加 Samba 用户（如不存在）
  if ! pdbedit -L | grep -q "^$REAL_USER:"; then
    warn "为 $REAL_USER 设置 Samba 密码（可不同于 Linux 登录密码）"
    smbpasswd -a "$REAL_USER"
  fi

  systemctl enable --now smbd nmbd
  systemctl restart smbd
  ok "Samba 完成  Windows: \\\\$(hostname -I | awk '{print $1}')\\pool"
fi

# ---- Step 5: 防火墙（可选） ----
info "=== Step 5/5 UFW 防火墙（可选）==="
read -rp "启用 UFW 防火墙 仅放行必要端口? [y/N] " setup_ufw
if [[ "${setup_ufw,,}" == "y" ]]; then
  ufw --force reset
  ufw default deny incoming
  ufw default allow outgoing

  ufw allow 22/tcp                       comment 'SSH'
  ufw allow 445/tcp                      comment 'Samba'
  ufw allow 137,138/udp                  comment 'Samba NetBIOS'
  ufw allow 139/tcp                      comment 'Samba NetBIOS'
  ufw allow "${IMMICH_PORT:-2283}/tcp"   comment 'Immich'
  ufw allow "${JELLYFIN_PORT:-8096}/tcp" comment 'Jellyfin'
  ufw allow "${SYNCTHING_UI_PORT:-8384}/tcp" comment 'Syncthing UI'
  ufw allow 22000/tcp                    comment 'Syncthing sync'
  ufw allow 22000/udp                    comment 'Syncthing sync'
  ufw allow 21027/udp                    comment 'Syncthing discovery'
  ufw allow "${VAULTWARDEN_PORT:-8080}/tcp" comment 'Vaultwarden'
  ufw allow "${QBIT_UI_PORT:-8081}/tcp"  comment 'qBittorrent UI'
  ufw allow 6881/tcp                     comment 'qBittorrent'
  ufw allow 6881/udp                     comment 'qBittorrent'

  ufw --force enable
  ok "UFW 已启用  状态: ufw status"
fi

# ---- 收尾 ----
echo
ok "=========================================="
ok "bootstrap 完成！后续步骤："
ok "=========================================="
echo
echo "  1. .env 密码已自动生成 如需查看: cat .env"
echo "  2. 启动所有服务："
echo "       docker compose up -d"
echo "  3.（可选）想把 moxian-p2p 也跑容器？编辑 configs/moxian/client.yaml 然后:"
echo "       docker compose -f docker-compose.yml -f docker-compose.moxian.yml up -d"
echo "  4. 查看状态："
echo "       docker compose ps"
echo "       docker compose logs -f <service-name>"
echo
echo "  访问入口（替换 <NAS-IP>）："
echo "    Immich:      http://<NAS-IP>:$(grep -E '^IMMICH_PORT=' .env | cut -d= -f2)"
echo "    Jellyfin:    http://<NAS-IP>:$(grep -E '^JELLYFIN_PORT=' .env | cut -d= -f2)"
echo "    Syncthing:   http://<NAS-IP>:$(grep -E '^SYNCTHING_UI_PORT=' .env | cut -d= -f2)"
echo "    Vaultwarden: http://<NAS-IP>:$(grep -E '^VAULTWARDEN_PORT=' .env | cut -d= -f2)"
echo "    qBittorrent: http://<NAS-IP>:$(grep -E '^QBIT_UI_PORT=' .env | cut -d= -f2)"
echo
