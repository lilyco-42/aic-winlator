#!/usr/bin/env bash
#
# 把定制版 APK 装到手机，并把游戏本体推到容器能看到的 D: 盘位置。
#
# 用法：
#   bash deploy.sh <apk路径> [设备序列号]
#
# 环境变量：
#   GAME_SRC   游戏本体目录（默认指向本机那份）
#   SERIAL     adb 设备序列号（同时连着模拟器和手机时必须给）
#
# 容器里的 D: 盘 = 手机的 /sdcard/Download（见 Container.DEFAULT_DRIVES），
# 所以游戏必须落在 /sdcard/Download/AliceInCradle/ 下，App 才能自动找到它。
#
set -euo pipefail

APK="${1:-}"
SERIAL="${2:-${SERIAL:-}}"

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "用法: bash deploy.sh <apk路径> [设备序列号]" >&2
    exit 1
fi

# Git Bash 会把 /sdcard/... 当成 Windows 路径转掉，凡是 adb shell 里带
# 绝对路径的命令都得关掉这个转换，否则会变成 'C:' Read-only file system。
export MSYS_NO_PATHCONV=1

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

# adb.exe 是原生 Windows 程序，看不懂 MSYS 的 /tmp/xxx 这类路径，
# 会把它们当成 Windows 相对路径。所以给 adb 的路径统一过一遍 cygpath。
winpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

GAME_SRC="${GAME_SRC:-D:/gal/AliceInCradle/AliceInCradle Win ver030/AliceInCradle_ver030}"
PKG="com.lilyco42.aicwinlator"
REMOTE_DIR="/sdcard/Download/AliceInCradle"

if [ ! -d "$GAME_SRC" ]; then
    echo "找不到游戏目录: $GAME_SRC" >&2
    exit 1
fi

APK_W="$(winpath "$APK")"
GAME_SRC_W="$(winpath "$GAME_SRC")"

echo "=== 设备 ==="
adb devices -l

if [ -z "$SERIAL" ]; then
    n=$(adb devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}')
    if [ "$n" -gt 1 ]; then
        echo
        echo "!! 检测到多台设备。请用第二个参数指定序列号，例如：" >&2
        adb devices | awk 'NR>1 && $2=="device"{printf "     bash deploy.sh %s %s\n", apk, $1}' apk="$APK" >&2
        exit 1
    fi
fi

echo
echo "=== 安装 APK ==="
# 每次 CI 构建用的都是 runner 上临时生成的 debug keystore，签名不固定，
# 所以覆盖安装会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。这里自动降级成
# 「卸载 → 安装」。代价是 /data/data 被清空（rootfs 要重新解一遍），
# 换来的是不用手工处理，也不会出现「装了个旧的还以为是新的」。
#
# 输出先落盘再判断：直接 `adb install | grep -q` 会因为 grep 提前退出
# 触发 SIGPIPE，把安装结果整个吞掉，看起来像什么都没发生。
INSTALL_LOG="$(mktemp)"
if "${ADB[@]}" install -r -d "$APK_W" > "$INSTALL_LOG" 2>&1; then
    cat "$INSTALL_LOG"
else
    cat "$INSTALL_LOG" >&2
    if grep -q "UPDATE_INCOMPATIBLE\|signatures do not match" "$INSTALL_LOG"; then
        echo ">> 签名不一致（CI 每次构建的 debug 签名都不同），改为卸载后重装"
        echo ">> 注意：/data/data 会被清空，首次启动要重新解 rootfs（约 1 分钟）"
        "${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
        "${ADB[@]}" install -d "$APK_W"
    else
        echo "安装失败，见上面的输出。" >&2
        rm -f "$INSTALL_LOG"
        exit 1
    fi
fi
rm -f "$INSTALL_LOG"

echo
echo "=== 准备远端目录 ==="
"${ADB[@]}" shell mkdir -p "$REMOTE_DIR"

# 注意：故意不推 BepInEx / winhttp.dll / doorstop_config.ini。
# 那三个是 BepInEx 的加载器（winhttp.dll 是 doorstop 的代理 DLL）。
# 实测过 AliceInCradle.exe 只导入 UnityPlayer.dll + KERNEL32.dll，而
# UnityPlayer.dll 导入 WINHTTP.dll —— Wine 有内建 winhttp，不推也能解析，
# 所以不推的代价只是「没有 BepInEx 注入」，游戏照跑干净原版。
# 好处是把「游戏跑不起来」和「mod 跑不起来」彻底分开。
echo
echo "=== 推送游戏本体（不含 BepInEx） ==="
"${ADB[@]}" push "$GAME_SRC_W/AliceInCradle.exe"       "$REMOTE_DIR/"
"${ADB[@]}" push "$GAME_SRC_W/UnityPlayer.dll"         "$REMOTE_DIR/"
"${ADB[@]}" push "$GAME_SRC_W/UnityCrashHandler64.exe" "$REMOTE_DIR/"
"${ADB[@]}" push "$GAME_SRC_W/AliceInCradle_Data"      "$REMOTE_DIR/AliceInCradle_Data"
"${ADB[@]}" push "$GAME_SRC_W/MonoBleedingEdge"        "$REMOTE_DIR/MonoBleedingEdge"

echo
echo "=== 核对 ==="
"${ADB[@]}" shell ls -la "$REMOTE_DIR"
"${ADB[@]}" shell du -sh "$REMOTE_DIR"

# 光看文件在不够 —— 得确认 App 真的读得到（外部存储权限）。
if "${ADB[@]}" shell "test -r $REMOTE_DIR/AliceInCradle.exe" 2>/dev/null; then
    echo ">> 游戏本体可读 ✅"
else
    echo ">> !! 游戏本体读不到 —— 检查手机是否授予了「文件和媒体」权限" >&2
fi

echo
echo "======================================================"
echo "推送完成。首次启动的流程："
echo "  1. 点开「Alice in Cradle」图标"
echo "  2. 弹「允许访问照片、视频、音乐和文件」→ 必须点允许"
echo "  3. 等它解 rootfs（约 1 分钟，一次性），之后自动进游戏"
echo
echo "如果游戏没起来：再点一次图标不会反复重启（有 30 秒冷却），"
echo "会停在 Winlator 主界面 —— 在那里可以改容器参数、换图形驱动重试。"
echo "======================================================"
