#!/usr/bin/env bash
# moxian-p2p CLI client 一键安装脚本（v2.x）
#
# 用法（家里 Linux 节点比如 NAS）：
#   curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.sh | sudo bash
#
# 交互式询问：
#   - 服务器 URL（如 https://1.2.3.4:7788）
#   - 邮箱 / 主密码
#   - 节点名（默认 hostname）
#   - 是否启用自签证书跳过验证
#
# 自动完成：
#   1. 下载最新 client binary
#   2. 写凭据 / 启动参数到 /etc/moxian/client.env
#   3. 创建 systemd unit + 启动 + 自启
#
# 重新运行 = 升级 binary（凭据保留）

set -euo pipefail

C_OK="\033[32m"; C_WARN="\033[33m"; C_ERR="\033[31m"; C_INFO="\033[36m"; C_OFF="\033[0m"
ok()   { echo -e "${C_OK}[ ok ]${C_OFF} $*"; }
info() { echo -e "${C_INFO}[info]${C_OFF} $*"; }
warn() { echo -e "${C_WARN}[warn]${C_OFF} $*"; }
err()  { echo -e "${C_ERR}[err ]${C_OFF} $*"; exit 1; }

[[ $EUID -eq 0 ]] || err "必须 root 运行"

ARCH=""
case "$(uname -m)" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) err "不支持的架构: $(uname -m)" ;;
esac

# ---- 下载 binary ----
LATEST_TAG=$(curl -fsSL https://api.github.com/repos/Mo-Xian/moxian-p2p/releases/latest \
  | grep -oP '"tag_name":\s*"\K[^"]+' | head -1)
[[ -n "$LATEST_TAG" ]] || err "拿不到最新版本"
info "最新版本: $LATEST_TAG"

# 已装版本（一行 tag）
TAG_FILE=/etc/moxian/.installed_tag
INSTALLED_TAG=""
[[ -f "$TAG_FILE" ]] && INSTALLED_TAG=$(cat "$TAG_FILE" | tr -d '[:space:]')

if [[ "$INSTALLED_TAG" == "$LATEST_TAG" && -x /usr/local/bin/moxian-client ]]; then
  ok "已是最新版 $LATEST_TAG 跳过下载（删除 $TAG_FILE 强制重装）"
else
  [[ -n "$INSTALLED_TAG" ]] && info "升级 $INSTALLED_TAG → $LATEST_TAG" || info "首次安装 $LATEST_TAG"
  BIN_URL="https://github.com/Mo-Xian/moxian-p2p/releases/download/$LATEST_TAG/moxian-client-linux-$ARCH"
  TMP=$(mktemp)
  info "下载 $BIN_URL"
  curl -fSL -o "$TMP" "$BIN_URL"
  install -m 755 "$TMP" /usr/local/bin/moxian-client
  rm -f "$TMP"
  install -d -m 755 /etc/moxian
  echo "$LATEST_TAG" > "$TAG_FILE"
  ok "已安装到 /usr/local/bin/moxian-client ($LATEST_TAG)"
fi

# ---- 收集凭据 ----
# 每次都问 但显示旧值 回车保留
install -d -m 755 /etc/moxian
ENV_FILE=/etc/moxian/client.env

# 加载旧值（如有）
OLD_SERVER=""; OLD_EMAIL=""; OLD_PWD=""; OLD_NODE=""; OLD_INSECURE=""
if [[ -f "$ENV_FILE" ]]; then
  # 安全 source（控制白名单避免奇怪命令注入）
  while IFS='=' read -r k v; do
    case "$k" in
      MOXIAN_SERVER)   OLD_SERVER="$v" ;;
      MOXIAN_EMAIL)    OLD_EMAIL="$v" ;;
      MOXIAN_PASSWORD) OLD_PWD="$v" ;;
      MOXIAN_NODE)     OLD_NODE="$v" ;;
      MOXIAN_INSECURE) OLD_INSECURE="$v" ;;
    esac
  done < "$ENV_FILE"
  echo
  info "检测到现有配置 直接回车保留旧值"
fi

# 服务器 URL
if [[ -n "$OLD_SERVER" ]]; then
  read -rp "moxian-server URL [默认 $OLD_SERVER]: " SERVER
  SERVER="${SERVER:-$OLD_SERVER}"
else
  read -rp "moxian-server URL (如 https://1.2.3.4:7788): " SERVER
fi
[[ -n "$SERVER" ]] || err "URL 必填"

# 邮箱
if [[ -n "$OLD_EMAIL" ]]; then
  read -rp "邮箱 [默认 $OLD_EMAIL]: " EMAIL
  EMAIL="${EMAIL:-$OLD_EMAIL}"
else
  read -rp "邮箱: " EMAIL
fi
[[ -n "$EMAIL" ]] || err "邮箱必填"

# 主密码（明文显示旧值 + 明文输入新值）
# 脚本本地交互运行 client.env 本身明文存储 显示无新增泄漏面
if [[ -n "$OLD_PWD" ]]; then
  read -rp "主密码 [默认 $OLD_PWD]: " PWD
  PWD="${PWD:-$OLD_PWD}"
else
  read -rp "主密码: " PWD
fi
[[ -n "$PWD" ]] || err "密码必填"

# 节点名
DEFAULT_NODE="${OLD_NODE:-$(hostname -s 2>/dev/null || echo "linux-$(date +%s)")}"
read -rp "节点名 [默认 $DEFAULT_NODE]: " NODE
NODE="${NODE:-$DEFAULT_NODE}"

# TLS 跳过
INSECURE_FLAG=""
if [[ "$SERVER" == https://* ]]; then
  if [[ "$OLD_INSECURE" == "-insecure-tls" ]]; then
    DEFAULT_HINT="Y/n"
    DEFAULT_VAL="y"
  else
    DEFAULT_HINT="y/N"
    DEFAULT_VAL="n"
  fi
  read -rp "跳过 TLS 证书验证（家用自签证书选 y）[$DEFAULT_HINT]: " ans
  ans="${ans:-$DEFAULT_VAL}"
  [[ "${ans,,}" == "y" ]] && INSECURE_FLAG="-insecure-tls"
fi

# ---- 写 env 文件 ----
cat > "$ENV_FILE" <<EOF
MOXIAN_SERVER=$SERVER
MOXIAN_EMAIL=$EMAIL
MOXIAN_PASSWORD=$PWD
MOXIAN_NODE=$NODE
MOXIAN_INSECURE=$INSECURE_FLAG
EOF
chmod 600 "$ENV_FILE"
ok "凭据写入 $ENV_FILE (mode 600)"

# ---- systemd unit ----
UNIT=/etc/systemd/system/moxian-client.service

# 保护用户自定义
if [[ -f "$UNIT" ]] && ! grep -q "# moxian-managed" "$UNIT"; then
  BAK="${UNIT}.bak.$(date +%Y%m%d_%H%M%S)"
  warn "$UNIT 似乎被手动改过 备份为 $BAK 并保留原 unit"
  cp "$UNIT" "$BAK"
else
cat > "$UNIT" <<'EOF'
# moxian-managed (此行勿删 重跑安装脚本时用于检测自定义)
[Unit]
Description=moxian-p2p v2 client
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/moxian/client.env
ExecStart=/bin/sh -c '/usr/local/bin/moxian-client \
  -login "$MOXIAN_SERVER" \
  -email "$MOXIAN_EMAIL" \
  -id "$MOXIAN_NODE" \
  $MOXIAN_INSECURE \
  -mesh'
Restart=always
RestartSec=10
LimitNOFILE=65536
# TUN 需要 NET_ADMIN
AmbientCapabilities=CAP_NET_ADMIN
CapabilityBoundingSet=CAP_NET_ADMIN

[Install]
WantedBy=multi-user.target
EOF
  ok "systemd unit: $UNIT"
fi

systemctl daemon-reload
systemctl enable moxian-client >/dev/null 2>&1
systemctl restart moxian-client
sleep 3

if systemctl is-active --quiet moxian-client; then
  ok "🎉 moxian-client 已启动并设置自启"
  echo
  echo -e "${C_INFO}查看日志:${C_OFF} journalctl -u moxian-client -f"
  echo -e "${C_INFO}重启:${C_OFF}     systemctl restart moxian-client"
  echo -e "${C_INFO}修改凭据:${C_OFF} sudo vim $ENV_FILE && sudo systemctl restart moxian-client"
else
  warn "启动失败 看日志:"
  journalctl -u moxian-client -n 20 --no-pager
  exit 1
fi
