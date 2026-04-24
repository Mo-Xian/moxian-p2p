# moxian-p2p v2.0 升级指南

v2 是一次**不兼容的大改**：引入用户账号体系和零知识加密 vault。老的 YAML 配置模式已废弃，所有客户端必须通过登录认证。

## 架构对比

| 维度 | v1.x | v2.0 |
|------|------|------|
| 配置分发 | 每个 client 手动写 `client.yaml` | 服务器下发 客户端登录后自动拉 |
| P2P passphrase | 双方手动保持一致 | 服务器生成 登录后下发 |
| NAS 服务凭据 | 各 Activity 单独存本地 | 加密 vault 同步服务器 多端同步 |
| 认证 | pass + token（对称）| JWT + Argon2id 密码哈希 |
| 用户数 | 单机部署 | 多用户 + 邀请码 |
| 管理界面 | 无（或 Basic Auth） | Web 面板 |

## 安全模型（零知识）

```
用户主密码（记在用户脑子里）
  ↓ PBKDF2-SHA256(password, email, 600k 迭代)
masterKey（32 字节 仅进程内存 从不离开客户端）
  ├─ PBKDF2(masterKey, password, 1) → pwdHash → 传服务器做登录
  └─ HKDF(masterKey) → encKey + macKey
      └─ AES-CBC-HMAC(encKey, macKey) 加解密 vault blob

服务器侧存储：
  users.password_hash       = Argon2id(pwdHash, random_salt)  ← 无法反推
  users.encrypted_vault     = 客户端加密的密文                  ← 服务器没密钥
```

**服务器被黑后果**：攻击者拿到 Argon2id 哈希（暴破极慢）+ AES 密文（无 masterKey 无法解）。**核心秘密仍在用户主密码里**，不泄漏。

## 服务器部署

### 新部署

```bash
# 1. 下载最新 v2 binary
wget https://github.com/Mo-Xian/moxian-p2p/releases/download/v2.0.0/moxian-server-linux-amd64
mkdir -p /var/lib/moxian

# 2. 生成 JWT 签名密钥（永久保存 换了所有会话失效）
JWT_SECRET=$(openssl rand -hex 32)
echo "MOXIAN_JWT_SECRET=$JWT_SECRET" >> /etc/moxian/env

# 3. 启动
./moxian-server \
  -host your.vps.com \
  -ws :7788 -udp :7789 \
  -tls-cert /etc/letsencrypt/live/xxx/fullchain.pem \
  -tls-key  /etc/letsencrypt/live/xxx/privkey.pem \
  -db /var/lib/moxian/moxian.db \
  -jwt-secret "$JWT_SECRET"

# 4. 浏览器访问 https://your.vps.com:7788/
#    注册第一个用户 邀请码留空 → 自动成管理员
```

### 从 v1 迁移

**v2 不兼容 v1 的 YAML 配置**。流程：

```bash
# 1. 停 v1 服务
systemctl stop moxian-server

# 2. 备份老数据（如有）
mv /var/lib/moxian /var/lib/moxian.v1.bak

# 3. 装 v2 + 启动（见上）

# 4. Web 面板注册管理员 + 为每个老节点生成邀请码
# 5. 通知各客户端用户升级 APP / 更新二进制 + 重登录
```

老的 YAML 配置文件可以丢弃。新客户端从服务器拉取一切。

## 客户端：Android APP

装 v2 APK 后首次打开进入 **LoginActivity**：

1. **服务器 URL**：`https://your.vps.com:7788`
2. **邮箱 / 主密码 / 用户名 / 邀请码**（注册 Tab）
3. 注册后自动登录
4. JWT + encrypted_vault 本地持久化
5. 下次打开：JWT 有效则跳 UnlockActivity 输主密码解锁

**主页自动从 /api/config 拉 moxian-p2p 配置**，用户不用再手填 server/udp/pass。

**7 个 NAS 服务**（Jellyfin/Immich/Navidrome/Vaultwarden/qBittorrent/Syncthing/AdGuard）凭据自动从 vault 读取，登录一次后永久同步到所有设备。

**自动锁定**：APP 在后台超过 10 分钟清 masterKey，下次打开需重输主密码（JWT 仍有效无需重新注册）。

## 客户端：CLI / Windows GUI

v2 CLI 模式：

```bash
# 老模式（YAML 配置）已废弃，但命令行 flag 仍可用
# 推荐 v2 模式：

export MOXIAN_PASSWORD='你的主密码'
moxian-client -login https://your.vps.com:7788 -email you@example.com -id laptop

# 或纯手动（password 在 cmdline 上不安全 仅测试用）
moxian-client -login ... -email ... -password "xxx" -id nas
```

CLI 会：
1. 连服务器 /api/auth/prelogin 取 KDF 参数
2. 派生 pwdHash 发给 /api/auth/login 拿 JWT
3. GET /api/config?node=xxx 拉完整配置
4. 若节点未注册 先 POST /api/nodes 注册再拉
5. 进入正常的 moxian-p2p P2P 流程

## Web 管理面板

浏览器访问服务器根路径（`https://your.vps.com:7788/`）：

- **登录 Tab**：现有用户
- **注册 Tab**：邀请码注册（首位管理员免填）
- **管理员视图**（登录后）：
  - 节点列表（自己 + 按需添加）
  - 邀请码列表 + 一键生成 24h 新码
  - 所有用户列表

## 故障排查

| 问题 | 解决 |
|------|------|
| 忘记主密码 | **无法找回**（零知识模型的代价）。管理员在 Web 面板删账号重新注册。老 vault 数据永久丢失。 |
| JWT 过期后登录失败 | 默认 24h 过期。重新输主密码即可。 |
| APP 打不开 Web 面板看到 "unauthorized" | JWT 存在但过期了。清 APP 数据重新登录。 |
| Web 面板注册报 "邀请码无效" | 管理员先登录生成新邀请码。首位注册才免码。 |
| 节点 ID 冲突 | 服务器按 `(user_id, node_id)` 唯一。不同用户的同名节点不冲突。 |
| `moxian-server` 启动报 "open db" | `-db` 指向的路径不可写。用 `mkdir -p` 建父目录并设权限。 |

## 技术参数

- 客户端 KDF：PBKDF2-SHA256 600,000 迭代
- 服务器 KDF：Argon2id m=64MiB t=3 p=4
- Vault 加密：AES-256-CBC + HMAC-SHA256（Bitwarden type-2 EncString）
- JWT 签名：HS256（默认 24h 过期）
- 数据库：SQLite（WAL 模式 嵌入）
- 邀请码：8 字符大写字母+数字（去掉 0OIl 易混字符）
