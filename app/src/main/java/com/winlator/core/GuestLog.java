package com.winlator.core;

import java.io.File;
import java.io.IOException;

/**
 * 把 Wine / box64 的 stdout+stderr 落到一个固定文件里。
 *
 * 背景：ProcessHelper 原来在 debugCallbacks 为空时把输出直接扔 /dev/null，
 * 结果排查音频、挂载、注册表这类问题时拿不到任何 guest 侧报错 —— 只能靠猜。
 *
 * 现在改成追加写到 <rootfs>/tmp/wine_debug.log。
 * 用 append 模式（而不是覆盖）是为了保留多次启动之间的历史，方便对比。
 * 日志不设大小上限由调用方决定 —— 这里只做「尽量小、尽量稳」：
 *   - 路径固定，方便 adb 直接 pull
 *   - 写不进去就静默返回 null（回退 /dev/null），绝不因为日志把游戏搞崩
 */
public final class GuestLog {
    private static final String RELATIVE_PATH = "tmp/wine_debug.log";

    private GuestLog() {}

    /**
     * @param workingDir guest 进程的工作目录（= rootfs 根目录）
     * @return 可写的日志文件；不可用时返回 null
     */
    public static File getLogFile(File workingDir) {
        if (workingDir == null) return null;
        try {
            File tmpDir = new File(workingDir, "tmp");
            if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) return null;

            File logFile = new File(workingDir, RELATIVE_PATH);
            if (!logFile.exists() && !logFile.createNewFile()) return null;
            if (!logFile.canWrite()) return null;
            return logFile;
        }
        catch (IOException e) {
            return null;
        }
    }
}
