# moxian-p2p Windows 客户端一键安装脚本（v2.x）
#
# 用法（管理员 PowerShell）：
#   irm https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.ps1 | iex
#
# 自动完成：
#   1. 下载最新 moxian-gui.exe + wintun.dll 到 C:\Program Files\moxian-p2p\
#   2. 询问服务器 URL / 邮箱 / 密码 / 节点名
#   3. 写 client.yaml（仅 v2 登录字段 GUI 启动时自动登录拉 P2P 配置）
#   4. 创建桌面快捷方式（带管理员标记）
#   5. 提示用户双击桌面图标启动
#
# 重新运行 = 升级 binary（凭据保留）
# 安装脚本不再注册开机自启任务 不自动启动 GUI 用户手动控制

$ErrorActionPreference = "Stop"

function Info($m) { Write-Host "[info] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[warn] $m" -ForegroundColor Yellow }
function Err($m)  { Write-Host "[err ] $m" -ForegroundColor Red; exit 1 }

# ---- 0. 检查管理员 ----
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Err "请用管理员 PowerShell 运行（TUN 驱动需要管理员）" }

$INSTALL_DIR = "C:\Program Files\moxian-p2p"
if (-not (Test-Path $INSTALL_DIR)) { New-Item -ItemType Directory -Path $INSTALL_DIR -Force | Out-Null }

# ---- 1. 拿最新版本号 ----
Info "查询最新版本..."
$rel = Invoke-RestMethod "https://api.github.com/repos/Mo-Xian/moxian-p2p/releases/latest"
$tag = $rel.tag_name
Info "最新: $tag"

# 已装版本（写在 INSTALL_DIR\.installed_tag 一行 tag）
$TAG_FILE = "$INSTALL_DIR\.installed_tag"
$installedTag = if (Test-Path $TAG_FILE) { (Get-Content $TAG_FILE -Raw).Trim() } else { "" }

# 必要文件齐全 + tag 一致 → 跳过下载
$REQUIRED_FILES = @(
    "$INSTALL_DIR\moxian-gui.exe",
    "$INSTALL_DIR\moxian-client.exe",
    "$INSTALL_DIR\wintun.dll"
)
$allExist = $true
foreach ($f in $REQUIRED_FILES) { if (-not (Test-Path $f)) { $allExist = $false; break } }

if ($installedTag -eq $tag -and $allExist) {
    Ok "已是最新版 $tag 跳过下载（删除 $TAG_FILE 强制重装）"
} else {
    if ($installedTag) { Info "升级 $installedTag → $tag" } else { Info "首次安装 $tag" }

    function DownloadAsset($name, $dest) {
        $asset = $rel.assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if (-not $asset) { Err "Release 没找到 $name" }
        Info "下载 $name → $dest"
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $dest -UseBasicParsing
    }

    DownloadAsset "moxian-gui.exe"        "$INSTALL_DIR\moxian-gui.exe"
    DownloadAsset "moxian-client.exe"     "$INSTALL_DIR\moxian-client.exe"
    DownloadAsset "wintun.dll"            "$INSTALL_DIR\wintun.dll"
    Set-Content -Path $TAG_FILE -Value $tag -Encoding ASCII
    Ok "二进制下载完成 $tag"
}

# ---- 2. 收集凭据（每次都问 但显示默认值 回车保留）----
$YAML = "$INSTALL_DIR\client.yaml"

# 读旧 yaml（如果存在）作为默认值
function Get-YamlValue($path, $key) {
    if (-not (Test-Path $path)) { return "" }
    $line = Get-Content $path | Where-Object { $_ -match "^\s*$key\s*:" } | Select-Object -First 1
    if (-not $line) { return "" }
    $val = ($line -split ":", 2)[1].Trim()
    # 去引号 + YAML 单引号转义还原（''→'）
    if ($val.StartsWith('"') -and $val.EndsWith('"')) {
        $val = $val.Substring(1, $val.Length - 2)
    } elseif ($val.StartsWith("'") -and $val.EndsWith("'")) {
        $val = $val.Substring(1, $val.Length - 2) -replace "''", "'"
    }
    return $val
}

$oldServer   = Get-YamlValue $YAML "server"
$oldEmail    = Get-YamlValue $YAML "email"
$oldPwd      = Get-YamlValue $YAML "password"
$oldNode     = Get-YamlValue $YAML "node"
$oldInsecure = Get-YamlValue $YAML "insecure_tls"

Write-Host ""
if ($oldServer) {
    Write-Host "检测到现有配置（回车保留旧值）:" -ForegroundColor Cyan
} else {
    Write-Host "请输入连接信息:" -ForegroundColor Cyan
}

# 服务器 URL
$prompt = if ($oldServer) { "moxian-server URL [默认: $oldServer]" } else { "moxian-server URL (如 https://1.2.3.4:7788)" }
$server = Read-Host $prompt
if (-not $server) {
    if ($oldServer) { $server = $oldServer } else { Err "URL 必填" }
}

# 邮箱
$prompt = if ($oldEmail) { "邮箱 [默认: $oldEmail]" } else { "邮箱" }
$email = Read-Host $prompt
if (-not $email) {
    if ($oldEmail) { $email = $oldEmail } else { Err "邮箱必填" }
}

# 主密码（明文显示旧值 + 明文输入新值 让用户能看到）
# 安全考虑：脚本本来就是本地交互运行 yaml 也是明文存储 显示无新增泄漏面
$pwdPlain = ""
if ($oldPwd) {
    $pwdInput = Read-Host "主密码 [默认: $oldPwd]"
    if (-not $pwdInput) { $pwdPlain = $oldPwd } else { $pwdPlain = $pwdInput }
} else {
    $pwdInput = Read-Host "主密码"
    if (-not $pwdInput) { Err "密码必填" }
    $pwdPlain = $pwdInput
}

# 节点名
$defaultNode = if ($oldNode) { $oldNode } else { $env:COMPUTERNAME }
$node = Read-Host "节点名 [默认: $defaultNode]"
if (-not $node) { $node = $defaultNode }

# TLS 跳过
$insecure = "false"
if ($server.StartsWith("https://")) {
    $defaultAns = if ($oldInsecure -eq "true") { "Y/n" } else { "y/N" }
    $insecureAns = Read-Host "跳过 TLS 证书验证（家用自签证书选 y）[$defaultAns]"
    if (-not $insecureAns) {
        $insecure = if ($oldInsecure -eq "true") { "true" } else { "false" }
    } else {
        $insecure = if ($insecureAns -match '^y') { "true" } else { "false" }
    }
}

# 写 client.yaml（仅登录凭据 真实 P2P 配置由 GUI 启动时自动从服务器拉）
# 用 YAML 单引号字符串：仅需把 ' 替换成 ''，不用管 \ 和 "
function YamlSingle($s) { return "'" + ($s -replace "'", "''") + "'" }
$yamlContent = @"
# moxian-p2p 客户端配置
# 启动时 moxian-gui 用下方凭据登录服务器 自动拉 pass/server/server_udp/virtual_ip
# 改密码 / 迁服务器：编辑此文件后重启 GUI

server: $(YamlSingle $server)
email: $(YamlSingle $email)
password: $(YamlSingle $pwdPlain)
node: $(YamlSingle $node)
insecure_tls: $insecure
"@
Set-Content -Path $YAML -Value $yamlContent -Encoding UTF8
icacls $YAML /inheritance:r /grant:r "$($env:USERNAME):F" /grant:r "Administrators:F" 2>&1 | Out-Null
Ok "client.yaml 已写入: $YAML"

# ---- 3. 创建桌面快捷方式（带管理员标记）----
$shell = New-Object -ComObject WScript.Shell
$desktopShortcut = "$([Environment]::GetFolderPath('Desktop'))\moxian-p2p.lnk"
$lnk = $shell.CreateShortcut($desktopShortcut)
$lnk.TargetPath = "$INSTALL_DIR\moxian-gui.exe"
$lnk.WorkingDirectory = $INSTALL_DIR
$lnk.IconLocation = "$INSTALL_DIR\moxian-gui.exe"
$lnk.Save()

# 让快捷方式带"以管理员运行"标志
$bytes = [System.IO.File]::ReadAllBytes($desktopShortcut)
$bytes[0x15] = $bytes[0x15] -bor 0x20
[System.IO.File]::WriteAllBytes($desktopShortcut, $bytes)
Ok "桌面快捷方式创建（自动以管理员运行）"

# ---- 4. 清理之前装过的开机自启任务（如有 当前版本不再注册自启）----
$taskName = "moxian-p2p"
$existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existingTask) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    Info "已移除旧版的开机自启任务（当前版本不再自启）"
}

# ---- 5. 完成 ----
Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host " 🎉 moxian-p2p $tag 安装完成 " -ForegroundColor Green
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host ""
Write-Host "  安装位置: $INSTALL_DIR"
Write-Host "  配置文件: $YAML"
Write-Host "  日志:     $INSTALL_DIR\moxian.log"
Write-Host "  桌面图标: $desktopShortcut"
Write-Host ""
Write-Host "下一步：" -ForegroundColor Cyan
Write-Host "  双击桌面 'moxian-p2p' 图标启动（自动以管理员运行）"
Write-Host "  托盘出现图标后会自动用凭据登录 + 连接 P2P"
Write-Host ""
Write-Host "管理：" -ForegroundColor Cyan
Write-Host "  托盘图标右键 看状态 / 查看日志 / 停止 / 退出"
Write-Host ""
Write-Host "升级: 重跑此脚本 (凭据保留)"
Write-Host "改密码 / 迁服务器: 编辑 $YAML 然后重启 GUI"
Write-Host ""
