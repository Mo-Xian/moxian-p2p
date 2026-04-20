# moxian-p2p Android 测试 APP

将 `bin/moxian-client-linux-arm64` 嵌入 APK，在 App 内以子进程形式运行，UI 输入配置 + 实时日志。

## 架构

```
┌──────────────────────────────────────┐
│ Android APP (Kotlin)                 │
│                                      │
│  ┌─────────────────────────────┐     │
│  │ Activity: 配置 UI / 启停    │     │
│  │ 读取 stdout → 日志 TextView  │     │
│  └──────────────┬──────────────┘     │
│                 │ ProcessBuilder     │
│  ┌──────────────▼──────────────┐     │
│  │ filesDir/moxian-client       │     │
│  │ (从 assets 释放的 Linux ARM  │     │
│  │  二进制 13MB 静态链接)       │     │
│  └──────────────────────────────┘    │
└──────────────────────────────────────┘
```

- `assets/moxian-client` = `../bin/moxian-client-linux-arm64` 副本
- 首次启动时 extract 到 `filesDir` 并 `setExecutable(true)`
- 命令行参数由表单拼出 `-forward`, `-pass` 等
- stdout/stderr 合流实时展示

## 构建

### 方式 A：Android Studio（推荐）

1. 安装 [Android Studio Hedgehog](https://developer.android.com/studio) 或更新
2. `File → Open` 选择 `moxian-p2p/android` 目录
3. 首次 sync 会下载 Gradle 8.2、Android SDK 34、Kotlin 1.9
4. 接一台开发者模式已开启的 Android 设备，点 **Run ▶**

### 方式 B：命令行

前置：已装 JDK 17 + Android SDK 34 + `ANDROID_HOME` 环境变量。

```bash
cd android
# 首次需要生成 gradle wrapper
gradle wrapper --gradle-version 8.2
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 打开 **moxian-p2p** APP
2. 填入 VPS 连接信息：
   - Node ID: `phone`
   - Server: `ws://139.224.1.83:7788/ws`
   - Server UDP: `139.224.1.83:7789`
   - Token: 服务器 token
   - Pass: 与对端一致的口令
3. 填入转发规则（一行一条）：
   ```
   127.0.0.1:18080=winpc=127.0.0.1:8000
   127.0.0.1:13389=winpc=127.0.0.1:3389
   ```
4. 点 **启动**，观察日志出现：
   ```
   [client] registered id=phone ...
   [client] nat_type=cone samples=5
   [punch] session=... direct via pong from ...
   [forward] session=... established mode=1
   ```
5. 使用其他 App（浏览器 / RDP client）访问 `127.0.0.1:18080` 等即穿透到对端

## 限制

- **手机 localhost 限制**：部分 Android 浏览器不允许访问 `127.0.0.1`（视为不安全）。可用：
  - 监听 `0.0.0.0:18080` → 手机其他 app 或同 WiFi 设备访问
  - Termux 里 `curl 127.0.0.1:18080` 永远能用
- **前台 Service**：当前是普通 Activity，切到后台可能被杀；如需长期运行需扩展为 `ForegroundService`
- **TUN 模式不可用**：Android 上 TUN 必须走 `VpnService` API，普通 ProcessBuilder 子进程无 `CAP_NET_ADMIN`
- **仅 arm64**：99% 新手机够用；若需 armv7，`app/build.gradle` 里 `abiFilters` 加上 `armeabi-v7a` 并在 `assets/` 里放对应二进制（重编 `GOARCH=arm`）

## 更新二进制

每次客户端代码变动，重新生成 Linux ARM64 二进制并覆盖 assets：

```bash
cd ..
GOOS=linux GOARCH=arm64 go build -o bin/moxian-client-linux-arm64 ./cmd/client
cp bin/moxian-client-linux-arm64 android/app/src/main/assets/moxian-client
# Android Studio 会自动识别 assets 变化 下次 Run 即带入
```

## 签名与发布

debug 构建用 Android Studio 自动生成的 debug keystore。正式发布 release APK：

```bash
keytool -genkey -v -keystore moxian-release.keystore -keyalg RSA -keysize 2048 \
  -validity 10000 -alias moxian
```

在 `app/build.gradle` 中添加 signingConfig，不展开。
