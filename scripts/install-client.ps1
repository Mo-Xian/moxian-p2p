# moxian-p2p Windows 客户端一键安装脚本（v2.x）
#
# 用法（管理员 PowerShell）：
#   irm https://raw.githubusercontent.com/Mo-Xian/moxian-p2p/main/scripts/install-client.ps1 | iex
#
# 自动完成：
#   1. 下载最新 moxian-gui.exe + wintun.dll 到 C:\Program Files\moxian-p2p\
#   2. 询问服务器 URL / 邮箱 / 密码 / 节点名
#   3. 写 client.yaml（不带凭据 凭据通过 GUI 登录后保存）
#   4. 创建桌面 + 开始菜单快捷方式（带管理员标记）
#   5. 注册"Windows 登录后自启"任务
#
# 重新运行 = 升级 binary（凭据保留）

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

function DownloadAsset($name, $dest) {
    $asset = $rel.assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
    if (-not $asset) { Err "Release 没找到 $name" }
    Info "下载 $name → $dest"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $dest -UseBasicParsing
}

DownloadAsset "moxian-gui.exe"        "$INSTALL_DIR\moxian-gui.exe"
DownloadAsset "moxian-client.exe"     "$INSTALL_DIR\moxian-client.exe"
DownloadAsset "wintun.dll"            "$INSTALL_DIR\wintun.dll"
Ok "二进制下载完成"

# ---- 2. 收集凭据（每次都问 但显示默认值 回车保留）----
$YAML = "$INSTALL_DIR\client.yaml"

# 读旧 yaml（如果存在）作为默认值
function Get-YamlValue($path, $key) {
    if (-not (Test-Path $path)) { return "" }
    $line = Get-Content $path | Where-Object { $_ -match "^\s*$key\s*:" } | Select-Object -First 1
    if (-not $line) { return "" }
    $val = ($line -split ":", 2)[1].Trim()
    # 去引号
    if ($val.StartsWith('"') -and $val.EndsWith('"')) { $val = $val.Substring(1, $val.Length - 2) }
    return $val
}

$oldServer   = Get-YamlValue $YAML "v2_server"
$oldEmail    = Get-YamlValue $YAML "v2_email"
$oldPwd      = Get-YamlValue $YAML "v2_password"
$oldNode     = Get-YamlValue $YAML "v2_node"
$oldInsecure = Get-YamlValue $YAML "v2_insecure_tls"

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

# 主密码
$pwdPlain = ""
if ($oldPwd) {
    $pwdLen = $oldPwd.Length
    $hint = "主密码 [回车保留旧值 已设置 $pwdLen 位]"
    Write-Host -NoNewline "$hint`: "
    $pwd = Read-Host -AsSecureString
    $pwdPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwd)
    )
    if (-not $pwdPlain) { $pwdPlain = $oldPwd }
} else {
    $pwd = Read-Host "主密码（输入时不显示）" -AsSecureString
    $pwdPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwd)
    )
    if (-not $pwdPlain) { Err "密码必填" }
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

# 写 client.yaml
$yamlContent = @"
# moxian-p2p v2 客户端配置
# 由 install-client.ps1 自动生成 重跑脚本会保留旧值并允许覆盖

# v2 登录信息（GUI 启动时用这些自动登录服务器拉配置）
v2_server: "$server"
v2_email: "$email"
v2_password: "$pwdPlain"
v2_node: "$node"
v2_insecure_tls: $insecure

# 以下字段由服务器 /api/config 自动填充 不用手填
node_id: "$node"
server: ""
udp: ""
pass: ""
virtual_ip: "auto"
mesh: true
verbose: false
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

# ---- 4. 注册开机自启计划任务 ----
$taskName = "moxian-p2p"
schtasks /Delete /TN $taskName /F 2>$null | Out-Null

$action = New-ScheduledTaskAction -Execute "$INSTALL_DIR\moxian-gui.exe" -WorkingDirectory $INSTALL_DIR
$trigger = New-ScheduledTaskTrigger -AtLogon -User $env:USERNAME
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan)

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null
Ok "开机自启任务已注册（登录后自动启动）"

# ---- 5. 立刻启动一次（不用等下次登录）----
Write-Host ""
Info "立刻启动 moxian-gui..."
# Stop 旧的（重复运行场景）
Get-Process -Name "moxian-gui" -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1
# Start-Process 已在管理员上下文，新进程继承管理员权限（TUN 需要）
Start-Process -FilePath "$INSTALL_DIR\moxian-gui.exe" -WorkingDirectory $INSTALL_DIR
Start-Sleep -Seconds 2

# 检查是否真的起来了
$proc = Get-Process -Name "moxian-gui" -ErrorAction SilentlyContinue
if ($proc) {
    Ok "moxian-gui 已启动 PID=$($proc.Id) 托盘图标应该出现"
} else {
    Warn "未检测到进程 可能已自动退出 看 $INSTALL_DIR\moxian.log"
}

# ---- 6. 完成 ----
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
Write-Host "v2 自动模式：" -ForegroundColor Cyan
Write-Host "  ✅ 托盘图标已出现"
Write-Host "  ✅ 已用凭据自动登录服务器"
Write-Host "  ✅ 已拉取 P2P 配置 + 启动连接"
Write-Host "  ✅ 下次开机/登录自动启动 + 自动连接"
Write-Host ""
Write-Host "管理：" -ForegroundColor Cyan
Write-Host "  托盘图标右键 看状态 / 查看日志 / 停止 / 退出"
Write-Host ""
Write-Host "升级: 重跑此脚本 (凭据保留)"
Write-Host "改密码: 编辑 $YAML 然后重启 GUI"
Write-Host ""
