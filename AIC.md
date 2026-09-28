# AIC 定制版说明

本仓库是 [brunodev85/winlator-app](https://github.com/brunodev85/winlator-app) 的 fork，
被改造成**单图标、单游戏**的形态：装好之后点图标直接进 Alice in Cradle，不需要用户
手动建容器、调参数、配按键。

上游 Winlator 是 LGPL-2.1，本 fork 同样保持 LGPL-2.1（见 `LICENSE`）。

---

## 一、为什么是「兼容层」而不是「原生移植」

原生 Android 移植这条路已经用四条互相独立的证据排除掉了：

| 证据 | 内容 |
|---|---|
| **E1** 许可无匿名通道 | `license.unity3d.com/manual` 的激活接口是 `/genesis/activation/activate-nul`（NUL = Named User License），空 body、绑定 SSO 会话；JS 里不存在 `anonymous` / `guest` 通道 |
| **E2** `-force-free` 实测无效 | `Unity.exe -batchmode -nographics -quit -force-free` → `IsValid: false` / `No valid Unity Editor license found` / exit 1 |
| **E3** 本机运行时缺 arm64 | Unity 2022.3.62f2 的 `PlaybackEngines/AndroidPlayer/Variations/mono/` 只有 `armeabi-v7a`，全盘无 arm64 的 `libmonobdwgc-2.0.so`；而目标机（骁龙 8 Elite Gen 5）是纯 64 位 |
| **E4** 着色器字节码不通用 | Windows 构建里的 `Shader` 资产是 **D3D11 DXBC**，Android 需要 GLES 的 GLSL ES / Vulkan 的 SPIR-V。着色器编译是 Unity Editor 构建管线的职责，反编译补不出来 |

**E4 是最关键的一条**：它说明「把 Windows 构建手工重打包成 Android APK」在原理上就断了。
而 **DXVK 恰好在运行时把 DXBC 翻译成 Vulkan** —— 这正是兼容层能成立的原因。

社区先例（不是我们第一个想到的）：

- B 站 `BV19CRZYcEoT`（2025-04）：《[AliceInCradle]手机骁龙处理器(部分)，如何运行摇篮中的爱丽丝的原声教程 (winlator模拟器)》
- 百度贴吧 8886722370 / 9237124135：一致结论「没有移动版，但可以用 winlator 模拟器玩」

---

## 二、参数配方，每一条的出处

**没有一条是拍脑袋调的。**

| 项 | 值 | 出处 |
|---|---|---|
| Box64 预设 | `STABILITY` | Winlator 官方 README：*"To improve stability in games that uses **Unity Engine**, try changing the Box64 preset to `Stability`"* |
| 启动参数 | `-force-gfx-direct` | 同上，README 原文 |
| DX 转换层 | `DXVK` | 见上文 E4 |
| `BOX64_UNITYPLAYER` | `1` | box64 上游 `docs/USAGE.md`：*"Detect UnityPlayer and apply conservative settings. 1: … **[Default]**"* —— 默认就是 1，这里显式写上防被别的预设覆盖 |
| 图形驱动 | 保持上游默认（`vortek,gladio`） | Winlator 11.x 自己的 Vulkan/GL 实现，官方默认值 |

> 上游 `Box64Preset.DEFAULT` 是 `PERFORMANCE`，社区教程普遍反映 Unity 游戏在这个预设下
> 跑一会儿会红感叹号报错。所以本 fork 把默认改成了 `STABILITY`。

---

## 三、相对上游改了哪些地方

| 文件 | 改动 |
|---|---|
| `container/Container.java` | `DEFAULT_ENV_VARS` 加 `BOX64_UNITYPLAYER=1`；`box64Preset` 默认值 `DEFAULT` → `STABILITY` |
| `core/Win32AppWorkarounds.java` | 新增 `aliceincradle.exe` 分支，注入 `EXTRA_EXEC_ARGS=-force-gfx-direct` |
| `aic/AicSetup.java`（新增） | rootfs 装完 → 建调好的容器 → 写快捷方式 → 直接拉起游戏 |
| `xenvironment/RootFSInstaller.java` | 加完成回调重载（原签名保留，传 `null` 等价上游行为） |
| `MainActivity.java` | 只在「桌面图标冷启动」时自动进游戏；加 30 秒冷却防「起不来→退回→又自动进」的死循环 |
| `XServerDisplayActivity.java` | 触屏布局补上「容器级 `controlsProfile`」回退 |
| `assets/inputcontrols/profiles/controls-5.icp`（新增） | AIC 专用触屏布局 |
| `app/build.gradle` + 3 处硬编码 | `applicationId` 改成 `com.lilyco42.aicwinlator` |
| `res/mipmap-*` + `res/values/ic_launcher_background.xml` | 换图标 |

### 四个容易踩的坑（都是实测踩出来的）

1. **自动启动的判据用 `savedInstanceState == null && isTaskRoot()`**，
   不要用「第一次运行」标志位。因为它天然排除了两个误触发场景：
   从游戏退出后 MainActivity 走 `onResume` 复用（`onCreate` 不会再跑）；
   从游戏里点「编辑触屏布局」拉起的 MainActivity 是新实例但 `isTaskRoot()` 为 false。

2. **`AicSetup.onRootFSReady` 必须切回主线程**。
   `RootFSInstaller` 的完成回调跑在 `Executors.newSingleThreadExecutor()` 的线程上，
   而 `ContainerManager.createContainerAsync` 第一件事就是 `new Handler()` ——
   在没调过 `Looper.prepare()` 的线程上直接抛
   `RuntimeException: Can't create handler inside thread ...`。

3. **`.desktop` 里 `Exec=` 的反斜杠要写四个**。
   `StringUtils.unescapeDOSPath` 里串了**三次**替换：先把 `\X` 折成 `X` 连做两轮，
   最后再把残留的 `\\` 折成 `\`。所以只有
   `D:\\\\AliceInCradle\\\\AliceInCradle.exe` 才能还原成
   `D:\AliceInCradle\AliceInCradle.exe`。写一个会被吃光，写两个也会被两轮吃完，
   结果是没有任何分隔符的怪字符串，然后 `Shortcut` 取目录时抛
   `StringIndexOutOfBoundsException`。

4. **自动启动必须留逃生通道**。
   无条件自动进游戏 = 游戏一旦起不来，用户退回主界面、下次冷启动又立刻再自动进一次，
   **崩溃死循环，而且他没有任何办法改配置**。所以加了
   `AIC_AUTO_LAUNCH_COOLDOWN_MS = 30s`：刚自动进过就再冷启动，说明那次多半没起来，
   这次停在主界面，把上游 Winlator 的完整 UI 让出来（那里能改容器参数、换 Box64 预设、看日志）。
   时间戳打在**真正拉起游戏之前**，不是 `onCreate` 里 —— 首次运行装 rootfs 要一分钟，
   打在 `onCreate` 会让冷却期提前过期。

---

## 三·五、游戏本体的依赖，实测过了

用自己写的极小 PE 导入表解析器（`tools/peimports.py`，本机没有 `pefile`）把三个 PE 全拆了一遍。
**结论：游戏不需要任何 MSVC 运行时**，所有导入都能落到 Wine 内建：

| PE | 架构 | 导入 |
|---|---|---|
| `AliceInCradle.exe` | x86_64 | `UnityPlayer.dll`、`KERNEL32.dll` |
| `UnityPlayer.dll` | x86_64 | KERNEL32 / USER32 / VERSION / ole32 / SHLWAPI / SETUPAPI / ADVAPI32 / GDI32 / SHELL32 / **OPENGL32** / WINMM / OLEAUT32 / IMM32 / **WINHTTP** / bcrypt / HID / CRYPT32 / WS2_32 / dwmapi（共 19 个） |
| `AliceInCradle_Data/Plugins/x86_64/cri_ware_unity.dll` | x86_64 | PROPSYS / KERNEL32 / ole32 / WS2_32 / **MFPlat.DLL** / **AVRT.dll** |
| `AliceInCradle_Data/Plugins/x86_64/nanosockets.dll` | x86_64 | WS2_32 / KERNEL32 |

几个由此得出的判断：

- **`winhttp.dll` 是 `UnityPlayer.dll` 的真实导入项**（Unity 的 `UnityWebRequest` 在 Windows 上走 WinHTTP）。
  AIC 装的 BepInEx 用 doorstop 把 `winhttp.dll` 换成了自己的代理 dll。
  **不推它也不会让游戏起不来** —— Wine 有内建 `winhttp`，导入照样解析，
  只是没有 BepInEx 注入而已。这正好符合「先跑通干净原版」。
- **`cri_ware_unity.dll` 导入 `MFPlat.DLL`（Media Foundation）**，而 Winlator 的
  `wincomponents.json` 里**没有 mfplat 组件**（只有 direct3d / directmusic / directplay /
  directshow / directsound / vcrun2005 / vcrun2010 / wmdecoder / xaudio）。
  所以 CRIWARE 这条音频链只能吃 Wine 内建 `mfplat` —— **这是音频最可能出问题的地方**，
  实机验收时要专门听一下 BGM 和音效。
- **没有一个插件导入 `msvcp100`/`msvcr100`/`msvcp140`/`vcruntime140`**，
  所以 `vcrun2005`/`vcrun2010` 对这个游戏其实是空转（保持上游默认，无害）。
- **`AVRT.dll` + `ole32`** 说明 CRIWARE 走的是 WASAPI 独占（`mmdevapi` 是动态加载的，不在导入表里），
  音频最终落到 Wine 的 `alsa` 驱动 → Android AudioTrack。


---

## 四、构建

CI（`.github/workflows/build.yml`）在 Ubuntu runner 上构建，一轮约 2~3 分钟：

```bash
gh run list  -R lilyco-42/aic-winlator
gh run watch -R lilyco-42/aic-winlator
gh run download <run-id> -R lilyco-42/aic-winlator -D out
```

工具链版本必须对齐，不能随手升：

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.14.5（wrapper 自带） |
| AGP | 8.4.2 |
| compileSdk | 35 |
| NDK | **24.0.8215888**（= r24，`app/build.gradle` 里 pin 死的） |
| CMake | 3.22.1 |

NDK 直接从 `dl.google.com` 下 zip 再放到 `$ANDROID_SDK_ROOT/ndk/24.0.8215888`，
不走 `sdkmanager` —— 新版 cmdline-tools 里 `sdkmanager` 已被 `android` CLI 取代，
包名语法不一样，会报 `Package ndk not found`。

本地构建（可选）：

```bash
export ANDROID_HOME=/path/to/android-sdk   # 需要 platforms;android-35 + cmake;3.22.1 + ndk;24.0.8215888
./gradlew :app:assembleDebug
```

---

## 五、部署到手机

游戏本体**不进仓库、不进 CI 产物**（版权 + 体积），由 adb 推到手机的 Download 目录。
容器里的 `D:` 盘就映射到 `/sdcard/Download`，所以 App 能直接访问，不用把 670MB 拷进私有目录。

```bash
bash deploy.sh out/winlator-apk-debug/app-debug.apk
```

脚本会：
1. `adb install -r -d <apk>`
2. 把 `AliceInCradle.exe` / `AliceInCradle_Data` / `UnityPlayer.dll` / `MonoBleedingEdge` /
   `UnityCrashHandler64.exe` 推到 `/sdcard/Download/AliceInCradle/`
3. **故意不推** `BepInEx/`、`winhttp.dll`、`doorstop_config.ini`
   —— 那是 BepInEx 的加载器，在 Wine + Box64 下大概率加载失败，
   会把「游戏跑不起来」和「mod 跑不起来」搅在一起。先跑干净的原版。

---

## 六、已知限制

- **不能和正版 Winlator 共存**：包名虽然改成了 `com.lilyco42.aicwinlator`，但如果手机上
  已装过用别的签名打包的同包名应用，需要先卸载。
- **只出 arm64-v8a**：上游 debug buildType 就只编 `arm64-v8a`。32 位 ARM 机型不支持。
- **游戏本体需自行准备**：本仓库不含游戏任何内容。
- 触屏布局是按[官方键位表](https://aicwiki.com/zh/home/control-guide)做的初版，
  实机手感需要按个人习惯在 Winlator 的「输入控制」里微调。

---

## 七、验证到了哪一步

| 验的东西 | 怎么验的 | 结果 |
|---|---|---|
| APK 包名 / 应用名 | `aapt2 dump badging` | `com.lilyco42.aicwinlator` / `Alice in Cradle` ✅ |
| ABI | `aapt2 dump badging` | `native-code: 'arm64-v8a'` ✅ |
| 触屏布局打进包 | `unzip -l` | `assets/inputcontrols/profiles/controls-5.icp` 在包内 ✅ |
| 定制类编进 dex | `dexdump` | `AicSetup` 出现 20 处引用 ✅ |
| 容器参数真的落盘 | 模拟器上 `run-as cat .container` | `box64Preset=STABILITY` / `dxwrapper=dxvk` / `extraData.controlsProfile=5` / `BOX64_UNITYPLAYER=1` ✅ |
| 快捷方式真的落盘 | 模拟器上 `run-as cat '.../Alice in Cradle.desktop'` | `Exec=wine D:\\\\AliceInCradle\\\\AliceInCradle.exe` / `execArgs=-force-gfx-direct` ✅ |
| `-force-gfx-direct` 注入链 | 读代码 + 追调用顺序 | `applyStartupWorkarounds`（:224）→ `EnvVarsWorkaround` → `getWineStartCommand()`（:999）消费 ✅ |
| 首启不崩 | 模拟器冷启动 | 无崩溃，容器 + 快捷方式都建好 ✅ |

**没验到、也验不了的**：模拟器是 **x86_64**（带 `libndk_translation` 翻译层），
而 Box64 是 x86_64→ARM64 的翻译器 —— 在 x86_64 上跑等于套两层翻译，
得到的失败信号全是模拟器特有的，没有参考价值。
**所以「能不能进游戏 / 音频对不对 / 帧率多少」只能在真机（一加 15 / PLK110）上验。**

