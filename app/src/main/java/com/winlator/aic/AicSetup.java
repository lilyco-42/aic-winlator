package com.winlator.aic;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;

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

    /** 内置触屏布局 id —— 对应 assets/inputcontrols/profiles/controls-5.icp。 */
    public static final String CONTROLS_PROFILE_ID = "5";

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

    // ------------------------------------------------------------------ 入口

    /**
     * rootfs 装完（或已就绪）之后调用。必须在主线程调用 ——
     * {@link ContainerManager#createContainerAsync} 内部要 new Handler()，没有 Looper 会抛。
     */
    public static void onRootFSReady(final MainActivity activity) {
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
     * {@link com.winlator.core.StringUtils#unescapeDOSPath} 会连续两轮把 {@code \X} 折成 {@code X}，
     * 所以文件里必须写 {@code D:\\\\AliceInCradle\\\\AliceInCradle.exe} 才能还原成
     * {@code D:\AliceInCradle\AliceInCradle.exe}。写一个或两个反斜杠都会被吃光，
     * 变成一个没有分隔符的怪字符串，然后 getDirname() 直接抛异常。
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
