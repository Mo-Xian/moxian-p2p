#!/usr/bin/env bash
# 一键构建全平台二进制 + 发 GitHub Release
#
# 用法:
#   bash scripts/release.sh v0.5.2 "本次改动说明"
#
# 多行 notes:
#   bash scripts/release.sh v0.5.2 "$(cat <<'EOF'
#   ### 改动
#   - feat: xxx
#   - fix: xxx
#   EOF
#   )"
#
# 从文件读:
#   bash scripts/release.sh v0.5.2 -F notes.md
#
# 可选标志:
#   --dirty       允许工作区有未提交修改（不推荐）
#   --no-apk      跳过 Android APK 构建
#   --draft       先创建草稿 Release 不直接发布
#   --dry-run     只编译 不 tag 不 release

set -euo pipefail

# ---- 工具路径（按需调整）----
GO_BIN="${GO_BIN:-/d/CP12064/install/go/bin}"
JDK_HOME="${JDK_HOME:-/c/Users/$USERNAME/.jdks/ms-17.0.15}"
ANDROID_SDK="${ANDROID_SDK:-/c/Users/$USERNAME/AppData/Local/Android/Sdk}"
GH_BIN="${GH_BIN:-/c/Program Files/GitHub CLI}"
GOPATH_WIN="${GOPATH_WIN:-/c/Users/$USERNAME/go}"

# ---- 解析参数 ----
VERSION=""
NOTES=""
ALLOW_DIRTY=0
SKIP_APK=0
DRAFT=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dirty)   ALLOW_DIRTY=1; shift ;;
    --no-apk)  SKIP_APK=1; shift ;;
    --draft)   DRAFT=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -F)        shift; NOTES="$(cat "$1")"; shift ;;
    -h|--help)
      grep '^#' "$0" | head -30
      exit 0
      ;;
    v*)
      if [ -z "$VERSION" ]; then VERSION="$1"; else NOTES="$1"; fi
      shift
      ;;
    *)
      if [ -z "$NOTES" ]; then NOTES="$1"; fi
      shift
      ;;
  esac
done

if [ -z "$VERSION" ]; then
  echo "用法: bash scripts/release.sh v0.5.2 [notes]"
  exit 1
fi
if [[ ! "$VERSION" =~ ^v[0-9] ]]; then
  echo "错误: version 要 v0.x.y 形式（当前: $VERSION）"
  exit 1
fi
if [ -z "$NOTES" ]; then
  NOTES="Release $VERSION"
fi

# ---- 环境 ----
echo "== 环境探测 =="
export PATH="$GO_BIN:$GH_BIN:$PATH"
export GOPATH="$GOPATH_WIN"
export GOMODCACHE="$GOPATH/pkg/mod"

command -v go >/dev/null || { echo "找不到 go 请设置 GO_BIN"; exit 1; }
command -v gh >/dev/null || { echo "找不到 gh 请设置 GH_BIN"; exit 1; }
go version
gh --version | head -1
gh auth status >/dev/null 2>&1 || { echo "gh 未登录 执行 gh auth login"; exit 1; }

# ---- 定位项目根 ----
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "项目: $ROOT"

# ---- 工作区 ----
if [ $ALLOW_DIRTY -eq 0 ] && [ -n "$(git status --porcelain)" ]; then
  echo "工作区有未提交修改（用 --dirty 强制）:"
  git status --short
  exit 1
fi

# ---- 清理旧产物 ----
mkdir -p bin
echo "== 清理旧二进制 =="
rm -f bin/moxian-client-* bin/moxian-server-* bin/moxian-client.exe bin/moxian-server.exe bin/moxian-p2p-debug.apk

# ---- 编译 Go 二进制 ----
echo "== 编译 Go 二进制 =="
go build -o bin/moxian-server.exe         ./cmd/server
go build -o bin/moxian-client.exe         ./cmd/client
GOOS=linux  GOARCH=amd64 go build -o bin/moxian-server-linux-amd64  ./cmd/server
GOOS=linux  GOARCH=amd64 go build -o bin/moxian-client-linux-amd64  ./cmd/client
GOOS=linux  GOARCH=arm64 go build -o bin/moxian-server-linux-arm64  ./cmd/server
GOOS=linux  GOARCH=arm64 go build -o bin/moxian-client-linux-arm64  ./cmd/client
GOOS=darwin GOARCH=amd64 go build -o bin/moxian-client-darwin-amd64 ./cmd/client

echo "Go 二进制产出:"
ls -la bin/moxian-server-* bin/moxian-client-* bin/moxian-*.exe 2>/dev/null | grep -v apk

# ---- 编译 Android APK ----
if [ $SKIP_APK -eq 0 ]; then
  echo "== 编译 Android APK =="
  mkdir -p android/app/src/main/jniLibs/arm64-v8a
  cp bin/moxian-client-linux-arm64 android/app/src/main/jniLibs/arm64-v8a/libmoxianclient.so

  if [ -d "$JDK_HOME" ]; then export JAVA_HOME="$JDK_HOME"; export PATH="$JAVA_HOME/bin:$PATH"; fi
  if [ -d "$ANDROID_SDK" ]; then export ANDROID_HOME="$ANDROID_SDK"; fi

  if [ -z "${JAVA_HOME:-}" ] || [ -z "${ANDROID_HOME:-}" ]; then
    echo "警告: JAVA_HOME / ANDROID_HOME 未设置 APK 可能编译失败"
  fi

  (cd android && ./gradlew assembleDebug --quiet)
  cp android/app/build/outputs/apk/debug/app-debug.apk bin/moxian-p2p-debug.apk
  echo "APK: $(ls -la bin/moxian-p2p-debug.apk)"
fi

# ---- 检查 wintun.dll ----
if [ ! -f bin/wintun.dll ]; then
  echo "警告: bin/wintun.dll 不存在 从 https://www.wintun.net/ 下载放入"
  echo "      （Release 里没有 dll 用户需自行下载）"
fi

# ---- Dry run 结束 ----
if [ $DRY_RUN -eq 1 ]; then
  echo "== dry-run 完成 产物在 bin/ 下 =="
  ls -la bin/
  exit 0
fi

# ---- git tag ----
echo "== 创建 tag $VERSION =="
if git rev-parse "$VERSION" >/dev/null 2>&1; then
  echo "tag 已存在 跳过创建"
else
  git tag -a "$VERSION" -m "Release $VERSION"
  git push origin "$VERSION"
fi

# ---- gh release ----
echo "== 发布 GitHub Release =="
ASSETS=(
  bin/moxian-server-linux-amd64
  bin/moxian-server-linux-arm64
  bin/moxian-server.exe
  bin/moxian-client-linux-amd64
  bin/moxian-client-linux-arm64
  bin/moxian-client-darwin-amd64
  bin/moxian-client.exe
)
[ -f bin/moxian-p2p-debug.apk ] && ASSETS+=(bin/moxian-p2p-debug.apk)
[ -f bin/wintun.dll ]           && ASSETS+=(bin/wintun.dll)

RELEASE_FLAGS=()
[ $DRAFT -eq 1 ] && RELEASE_FLAGS+=(--draft)

gh release create "$VERSION" \
  --title "$VERSION" \
  --notes "$NOTES" \
  "${RELEASE_FLAGS[@]}" \
  "${ASSETS[@]}"

echo ""
echo "== 完成 =="
echo "URL: https://github.com/Mo-Xian/moxian-p2p/releases/tag/$VERSION"
