# moxian-nas-stack Windows 初始化脚本
#
# 作用：
#   1. 检查 Docker Desktop 是否可用
#   2. 创建数据目录骨架（默认 D:\nas-data）
#   3. 从 .env.example 生成 .env
#   4. 检查 CHANGEME 占位符并提醒用户修改
#   5. 可选：立即 docker compose up -d
#
# 用法（任意 PowerShell，**不需要管理员**）：
#   cd moxian-p2p\examples\nas-stack
#   .\bootstrap.ps1
#
# 前置：
#   - Windows 10 / 11
#   - Docker Desktop 已装且正在运行
#     下载：https://www.docker.com/products/docker-desktop
#
# 幂等：可重复跑 不会破坏已有配置

$ErrorActionPreference = "Stop"

function Info($msg)  { Write-Host "[info] $msg" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "[ ok ] $msg" -ForegroundColor Green }
function Warn($msg)  { Write-Host "[warn] $msg" -ForegroundColor Yellow }
function Err($msg)   { Write-Host "[err ] $msg" -ForegroundColor Red; exit 1 }

# ---- Step 1: 检查 Docker ----
Info "=== Step 1/4 检查 Docker Desktop ==="
try {
    $dockerVersion = docker --version 2>$null
    if (-not $dockerVersion) { throw "docker 命令不可用" }
    Ok "Docker 已安装: $dockerVersion"
} catch {
    Err "Docker 不可用。请先装 Docker Desktop 并启动：`n  https://www.docker.com/products/docker-desktop"
}

try {
    docker ps 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker daemon 未运行" }
    Ok "Docker daemon 正常运行"
} catch {
    Err "Docker daemon 未启动。请从开始菜单启动 Docker Desktop 等它变绿后再跑。"
}

try {
    $composeVersion = docker compose version 2>$null
    Ok "Docker Compose: $composeVersion"
} catch {
    Err "docker compose 不可用。Docker Desktop 版本太老 请升级到 4.x+"
}

# ---- Step 2: 选数据目录 + 建骨架 ----
Info "=== Step 2/4 数据目录 ==="

$defaultRoot = "D:\nas-data"
$dataRoot = Read-Host "数据根目录 [默认 $defaultRoot]"
if ([string]::IsNullOrWhiteSpace($dataRoot)) { $dataRoot = $defaultRoot }

# 支持相对路径 / 清理末尾斜杠
$dataRoot = $dataRoot.TrimEnd('\','/').Replace('/','\')

if (-not (Test-Path $dataRoot)) {
    Info "创建 $dataRoot"
    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
}

$subdirs = @(
    "photos",
    "media\movies", "media\tv", "media\music",
    "docs",
    "downloads",
    "backups",
    "appdata"
)
foreach ($sub in $subdirs) {
    $p = Join-Path $dataRoot $sub
    if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
}
Ok "目录骨架就绪: $dataRoot"

# Docker Compose 需要正斜杠路径
$dataRootCompose = $dataRoot.Replace('\','/')

# ---- Step 3: 生成 .env ----
Info "=== Step 3/4 配置文件 ==="

$scriptDir = $PSScriptRoot
$envPath = Join-Path $scriptDir ".env"
$envExample = Join-Path $scriptDir ".env.example"

# 生成 32 位随机密码（字母数字）
function New-RandomPassword {
    param([int]$Length = 32)
    $chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    -join ((1..$Length) | ForEach-Object { $chars[(Get-Random -Max $chars.Length)] })
}

# 生成 48 位 base64（适合 Vaultwarden admin token）
function New-Base64Token {
    $bytes = New-Object byte[] 36
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes)
}

if (-not (Test-Path $envPath)) {
    if (-not (Test-Path $envExample)) {
        Err "找不到 .env.example 请确认脚本运行在 nas-stack 目录下"
    }
    Copy-Item $envExample $envPath
    Ok ".env 已从模板创建"

    # 自动填入 DATA_ROOT
    $envText = Get-Content $envPath -Raw
    $envText = $envText -replace '(?m)^DATA_ROOT=.*$', "DATA_ROOT=$dataRootCompose"

    # 自动生成随机密码替换所有 CHANGEME
    $immichPwd = New-RandomPassword -Length 32
    $vwToken = New-Base64Token
    $envText = $envText -replace 'CHANGEME-immich-db-strong-password', $immichPwd
    $envText = $envText -replace 'CHANGEME-use-openssl-rand-base64-48', $vwToken
    # 兜底 替换所有剩余 CHANGEME-* 为通用密码
    $envText = $envText -replace 'CHANGEME-[a-zA-Z0-9-]+', (New-RandomPassword -Length 24)

    Set-Content -Path $envPath -Value $envText -NoNewline
    Ok "DATA_ROOT 和所有密码已自动生成 写入 .env"
    Info "  Immich DB 密码：$immichPwd"
    Info "  Vaultwarden ADMIN_TOKEN：$vwToken"
    Info "（如需查看所有密码：notepad $envPath）"
} else {
    Ok ".env 已存在 跳过（如需重置 删 .env 再跑此脚本）"

    # 仍然检查占位符 以防用户手动改过但还有漏网
    $envContent = Get-Content $envPath -Raw
    if ($envContent -match 'CHANGEME') {
        Warn ".env 中仍有 CHANGEME 占位符 建议替换为强密码"
        Select-String -Path $envPath -Pattern 'CHANGEME' | ForEach-Object {
            Write-Host "  $($_.LineNumber): $($_.Line)" -ForegroundColor Yellow
        }
    }
}

# moxian client.yaml 也自动复制（可选 Windows 下一般跑原生 gui 不用容器）
$moxianCfgDir = Join-Path $scriptDir "configs\moxian"
$moxianCfg = Join-Path $moxianCfgDir "client.yaml"
$moxianCfgExample = Join-Path $moxianCfgDir "client.yaml.example"
if (-not (Test-Path $moxianCfg) -and (Test-Path $moxianCfgExample)) {
    Copy-Item $moxianCfgExample $moxianCfg
    Ok "moxian client.yaml 已从模板创建（仅 Linux 用 overlay 时需要 Windows 可忽略）"
}

# ---- Step 4: 启动 ----
Info "=== Step 4/4 启动服务 ==="

$ans = Read-Host "现在立刻 docker compose up -d 启动？[Y/n]"
if ($ans -notmatch '^(n|N)') {
    Push-Location $scriptDir
    try {
        Info "拉取镜像 + 启动服务（首次 3-5 分钟 约 5G 镜像）..."
        docker compose up -d
        if ($LASTEXITCODE -eq 0) {
            Write-Host ""
            Ok "全部服务已启动"
            Write-Host ""
            docker compose ps
        } else {
            Warn "docker compose up 报错 见上方日志"
        }
    } finally {
        Pop-Location
    }
} else {
    Info "跳过启动。启动命令: cd `"$scriptDir`"; docker compose up -d"
}

# ---- 收尾 ----
Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host " bootstrap 完成 访问入口（替换为实际 IP）" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""
Write-Host "  本机访问："
Write-Host "    Immich:      http://localhost:2283"
Write-Host "    Jellyfin:    http://localhost:8096"
Write-Host "    Syncthing:   http://localhost:8384"
Write-Host "    Vaultwarden: http://localhost:8080"
Write-Host "    qBittorrent: http://localhost:8081"
Write-Host ""
Write-Host "  局域网其他设备（手机/平板）访问："
$lanIp = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.InterfaceAlias -notmatch 'Loopback|vEthernet|WSL' -and $_.PrefixOrigin -eq 'Dhcp' } |
    Select-Object -First 1 -ExpandProperty IPAddress)
if ($lanIp) {
    Write-Host "    http://$lanIp`:<端口>"
    Write-Host "    检测到本机局域网 IP: $lanIp"
} else {
    Write-Host "    用 ipconfig 查本机 IPv4"
}
Write-Host ""
Write-Host "  下一步建议（看 README-windows.md 详细说明）："
Write-Host "    1) 浏览器访问任一应用开始使用"
Write-Host "    2) 需要从外网访问？下载原生 moxian-gui.exe 装托盘："
Write-Host "         https://github.com/Mo-Xian/moxian-p2p/releases/latest"
Write-Host "    3) 右键 $dataRoot → 属性 → 共享 开 SMB 让其他设备访问文件"
Write-Host "    4) 装 Windows Defender 排除以加速 Docker I/O："
Write-Host "         Add-MpPreference -ExclusionPath `"$dataRoot`""
Write-Host ""
