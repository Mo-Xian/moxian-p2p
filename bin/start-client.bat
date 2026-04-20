@echo off
chcp 65001 >nul
cd /d "%~dp0"

REM 检查管理员权限（TUN 模式需要）
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo.
    echo [!] 需要以管理员身份运行
    echo     右键本文件 ^> 以管理员身份运行
    echo.
    pause
    exit /b
)

REM 检查依赖
if not exist wintun.dll (
    echo [!] 缺少 wintun.dll
    echo     从 https://www.wintun.net/ 下载 amd64 版本放到本目录
    pause
    exit /b
)
if not exist client.yaml (
    echo [!] 缺少 client.yaml
    echo     复制 ..\examples\client.yaml 到本目录 编辑 node_id / virtual_ip 等
    pause
    exit /b
)

echo === moxian-p2p client ===
moxian-client.exe -config client.yaml
pause
