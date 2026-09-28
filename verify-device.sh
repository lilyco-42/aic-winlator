#!/usr/bin/env bash
#
# 真机验收：把「一加 15 上到底能不能跑」这件事，变成一条命令 + 一堆可核对的证据。
#
# 用法：
#   bash verify-device.sh <apk路径> [设备序列号]
#
# 做四件事：
#   1. 前置体检：确认是 arm64 真机（不是 x86_64 模拟器）、有 Vulkan、空间够
#   2. 部署（复用 deploy.sh）
#   3. 冷启动并抓证据：logcat 全量、多张截图、顶部 Activity、崩溃检测
#   4. 汇总 + 把所有证据落到 out/ 目录
#
# 为什么要先体检 ABI：模拟器是 x86_64 + libndk_translation，
# 而 Box64 是 x86_64→ARM64 翻译器 —— 在 x86_64 上跑等于套两层翻译，
# 得到的失败信号全是模拟器特有的，没有参考价值。宁可先拦住。
#
set -uo pipefail

# 和 deploy.sh 同因：Git Bash 会把 /data、/sdcard 这类绝对路径当成 Windows 路径
# 转成 C:/Users/.../data，凡是 adb shell 里带绝对路径的命令都会被坑。
export MSYS_NO_PATHCONV=1

APK="${1:-}"
SERIAL="${2:-${SERIAL:-}}"
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL") || ADB=(adb)

OUT_DIR="${OUT_DIR:-out}"
PKG="com.lilyco42.aicwinlator"

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "用法: bash verify-device.sh <apk路径> [设备序列号]" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
say() { printf '\n=== %s ===\n' "$*"; }

# ---------------------------------------------------------------- 1. 前置体检
say "1/4 前置体检"

"${ADB[@]}" wait-for-device

MODEL=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')
BRAND=$("${ADB[@]}" shell getprop ro.product.brand | tr -d '\r')
SDK=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
REL=$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')
ABILIST=$("${ADB[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')
ABI64=$("${ADB[@]}" shell getprop ro.product.cpu.abilist64 | tr -d '\r')

echo "机型      : $BRAND $MODEL"
echo "Android   : $REL (API $SDK)"
echo "ABI 列表  : $ABILIST"
echo "64 位 ABI : $ABI64"

FATAL=0

# 真机 = 至少有一个 64 位 ARM ABI，且不是模拟器
if ! printf '%s' "$ABILIST" | grep -q "arm64"; then
    echo
    echo "!! 这台设备没有 arm64 ABI —— 十有八九是 x86_64 模拟器。" >&2
    echo "   Box64 是 x86_64→ARM64 翻译器，在 x86_64 上跑等于套两层翻译，" >&2
    echo "   得到的失败信号没有参考价值。请接上真机（一加 15 / PLK110）。" >&2
    FATAL=1
fi

if "${ADB[@]}" shell getprop ro.kernel.qemu 2>/dev/null | grep -q "^1"; then
    echo
    echo "!! ro.kernel.qemu=1 —— 这是模拟器。" >&2
    FATAL=1
fi

# Vulkan：DXVK 的硬前提。拿不到就只警告，不拦（探测手段在真机上不一定可用）
say "Vulkan 探测"
VK_JSON="$OUT_DIR/vkjson.txt"
if "${ADB[@]}" shell cmd gpu vkjson > "$VK_JSON" 2>/dev/null && [ -s "$VK_JSON" ]; then
    VK_DRIVER=$(grep -m1 '"driverName"' "$VK_JSON" | sed 's/.*: *"//;s/".*//')
    VK_API=$(grep -m1 '"apiVersion"' "$VK_JSON" | sed 's/.*: *//;s/,.*//')
    echo "Vulkan 驱动 : ${VK_DRIVER:-?}"
    echo "Vulkan 版本 : ${VK_API:-?}"
    [ -z "$VK_DRIVER" ] && echo ">> 没解析到驱动名，看 $VK_JSON"
else
    echo ">> cmd gpu vkjson 不可用，改用 SurfaceFlinger 兜底："
    "${ADB[@]}" shell dumpsys SurfaceFlinger 2>/dev/null \
        | grep -iE "GLES|Vulkan|GPU|driver" | head -8 | tee "$OUT_DIR/sf_gpu.txt"
fi

say "存储空间"
"${ADB[@]}" shell df -h /data 2>/dev/null | tr -d '\r'
"${ADB[@]}" shell df -h /sdcard 2>/dev/null | tr -d '\r'

if [ "$FATAL" = "1" ]; then
    if [ "${FORCE:-0}" = "1" ]; then
        echo
        echo ">> FORCE=1：无视上面的问题继续跑。"
        echo ">> 注意这只用来验证本脚本自己能不能正常收集证据，"
        echo ">> 在 x86_64 上得到的游戏能不能跑 的结论是无效的。"
    else
        say "体检不通过，已中止"
        echo "接上真机后重跑：bash verify-device.sh $APK <序列号>"
        echo "（确实想在模拟器上验证本脚本自身，用 FORCE=1）"
        exit 2
    fi
fi
echo ">> 体检通过"

# ---------------------------------------------------------------- 2. 部署
say "2/4 部署（APK + 游戏本体）"
if [ -n "$SERIAL" ]; then
    bash "$(dirname "$0")/deploy.sh" "$APK" "$SERIAL"
else
    bash "$(dirname "$0")/deploy.sh" "$APK"
fi
[ $? -ne 0 ] && { echo "部署失败，中止" >&2; exit 3; }

# ---------------------------------------------------------------- 3. 冷启动 + 抓证据
say "3/4 冷启动并抓证据"

"${ADB[@]}" shell am force-stop "$PKG"
sleep 2
"${ADB[@]}" logcat -c 2>/dev/null
"${ADB[@]}" logcat -v threadtime > "$OUT_DIR/logcat.txt" 2>/dev/null &
LOGCAT_PID=$!

START_MS=$(date +%s%3N)
"${ADB[@]}" shell am start -n "$PKG/com.winlator.MainActivity" >/dev/null 2>&1

# 采样：每 10 秒记一次「顶部 Activity + 进程是否还在 + 时间戳」
SAMPLE="$OUT_DIR/samples.tsv"
: > "$SAMPLE"
printf 'sec\tpid\ttopActivity\taic_last_launch\n' >> "$SAMPLE"

FIRST_LAUNCH=""
for i in $(seq 1 12); do
    sleep 10
    PID=$("${ADB[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
    TOP=$("${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
          | grep -m1 topResumedActivity | sed 's/.*u0 //;s/ .*//' | tr -d '\r')
    TS=$("${ADB[@]}" shell "run-as $PKG cat /data/data/$PKG/shared_prefs/${PKG}_preferences.xml" 2>/dev/null \
          | grep -o 'aic_last_launch_time" value="[0-9]*' | grep -o '[0-9]*$')
    printf '%s\t%s\t%s\t%s\n' "$((i*10))" "${PID:-DEAD}" "${TOP:-?}" "${TS:-}" >> "$SAMPLE"
    [ -n "$TS" ] && [ -z "$FIRST_LAUNCH" ] && FIRST_LAUNCH="$TS"
    # 到点就各存一张图
    case $i in 2|4|6|12) "${ADB[@]}" exec-out screencap -p > "$OUT_DIR/shot_$((i*10))s.png" 2>/dev/null ;;
    esac
    printf '[%3ss] pid=%-8s top=%-45s launch_ts=%s\n' "$((i*10))" "${PID:-DEAD}" "${TOP:-?}" "${TS:-<none>}"
done

kill "$LOGCAT_PID" 2>/dev/null
wait "$LOGCAT_PID" 2>/dev/null

# ---------------------------------------------------------------- 4. 汇总
say "4/4 汇总"

{
    echo "机型: $BRAND $MODEL / Android $REL (API $SDK)"
    echo "ABI : $ABILIST"
    echo
    echo "--- 崩溃 / 异常 ---"
    grep -iE "FATAL EXCEPTION|AndroidRuntime.*$PKG|SIGSEGV|tombstone" "$OUT_DIR/logcat.txt" | head -20 || echo "(无)"
    echo
    echo "--- 关键错误（wine/box64/dxvk/vulkan/rootfs）---"
    grep -iE "err:|failed|cannot|unable|not found" "$OUT_DIR/logcat.txt" \
        | grep -iE "wine|box64|dxvk|vulkan|d3d|rootfs|imagefs|winhandler|xserver|gladio|vortek|turnip" \
        | head -40 || echo "(无)"
    echo
    echo "--- 首次拉起游戏的时间戳 ---"
    echo "${FIRST_LAUNCH:-<没写进去，说明没走到 launchGame>}"
} > "$OUT_DIR/summary.txt"

cat "$OUT_DIR/summary.txt"

echo
echo "证据都在 $OUT_DIR/："
ls -la "$OUT_DIR" | sed 's/^/  /'
echo
echo "下一步人工判断："
echo "  - shot_*.png 里有没有出标题画面"
echo "  - samples.tsv 里 top 是否变成 .XServerDisplayActivity"
echo "  - logcat.txt 里 DXVK / vulkan / box64 有没有报错"
echo "  - 有没有声音（这个脚本抓不到，得人听）"
