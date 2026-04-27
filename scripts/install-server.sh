#!/usr/bin/env bash
# moxian-p2p server 一键安装脚本（v2.x）
#
# 用法（VPS 上 root 跑）：
#   curl -fsSL https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-server.sh | sudo bash
#
# 或者下载到本地后跑：
#   wget https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-server.sh
#   sudo bash install-server.sh
#
# 自动完成：
#   1. 检测 VPS 公网 IP
#   2. 下载最新 v2 server binary
#   3. 生成 JWT 密钥（保存 /etc/moxian/jwt.secret）
#   4. 生成自签 TLS 证书（保存 /etc/moxian/tls/）
#   5. 创建 SQLite DB 目录
#   6. 写 systemd unit + 启动 + 自启
#   7. 配置防火墙（firewalld 或 ufw）
#   8. 输出 Web 面板地址 + 后续手动操作指引
#
# 重新运行此脚本 = 升级 binary（密钥 / DB / 用户数据保留）

set -euo pipefail

C_OK="\033[32m"; C_WARN="\033[33m"; C_ERR="\033[31m"; C_INFO="\033[36m"; C_OFF="\033[0m"
ok()   { echo -e "${C_OK}[ ok ]${C_OFF} $*"; }
info() { echo -e "${C_INFO}[info]${C_OFF} $*"; }
warn() { echo -e "${C_WARN}[warn]${C_OFF} $*"; }
err()  { echo -e "${C_ERR}[err ]${C_OFF} $*"; exit 1; }

[[ $EUID -eq 0 ]] || err "必须 root 运行 (sudo bash $0)"

# ---- 0. 平台检测 ----
ARCH=""
case "$(uname -m)" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) err "不支持的架构: $(uname -m)" ;;
esac
info "架构: linux-$ARCH"

# ---- 1. 检测公网 IP ----
PUBLIC_IP="${MOXIAN_PUBLIC_IP:-}"
if [[ -z "$PUBLIC_IP" ]]; then
  info "检测公网 IP..."
  for src in ifconfig.me ip.sb api.ipify.org; do
    PUBLIC_IP=$(curl -4 -s --max-time 5 "$src" || true)
    [[ -n "$PUBLIC_IP" ]] && break
  done
  [[ -n "$PUBLIC_IP" ]] || err "无法检测公网 IP 请设环境变量 MOXIAN_PUBLIC_IP=x.x.x.x 重试"
fi
ok "公网 IP: $PUBLIC_IP"

# ---- 2. 下载 binary ----
LATEST_TAG=$(curl -fsSL https://api.github.com/repos/Mo-Xian/moxian-p2p/releases/latest \
  | grep -oP '"tag_name":\s*"\K[^"]+' | head -1)
[[ -n "$LATEST_TAG" ]] || err "拿不到最新版本号 网络问题？"
info "最新版本: $LATEST_TAG"

BIN_URL="https://github.com/Mo-Xian/moxian-p2p/releases/download/$LATEST_TAG/moxian-server-linux-$ARCH"
info "下载 binary: $BIN_URL"
TMP_BIN=$(mktemp)
curl -fSL -o "$TMP_BIN" "$BIN_URL" || err "下载失败 网络/镜像/代理？"
chmod +x "$TMP_BIN"
install -m 755 "$TMP_BIN" /usr/local/bin/moxian-server
rm -f "$TMP_BIN"
ok "已安装到 /usr/local/bin/moxian-server"

# ---- 3. 准备目录 ----
install -d -m 755 /etc/moxian /etc/moxian/tls
install -d -m 700 /var/lib/moxian

# ---- 4. JWT 密钥（已存在则保留 升级时不重置）----
JWT_FILE=/etc/moxian/jwt.secret
if [[ ! -s "$JWT_FILE" ]]; then
  openssl rand -hex 32 > "$JWT_FILE"
  chmod 600 "$JWT_FILE"
  ok "JWT 密钥已生成 $JWT_FILE"
else
  ok "JWT 密钥已存在 保留 $JWT_FILE"
fi

# ---- 5. 自签 TLS 证书（已存在则保留 除非 5 年内将过期）----
CERT=/etc/moxian/tls/cert.pem
KEY=/etc/moxian/tls/key.pem
NEED_GEN_CERT=false
if [[ ! -s "$CERT" || ! -s "$KEY" ]]; then
  NEED_GEN_CERT=true
elif ! openssl x509 -in "$CERT" -noout -checkend 157680000 >/dev/null 2>&1; then
  warn "证书将在 5 年内过期 重新生成"
  NEED_GEN_CERT=true
fi

if [[ "$NEED_GEN_CERT" == true ]]; then
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout "$KEY" -out "$CERT" \
    -subj "/CN=moxian-p2p" \
    -addext "subjectAltName=IP:${PUBLIC_IP},DNS:moxian-p2p" 2>/dev/null
  chmod 600 "$KEY"
  ok "自签证书已生成 (10 年有效) $CERT / $KEY"
else
  ok "证书已存在 保留"
fi

# ---- 6. systemd unit ----
UNIT=/etc/systemd/system/moxian-server.service

# 保护用户的自定义：检测 unit 是否被改过 改过就备份
# 判定依据：unit 内容含我们以前生成的 marker（# moxian-managed）
WRITE_UNIT=true
if [[ -f "$UNIT" ]]; then
  if ! grep -q "# moxian-managed" "$UNIT"; then
    BAK="${UNIT}.bak.$(date +%Y%m%d_%H%M%S)"
    warn "$UNIT 似乎被你手动改过（无 moxian-managed 标记）"
    warn "已备份为 $BAK"
    cp "$UNIT" "$BAK"
    if [[ -t 0 ]]; then
      read -rp "覆盖 unit 用脚本默认配置？[y/N] " ans
      [[ "${ans,,}" == "y" ]] || WRITE_UNIT=false
    else
      warn "非交互运行（管道）默认不覆盖 自定义保留"
      WRITE_UNIT=false
    fi
  fi
fi

if [[ "$WRITE_UNIT" == true ]]; then
cat > "$UNIT" <<EOF
# moxian-managed (此行勿删 重跑安装脚本时用于检测自定义)
[Unit]
Description=moxian-p2p v2 signaling + auth server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/bin/sh -c '/usr/local/bin/moxian-server \\
  -host ${PUBLIC_IP} \\
  -ws :7788 -udp :7789 -udp2 :7790 \\
  -tls-cert ${CERT} \\
  -tls-key  ${KEY} \\
  -db /var/lib/moxian/moxian.db \\
  -jwt-secret "\$(cat ${JWT_FILE})"'
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
  ok "systemd unit 已写入 $UNIT"
else
  ok "systemd unit 保留用户自定义（未覆盖）"
fi

systemctl daemon-reload
systemctl enable moxian-server >/dev/null 2>&1
systemctl restart moxian-server
sleep 2

if systemctl is-active --quiet moxian-server; then
  ok "服务已启动"
else
  warn "服务启动失败 看日志:"
  journalctl -u moxian-server -n 20 --no-pager
  exit 1
fi

# ---- 7. 防火墙 ----
if command -v firewall-cmd >/dev/null 2>&1; then
  info "配置 firewalld..."
  firewall-cmd --permanent --add-port=7788/tcp >/dev/null
  firewall-cmd --permanent --add-port=7789/udp >/dev/null
  firewall-cmd --permanent --add-port=7790/udp >/dev/null
  firewall-cmd --reload >/dev/null
  ok "firewalld 已放行 7788/tcp + 7789-7790/udp"
elif command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  info "配置 ufw..."
  ufw allow 7788/tcp >/dev/null
  ufw allow 7789/udp >/dev/null
  ufw allow 7790/udp >/dev/null
  ok "ufw 已放行 7788/tcp + 7789-7790/udp"
else
  warn "未检测到 firewalld/ufw 请手动确认 7788/tcp + 7789-7790/udp 已放行"
  warn "云厂商安全组也要单独配置"
fi

# ---- 8. 完成 + 输出 ----
echo
echo -e "${C_OK}═══════════════════════════════════════════════════════${C_OFF}"
echo -e "${C_OK} 🎉 moxian-p2p server $LATEST_TAG 安装完成${C_OFF}"
echo -e "${C_OK}═══════════════════════════════════════════════════════${C_OFF}"
echo
echo "  Web 面板:   https://${PUBLIC_IP}:7788/"
echo "  数据库:     /var/lib/moxian/moxian.db"
echo "  JWT 密钥:   $JWT_FILE"
echo "  TLS 证书:   $CERT (自签 10 年)"
echo "  日志:       journalctl -u moxian-server -f"
echo
echo -e "${C_INFO}下一步:${C_OFF}"
echo "  1. 浏览器开 https://${PUBLIC_IP}:7788/"
echo "     (会提示证书不受信任 → 点'高级'→'继续访问')"
echo "  2. 选'注册' Tab 输邮箱+用户名+密码 邀请码留空 → 自动成首位管理员"
echo "  3. 装 Android APK https://github.com/Mo-Xian/moxian-p2p/releases/latest"
echo "     登录时勾选 '接受自签证书'"
echo
echo -e "${C_INFO}升级:${C_OFF} 重跑此脚本 (密钥/证书/数据保留)"
echo -e "${C_INFO}修改配置:${C_OFF} sudo vim $UNIT && sudo systemctl restart moxian-server"
echo
