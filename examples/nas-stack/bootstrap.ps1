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

if (-not (Test-Path $envPath)) {
    if (-not (Test-Path $envExample)) {
        Err "找不到 .env.example 请确认脚本运行在 nas-stack 目录下"
    }
    Copy-Item $envExample $envPath
    Ok ".env 已从模板创建"

    # 自动填入 DATA_ROOT 用户刚才选的路径
    (Get-Content $envPath) `
        -replace '^DATA_ROOT=.*$', "DATA_ROOT=$dataRootCompose" |
        Set-Content $envPath
    Ok "DATA_ROOT=$dataRootCompose 已写入 .env"
} else {
    Ok ".env 已存在 跳过（如需重置 手动删 .env 再跑）"
}

# moxian client.yaml 也自动复制
$moxianCfgDir = Join-Path $scriptDir "configs\moxian"
$moxianCfg = Join-Path $moxianCfgDir "client.yaml"
$moxianCfgExample = Join-Path $moxianCfgDir "client.yaml.example"
if (-not (Test-Path $moxianCfg) -and (Test-Path $moxianCfgExample)) {
    Copy-Item $moxianCfgExample $moxianCfg
    Ok "moxian client.yaml 已从模板创建"
}

# 检查 CHANGEME 占位符
$envContent = Get-Content $envPath -Raw
if ($envContent -match 'CHANGEME') {
    Warn ".env 中仍有 CHANGEME 占位符 启动前必须替换成强密码！"
    Select-String -Path $envPath -Pattern 'CHANGEME' | ForEach-Object {
        Write-Host "  $($_.LineNumber): $($_.Line)" -ForegroundColor Yellow
    }
}

# ---- Step 4: 可选启动 ----
Info "=== Step 4/4 启动服务（可选）==="

$ans = Read-Host "现在立刻 docker compose up -d 启动？[y/N]"
if ($ans -match '^(y|Y)') {
    if ($envContent -match 'CHANGEME') {
        Warn "你的 .env 还有 CHANGEME 密码 强烈建议先改再启动"
        $confirm = Read-Host "真的要用默认占位密码启动？[y/N]"
        if ($confirm -notmatch '^(y|Y)') {
            Info "已取消启动。编辑 $envPath 后手动跑: docker compose up -d"
            exit 0
        }
    }

    Push-Location $scriptDir
    try {
        Info "拉取镜像 + 启动服务（首次 3-5 分钟）..."
        docker compose up -d
        Write-Host ""
        Ok "全部服务已启动 查看状态: docker compose ps"
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
Write-Host "    1) 编辑 .env 把所有 CHANGEME 改成强密码 然后 docker compose up -d 重启"
Write-Host "    2) 编辑 configs\moxian\client.yaml 填信令 server 和 passphrase"
Write-Host "    3) 右键 $dataRoot → 属性 → 共享 开启 SMB 让局域网访问文件"
Write-Host "    4) Windows Defender 添加 $dataRoot 为排除项避免 Docker I/O 慢"
Write-Host ""
