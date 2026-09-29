package com.winlator.aic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/**
 * AIC 定制版的「点图标 → 直接进游戏」引导。
 *
 * <p>上游 Winlator 的流程是「开 App → 建容器 → 挑 exe → 调一堆参数 → 建快捷方式 → 点快捷方式」。
 * 这里把它压成一步：装完 rootfs 之后自动建一个调好的容器、写一个快捷方式，然后直接拉起游戏。
 *
 * <p><b>为什么需要自己写 .desktop 快捷方式</b>：上游只在「有快捷方式」时才加载触屏布局
 * （见 XServerDisplayActivity），而「直接 exec_path 启动」这条路径拿不到 shortcut。
 * 我们两边都做了：
 * <ul>
 *   <li>容器级 {@code controlsProfile} 兜底（改了 XServerDisplayActivity），保证按键一定有；</li>
 *   <li>再写一个 .desktop，让 Winlator 自己的「快捷方式」页里也能看到并重进游戏。</li>
 * </ul>
 */
public final class AicSetup {

    /** 容器名。也用来判断「容器是不是我们建的」。 */
    public static final String CONTAINER_NAME = "Alice in Cradle";

    /**
     * 游戏放哪儿：手机的 Download 目录。
     *
     * <p>不是随便选的 —— 容器里 D: 盘就映射到 {@link AppUtils#DIRECTORY_DOWNLOADS}
     * （见 {@link Container#DEFAULT_DRIVES}），所以放到这里，容器里就能直接用
     * {@code D:\AliceInCradle\AliceInCradle.exe} 访问，不用把 670MB 拷进 App 私有目录。
     */
    public static final String GAME_FOLDER = "AliceInCradle";
    public static final String GAME_EXE = "AliceInCradle.exe";

    /**
     * 默认触屏布局 id —— 对应 assets/inputcontrols/profiles/controls-5.icp（纯手柄映射）。
     *
     * <p>为什么用手柄而不是键盘：AIC 的输入层是 Unity Input System 1.14.2，游戏存档目录里的
     * {@code config.cfg} 存着一张**每个动作同时绑键盘和手柄**的表，手柄侧覆盖完整
     * （{@code buttonSouth/East/North/West}、{@code leftStick}、肩键/扳机、
     * {@code rightStick} 选魔法方向、震动）。用摇杆做移动比八向 D_PAD 精度高得多，
     * 而且天然绕开键盘的键位布局映射问题。
     *
     * <p>Winlator 侧也齐：{@code Binding} 枚举里 {@code GAMEPAD_BUTTON_A..R2} 连续排列，
     * ordinal 差正好 0..11，与 {@code ExternalController.IDX_BUTTON_*} 对齐；
     * {@code ControlsProfile.load()} 发现元素全是手柄绑定时会自动把 {@code virtualGamepad}
     * 置 true，不需要额外开关。
     */
    public static final String CONTROLS_PROFILE_ID = "5";

    /**
     * 键盘布局 id —— controls-6.icp，作为手柄路线失效时的兜底。
     *
     * <p>什么时候会用到：Wine 的 {@code windows.gaming.input} 实现不完整时，
     * 虚拟手柄事件可能送不进去。此时把容器 / 快捷方式的 {@code controlsProfile}
     * 改成这个 id 即可切回纯键盘映射（键位同样按 config.cfg 的权威表来）。
     */
    public static final String CONTROLS_PROFILE_ID_KEYBOARD = "6";

    /** 上一次真正拉起游戏的时间戳（毫秒），用来做冷却防抖。 */
    private static final String PREF_LAST_LAUNCH = "aic_last_launch_time";

    /**
     * 自动进游戏之后的冷却时间。
     *
     * <p>为什么需要：自动启动是「无条件」的 —— 只要从桌面图标冷启动就会进游戏。
     * 如果游戏起不来（黑屏闪退、DXVK 初始化失败……），用户退回主界面，
     * 下一次冷启动又会立刻再自动进一次，形成**崩溃死循环**，而且他没有任何逃生通道。
     * 冷却期内不再自动进，直接把上游 Winlator 的完整界面给他 —— 那里能改容器参数、
     * 看日志、换 Box64 预设。这是「不要让人点注定失败的按钮」那条规矩的兜底。
     */
    private static final long LAUNCH_COOLDOWN_MS = 30_000L;

    private AicSetup() {}

    // ------------------------------------------------------------------ 路径

    public static File getGameDir() {
        return new File(AppUtils.DIRECTORY_DOWNLOADS, GAME_FOLDER);
    }

    public static File getGameExe() {
        return new File(getGameDir(), GAME_EXE);
    }

    public static boolean isGamePresent() {
        return getGameExe().isFile();
    }

    // ------------------------------------------------------------------ 冷却防抖

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** 上一次自动进游戏之后，是否还在冷却期内。 */
    public static boolean isInLaunchCooldown(Context context) {
        long last = prefs(context).getLong(PREF_LAST_LAUNCH, 0L);
        return last > 0L && System.currentTimeMillis() - last < LAUNCH_COOLDOWN_MS;
    }

    /**
     * 记一次「真的要把游戏拉起来了」。
     *
     * <p>刻意只在 {@link #launchGame} 之前调 —— 不能挪到更早的地方。
     * 如果游戏本体缺失（只弹了个提示、根本没启动），就不该记，
     * 否则用户刚把游戏拷进去、马上重开，会被冷却挡在门外，还得等 30 秒。
     */
    private static void recordLaunch(Context context) {
        prefs(context).edit().putLong(PREF_LAST_LAUNCH, System.currentTimeMillis()).apply();
    }

    // ------------------------------------------------------------------ 入口

    /**
     * rootfs 装完（或已就绪）之后调用。任意线程都可以调 —— 内部会自己切到主线程。
     *
     * <p>为什么必须切：{@link RootFSInstaller} 的完成回调是在
     * {@code Executors.newSingleThreadExecutor()} 的线程上跑的，而
     * {@link ContainerManager#createContainerAsync} 第一件事就是 {@code new Handler()}，
     * 在没调过 {@code Looper.prepare()} 的线程上会直接抛
     * {@code RuntimeException: Can't create handler inside thread ...}。
     * （这个坑是实测踩出来的：第一次在模拟器上跑就崩在这里。）
     */
    public static void onRootFSReady(final MainActivity activity) {
        new Handler(Looper.getMainLooper()).post(() -> onRootFSReadyOnMainThread(activity));
    }

    private static void onRootFSReadyOnMainThread(final MainActivity activity) {
        ContainerManager manager = new ContainerManager(activity);

        Container container = findContainer(manager);
        if (container != null) {
            onContainerReady(activity, container);
            return;
        }

        JSONObject data;
        try {
            data = buildContainerData();
        }
        catch (JSONException e) {
            showToast(activity, R.string.aic_container_failed);
            return;
        }

        manager.createContainerAsync(data, created -> onContainerReady(activity, created));
    }

    private static Container findContainer(ContainerManager manager) {
        for (Container container : manager.getContainers()) {
            if (CONTAINER_NAME.equals(container.getName())) return container;
        }
        return null;
    }

    // ------------------------------------------------------------------ 建容器

    /**
     * 容器参数全部来自 Winlator 自己的 README + box64 上游文档，不是拍脑袋：
     *
     * <ul>
     *   <li>{@code box64Preset = STABILITY} —— README：「Unity 引擎的游戏把 Box64 预设改成 Stability」
     *       （上游默认是 PERFORMANCE，社区教程普遍反映 Unity 游戏在性能预设下会跑挂）</li>
     *   <li>{@code dxwrapper = DXVK} —— 把游戏的 D3D11 着色器在运行时翻成 Vulkan，
     *       这是「Windows 构建的 DXBC 字节码没法在 Android 上直接用」的唯一解</li>
     *   <li>{@code envVars} 里带 {@code BOX64_UNITYPLAYER=1} —— box64 的 Unity 检测开关</li>
     *   <li>{@code controlsProfile} —— 容器级触屏布局（见 XServerDisplayActivity 的改动）</li>
     * </ul>
     */
    private static JSONObject buildContainerData() throws JSONException {
        JSONObject extraData = new JSONObject();
        extraData.put("controlsProfile", CONTROLS_PROFILE_ID);

        JSONObject data = new JSONObject();
        data.put("name", CONTAINER_NAME);
        data.put("screenSize", "1280x720");
        data.put("box64Preset", Box64Preset.STABILITY);
        data.put("dxwrapper", DXWrappers.DXVK);
        data.put("envVars", Container.DEFAULT_ENV_VARS);
        data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
        data.put("drives", Container.DEFAULT_DRIVES);
        data.put("extraData", extraData);
        return data;
    }

    // ------------------------------------------------------------------ 启动

    private static void onContainerReady(MainActivity activity, Container container) {
        if (container == null) {
            showToast(activity, R.string.aic_container_failed);
            return;
        }

        if (!isGamePresent()) {
            // 不自动启动 —— 启动也只会黑屏，不如直接说清楚要放哪
            Toast.makeText(activity,
                activity.getString(R.string.aic_game_missing, getGameDir().getAbsolutePath()),
                Toast.LENGTH_LONG).show();
            return;
        }

        writeShortcut(container);
        launchGame(activity, container);
    }

    private static void launchGame(MainActivity activity, Container container) {
        recordLaunch(activity);
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        // 必须是 unix 路径：XServerDisplayActivity 会拿它过 WineUtils.unixToDOSPath，
        // 而 D: 盘就是 DIRECTORY_DOWNLOADS，所以这里直接给绝对路径即可。
        intent.putExtra("exec_path", getGameExe().getAbsolutePath());
        activity.startActivity(intent);
    }

    // ------------------------------------------------------------------ 快捷方式

    /**
     * 在容器的桌面目录写一个 .desktop，让 Winlator 的「快捷方式」页里也能看到这个游戏。
     *
     * <p><b>Exec 那一行为什么反斜杠要写四个</b>：这是实测出来的，不是猜的。
     * {@link com.winlator.core.StringUtils#unescapeDOSPath} 里串了三次替换：
     * 先把 {@code \X} 折成 {@code X} 连做两轮，最后再把残留的 {@code \\} 折成 {@code \}。
     * 所以文件里必须写 {@code D:\\\\AliceInCradle\\\\AliceInCradle.exe} 才能还原成
     * {@code D:\AliceInCradle\AliceInCradle.exe}。
     * 写一个会被吃光（{@code D:AliceInCradle...}），写两个也一样被两轮吃完；
     * 结果是没有任何分隔符的怪字符串，{@code Shortcut} 之后取目录时直接抛异常。
     *
     * <p>快捷方式只是「顺手」—— 主路径是自动启动，所以这里失败也不影响进游戏。
     */
    private static void writeShortcut(Container container) {
        try {
            File desktopDir = new File(container.getUserDir(), "Desktop");
            if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return;

            File file = new File(desktopDir, CONTAINER_NAME + ".desktop");

            StringBuilder sb = new StringBuilder();
            sb.append("[Desktop Entry]\n");
            sb.append("Name=").append(CONTAINER_NAME).append("\n");
            sb.append("Exec=wine D:\\\\\\\\").append(GAME_FOLDER)
              .append("\\\\\\\\").append(GAME_EXE).append("\n");
            sb.append("Type=Application\n");
            sb.append("\n[Extra Data]\n");
            sb.append("box64Preset=").append(Box64Preset.STABILITY).append("\n");
            sb.append("dxwrapper=").append(DXWrappers.DXVK).append("\n");
            sb.append("controlsProfile=").append(CONTROLS_PROFILE_ID).append("\n");
            sb.append("execArgs=-force-gfx-direct\n");

            FileUtils.writeString(file, sb.toString());
        }
        catch (Exception ignored) {
            // 快捷方式是锦上添花，失败就失败
        }
    }

    // ------------------------------------------------------------------ 工具

    private static void showToast(Context context, int resId) {
        Toast.makeText(context, resId, Toast.LENGTH_LONG).show();
    }

    /** 供调试用：把当前容器列表打成一行。 */
    public static String describeContainers(Context context) {
        ArrayList<Container> containers = new ContainerManager(context).getContainers();
        StringBuilder sb = new StringBuilder();
        for (Container container : containers) {
            sb.append(container.id).append(':').append(container.getName()).append(' ');
        }
        return sb.toString();
    }
}
