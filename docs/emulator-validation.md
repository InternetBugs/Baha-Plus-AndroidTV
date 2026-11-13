# 本机 Android TV 模拟器测试

执行日期：2026-11-12 至 2026-11-13，Asia/Taipei。

本记录随源码上传。截图、UI 树、内存输出和运行日志保存在本机 `artifacts/emulator/`，不纳入仓库；下文的本机路径用于查找已有证据，远端读者可使用文末命令自行复现。

测试使用实际官方内容和访客会话。应用操作由 ADB 发送方向、确认、返回按键完成；搜索文本通过电视软键盘输入。官方年龄按钮通过应用内遥控光标点击。未使用触屏坐标注入、预置搜索文本或伪造登录、授权和进度。

## 环境与产物

| 项目 | 配置 |
| --- | --- |
| 宿主机 | 当前 Windows 电脑，Intel i9-12900HX，约 32 GiB 内存 |
| 加速 | Windows Hypervisor Platform |
| Android Emulator | 37.2.13 |
| AVD | `BahaPlusTV_API30`，`tv_1080p` |
| 系统镜像 | `system-images;android-30;android-tv;x86` revision 4 |
| 系统／ABI | Android 11／API 30，32 位 x86 |
| 显示 | 1920×1080，density 320，D-pad，无触屏 |
| 虚拟资源 | 4 核、2048 MiB RAM，SwiftShader |
| 系统 WebView | 90.0.4430.91 |
| 运行方式 | 无窗口、无宿主音频、无快照 |
| 应用 | `app.bahaplus.tv`，0.1.0 debug，默认低资源模式 |
| 最终 APK | `artifacts/BahaPlusTV-0.1.0-debug.apk`，6,452,064 字节 |
| SHA-256 | `631257a3b2ac85181ef40199aa571e5ec0c6c1629b1e2d04a558d558ddd62836` |

该模拟器具有 32 位运行环境，但 CPU 架构、系统解码器和 GPU 均与目标 ARM 电视不同。测试结果不能代替 TCL 55Q9K 的性能、硬件解码、声音和音画同步验收。

## 运行结果

“通过”仅表示本次模拟器中实际观察到的行为。

| 场景 | 结果与证据 |
| --- | --- |
| 安装与启动 | 最终 APK 更新安装成功，冷启动成功；最终包一次 `am start -W` 的 `TotalTime` 为 1285 ms。这只是 Activity 启动时间，不是官方内容首屏时间或性能基准 |
| 官方首页 | 海报及标题真实加载，首页截图（本机 `artifacts/emulator/home-network-retry.png`） |
| 侧栏导航 | 首页、搜索、追番、历史、设置可切换；访客历史显示登录提示，历史页（本机 `artifacts/emulator/regression-history-guest.png`） |
| 首列返回侧栏 | 海报和选集首列左键均返回首页侧栏，海报回归（本机 `artifacts/emulator/regression-left-to-rail.png`）、选集回归（本机 `artifacts/emulator/regression-episode-left-to-rail.png`） |
| 详情及选集 | 官方简介、制作信息和集数可见；选中第 4 集进入后返回仍选中第 4 集，详情（本机 `artifacts/emulator/detail-live.png`）、集数恢复（本机 `artifacts/emulator/detail-restored-episode.png`） |
| 列表位置恢复 | 打开详情再返回，同一海报焦点与边界均为 `[354,630][715,1016]`，进入前（本机 `artifacts/emulator/regression-scroll-before.png`）、返回后（本机 `artifacts/emulator/regression-scroll-after.png`） |
| 遥控搜索 | 通过 TV 软键盘方向键输入 `fate`，关闭键盘后提交，官方结果出现并聚焦首项，输入（本机 `artifacts/emulator/search-remote-entered.png`）、结果（本机 `artifacts/emulator/search-submitted.png`） |
| 设置 | 低资源模式、自动下一集和简繁切换可操作并保留当前焦点；语言设置在进程重启和更新安装后保留，设置（本机 `artifacts/emulator/settings-traditional.png`） |
| 访客追番与登录入口 | 访客追番显示登录提示；详情“加入追番”打开官方登录表单，方向键可进入网页，返回恢复详情操作焦点，登录页（本机 `artifacts/emulator/official-login-form.png`）、返回详情（本机 `artifacts/emulator/login-back-to-detail.png`） |
| 遥控光标 | 首次打开、退出播放页后再次打开均居中，首次（本机 `artifacts/emulator/regression-cursor-first.png`）、再次（本机 `artifacts/emulator/regression-cursor-reopened.png`） |
| 官方验证与广告 | 方向键移动光标确认官方年龄按钮，官方广告实际播放；未完成前保持官方页面，广告（本机 `artifacts/emulator/regression-after-age.png`） |
| 原生正片接管 | 《佐佐木與文鳥小嗶 Season 2》第 1 集进入 Media3，原生控制条及正片可见，进度持续推进，原生播放（本机 `artifacts/emulator/native-playback-confirmed.png`） |
| 暂停与恢复 | 暂停后连续两次读取均为 `02:53`；恢复后推进至 `02:55`，暂停（本机 `artifacts/emulator/native-pause-stable.png`）、恢复（本机 `artifacts/emulator/native-resumed.png`） |
| 控制条快进退 | 暂停时 `02:53 → 03:03 → 02:53`，前进（本机 `artifacts/emulator/native-forward-ten.png`）、后退（本机 `artifacts/emulator/native-rewind-ten.png`） |
| 控制条隐藏时快进退 | 暂停时 `03:31 → 03:41 → 03:31`，左右键各改变 10 秒，前进（本机 `artifacts/emulator/native-hidden-forward.png`）、后退（本机 `artifacts/emulator/native-hidden-rewind.png`） |
| 画质与倍速 | 本次访客仅获准自动与 360p；可切换至 360p 和 1.25×，暂停位置保持；测试后恢复自动和 1.0×，画质（本机 `artifacts/emulator/native-quality-360.png`）、倍速（本机 `artifacts/emulator/native-speed-125.png`） |
| 后台暂停 | Home 进入电视桌面后返回应用保持暂停，两次读取均为 `03:31`，返回应用（本机 `artifacts/emulator/native-after-background.png`）、时间保持（本机 `artifacts/emulator/native-background-pause-stable.png`） |
| 手动切集 | 原生选集选择第 2 集，官方页面标题确认 `[2]`，再次经官方年龄与广告后进入原生正片，时长为 `23:40`，官方第 2 集（本机 `artifacts/emulator/episode-two-ready.png`）、第 2 集原生播放（本机 `artifacts/emulator/episode-two-native-controls.png`） |
| 自动下一集 | 暂停后通过遥控左右快进到 `23:30 / 23:40`，恢复播放到片尾，自动打开官方第 3 集并停在年龄确认；未绕过下一集验证，片尾（本机 `artifacts/emulator/episode-two-end-resumed.png`）、自动第 3 集（本机 `artifacts/emulator/auto-next-episode-three.png`） |

## 本轮发现与修复

1. 遥控光标在尚未测量尺寸时初始化为左上角，隐藏的 1 像素 WebView 又会压缩坐标。改为有效尺寸下定位，每次启用重新居中；重复进入播放页已回归。
2. 第一列海报仅依赖 `nextFocusLeftId`，在 RecyclerView 中不能稳定回到侧栏。增加首列左键处理，选集首列也采用相同处理。
3. 切换侧栏或返回首页时，延迟的内容焦点恢复可能抢走侧栏焦点。显式导航渲染时关闭自动内容焦点恢复，访客历史与返回首页已回归。
4. 列表恢复后再次 `scrollToPosition` 会改变保存的滚动偏移。优先聚焦已附着的目标卡片，仅在目标不存在时滚动，回归前后卡片位置完全一致。

上述修复已包含在本记录的最终 APK 内。

最终包的 Android 编译、4 项 JVM 测试、Lint 和 APK 生成已通过；本轮再次运行 12 项官方适配器离线测试及 XML／JSON／JavaScript／许可证／Wrapper 检查，均通过。最终 APK 的 v2 签名验证与 16 KiB 对齐检查通过。详见 [构建验证](build-validation.md)。

## 观测与边界

原生第 2 集暂停时，主进程 PSS 为 133,353 KiB（约 130.2 MiB），RSS 为 273,632 KiB（约 267.2 MiB）。这是一次采样，未计入独立 WebView 渲染进程、系统解码器或全部图形缓冲，不是峰值或低端电视资源承诺。见 内存原始输出（本机 `artifacts/emulator/native-playback-memory.txt`）。

对当前应用进程及保留日志缓冲的检查发现：Java fatal exception 0 次、native fatal signal 0 次，系统缓冲中该包名 ANR 0 次；自动切集和退出后复查结果相同。见 播放统计（本机 `artifacts/emulator/native-playback-stats.json`）、最终复查（本机 `artifacts/emulator/final-session-stats.json`）。这不能证明未保留的历史日志没有错误。

UIAutomator 在动态 WebView 广告树上出现过 `AccessibilityNodeInfoDumper` 异常、退出码 137 或空 XML。这属于测试工具采集失败；广告和视频阶段改用截图，并另外检查应用进程。部分早期探索截图的文件名不代表其实际测试结果，以本表明确引用的证据为准。

尚未覆盖：

- 输入真实账号密码并完成登录、验证码、两步验证、会话失效和登出。
- 账号下的官方追番写回、观看历史写回、跨端续播及账号画质权限。
- 连续无快进看完一整集、整集音画同步、掉帧及反复 10 次播放的资源变化。
- 关闭自动下一集时的实际片尾行为，以及最后一集结束页；本次片尾测试仅覆盖开启自动下一集的第 2 → 3 集。
- API 24、API 36 和目标 ARM 电视实机。
- 网络断开、设备锁、账号策略拒绝和渲染进程退出的完整故障矩阵。

## 本机复现

项目专用 SDK 和 AVD 位于 `.tools/`，不会进入源码提交。启动时将 `ANDROID_HOME`／`ANDROID_SDK_ROOT` 指向 `.tools/android-sdk`，`ANDROID_AVD_HOME` 指向 `.tools/avd`，`ANDROID_EMULATOR_HOME` 指向 `.tools/emulator-home`。本次 ADB 使用 `.tools/emulator-home/adbkey` 对应的测试密钥，不应导出或提交密钥内容。

```powershell
$env:ANDROID_HOME = Join-Path $PWD '.tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = Join-Path $PWD '.tools/avd'
$env:ANDROID_EMULATOR_HOME = Join-Path $PWD '.tools/emulator-home'
$env:ANDROID_USER_HOME = $env:ANDROID_EMULATOR_HOME
$env:ADB_VENDOR_KEYS = Join-Path $env:ANDROID_EMULATOR_HOME 'adbkey'

& "$env:ANDROID_HOME/emulator/emulator.exe" -avd BahaPlusTV_API30 -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -memory 2048 -cores 4 -no-metrics
```

在另一终端安装并操作：

```powershell
& '.\.tools\android-sdk\platform-tools\adb.exe' -s emulator-5554 install -r artifacts/BahaPlusTV-0.1.0-debug.apk
python tools/emulator-inspect.py launch
python tools/emulator-inspect.py keys DPAD_RIGHT DPAD_CENTER
python tools/emulator-inspect.py capture example
python tools/emulator-inspect.py capture video-example --screenshot-only
python tools/emulator-inspect.py stats sample
```

辅助工具只操作显式指定的 `emulator-*` 设备，截图／XML／摘要写入 `artifacts/emulator/`。统计只保存内存及错误计数，不保存可能带会话 URL 的原始 logcat。

本机 Mihomo TUN 环境下，模拟器直接出站曾发生 `ERR_CONNECTION_CLOSED`。测试临时让 AVD 使用宿主机已经存在的代理入口 `10.0.2.2:7890`；未更改宿主机代理配置。其他电脑应使用其正常可访问官方服务的网络条件。

结束时已清除 AVD 临时代理，回读为 `:0`。本次 `adb emu kill` 返回控制台未知命令，随后通过虚拟系统关机关闭模拟器；已确认 ADB 设备列表为空，测试用 emulator 与 QEMU 进程均退出。未停止宿主机现有代理服务。

清理命令：

```powershell
& '.\.tools\android-sdk\platform-tools\adb.exe' -s emulator-5554 shell settings put global http_proxy :0
& '.\.tools\android-sdk\platform-tools\adb.exe' -s emulator-5554 shell reboot -p
```

生成的 emulator stdout 因早期运行目录锁错误积累过重复日志；模拟器停止后已保留注明范围的末尾摘要，将约 298 MB 的生成日志缩减为约 5.6 KB。它不是完整运行日志。

## 年龄确认遥控回归（2026 年 11 月 13 日）

本轮使用独立 AVD `BahaPlusTV_Age_API30`，Android TV API 30／32 位 x86、1920×1080、默认低资源模式及官方访客会话。所有应用操作使用 ADB 方向键、确认键和返回键，年龄确认使用新增的原生电视大按钮。

| 场景 | 实际结果与本机证据 |
| --- | --- |
| 初次进入年龄页 | 官方分级标志及警告保留，底部“同意并继续”自动获得焦点；`artifacts/emulator/age-isolated-initial.png` |
| 左右切换及焦点保持 | 右键聚焦“不同意，返回详情”，连续采集期间保持该选项；右、左往返后回到“同意并继续”；`age-isolated-right.png`、`age-isolated-right-stable.png`、`age-isolated-left.png` |
| 拒绝及重新进入 | 确认“不同意，返回详情”回到详情“开始播放”；重新进入后再次聚焦年龄确认；`age-isolated-declined.png`、`age-isolated-reopened.png` |
| 顶栏刷新 | 从大按钮按上、右到达刷新；刷新后年龄页重新出现并聚焦同意按钮；`age-isolated-refresh-focused.png`、`age-isolated-refreshed.png` |
| 确认 | 一次确认点击当前文档的官方同意按钮，底部操作条收起，官方广告实际播放；`age-isolated-after-confirm.png` |

25 项 JavaScript 适配器测试、5 项 JVM 测试、Android 编译、Lint 与 APK 生成通过；Lint 错误为 0。新增适配器测试覆盖显式确认、重复提交、过期文档、后台、非年龄阶段、隐藏／禁用／变化的按钮与错误来源。当前应用进程的保留日志中 Java fatal exception、native fatal signal 和 ANR 计数均为 0，见 `artifacts/emulator/age-isolated-stats.json`。

本轮记录不扩大此前的目标 ARM 电视、真实账号同步或整集播放验收范围。

## WebView 兼容性修复的本地回归（2026-11-13）

本轮使用全新访客 AVD `BahaPlusTV_WebViewCompat_API30`：Android TV 11／API 30、32 位 x86、1920×1080、2 核、1536 MiB RAM、SwiftShader，独立模拟器端口 5584 和 ADB 服务端口 5047。只复用只读系统镜像及设备配置，没有复制其他模拟器的应用数据或账号。应用操作通过方向键、确认键和返回键执行。

安装包为本次修复后的 `artifacts/BahaPlusTV-0.3.0-debug.apk`，SHA-256：`96001f05b9b12d02fe0a8f0efe433b86e74fb4134f3ee98ef6deaa60ae0d2d18`。当前 TV 镜像实际使用 `com.google.android.webview` 90.0.4430.91，因此下表属于 WebView 90 的端到端回归。

| 场景 | 实际结果 | 本机证据，均位于 `artifacts/performance/` |
| --- | --- | --- |
| 首页与访客状态 | 官方海报加载，显示“访客浏览” | `webview83-home.png`、同名 JSON |
| 详情选集 | 《冰劍的魔術師將要統一世界 2》简介及 1～6 集全部显示 | `webview83-first-detail.png`、同名 JSON |
| 第 1 集播放 | 经官方年龄确认进入原生正片，时间从 `00:03 / 24:15` 推进至 `00:11 / 24:15` | `webview90-first-episode-age.png`、`webview90-first-episode-native.png`、`webview90-first-episode-entry.json` |
| 遥控暂停 | 两次采集均停留在 `01:00 / 24:15`，按钮为“播放” | `webview90-paused.json`、`webview90-paused-stable.json` |
| 返回与选集导航 | 返回后 6 个集数按钮仍可见；下键聚焦第 3 集，左键聚焦第 2 集 | `webview90-return-detail.json`、`webview90-episode-focus.json`、`webview90-select-episode-two.json` |
| 第 2 集播放 | 确认第 2 集，经官方页面进入原生正片；时间从 `00:03 / 24:14` 推进至 `00:11 / 24:14` | `webview90-second-episode-advertisement.png`、`webview90-second-episode-age.png`、`webview90-second-episode-native.png`、`webview90-second-episode-entry.json` |
| 返回首页 | 返回列表后焦点位于同一作品海报 | `webview90-second-return.json` |
| 崩溃检查 | 保留日志中应用 Java 崩溃、应用原生崩溃、当前 PID 致命信号和应用 ANR 均为 0 | `webview90-final-errors.json` |

### 实际 WebView 83 的早期切换尝试（后续已完成，见下文）

从 Android 官方 AOSP `platform/external/chromium-webview` 提交 `cc0834d3a141fd1e35056f0008066a887ca495a4` 取得 x86 `webview.apk`，实际版本为 **83.0.4103.120**，与反馈日志相同。包名为 `com.android.webview`，versionCode 410412051、targetSdk 30、最低 API 21。已核对官方 Git blob `c068c1ea575798c76d751202f7a4e4fd21128579`，并通过 APK v1／v2 签名验证。安装包大小 44,364,882 字节，SHA-256 为 `f220097f11b22e4b81bf0cf025884f241755779765aeb35b07a224942847283e`。

旧包在该访客模拟器中安装成功，但系统执行 `cmd webviewupdate set-webview-implementation com.android.webview` 后拒绝启用。`dumpsys webviewupdate` 显示最低允许版本号为 443009131，83 的拒绝原因为 `Version code too low`；实际活动引擎仍为 Google WebView 90。不能将上述结果表述为 WebView 83 测试通过。

后续方案是使用可配置提供程序的独立 AOSP API 29／x86 镜像安装同一官方 83 包。已从 Google 官方镜像目录核对 `system-images;android-29;default;x86` revision 8，压缩包 516,543,600 字节，约 493 MiB，官方 SHA-1 为 `cc4fa13e49cb2e93770d4f2e90ea1dd2a81e315b`。下载与解压最初被自动审批拒绝；随后用户明确回复“允许”，已完成下载、大小与官方 SHA-1 校验、解压。

新建 AVD `BahaPlusTV_WebView83_API29`，独立数据目录 `.tools/avd-webview83-api29/`，模拟器端口 5586、ADB 5049；采用 TV 1080p 设备配置，但系统镜像为 AOSP Android 10／API 29，32 位 x86、2 核、1536 MiB。该镜像为 `userdebug`，预装 WebView **74.0.3729.185**。直接安装官方 83 包以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 失败，原因是与预装包签名不同；实际活动提供程序仍为 74，未作为 83 测试通过。

该隔离实例可启用 root 调试，常规 `adb remount` 因系统分区只读而失败。最初计划通过 `-writable-system` 创建临时系统副本，启动动作被自动审批拒绝；用户授权后先转为测试预装 WebView 74，因此在早期尝试结束时尚未替换系统包。此后用户明确要求继续 83 测试，已成功在同一专用 AOSP 访客 AVD 的可写副本中激活官方 83，结果见下文。只读基础镜像与官方压缩包中的 `system.img` CRC32 `dedcb335` 核对一致。

综合记录为本机 `artifacts/performance/webview83-attempt-and-webview90-regression.json`；官方旧包来源与哈希位于 `.tools/webview83/source.json`。本次回归未覆盖反馈作品《黑色五葉草》、真实账号同步、整集播放或目标 ARM 电视。

测试结束后已停止应用、清除该 AVD 的临时代理并读回 `:0`，关闭专用 ADB 5047 及该测试模拟器，核验 5047／5584／5585 均无残留监听。此前的其他模拟器与 ADB 服务未作为清理目标。

### 实际 WebView 74：应用适配与官网播放器限制（2026-11-13）

用户改为要求直接测试预装的 **74.0.3729.185**，并授权发现问题后自主修复。普通只读系统启动最初被自动审批拒绝；再次明确授权后启动成功。实际提供程序经 `dumpsys webviewupdate` 核实为 `com.android.webview` 74.0.3729.185；没有通过浏览器标识伪装版本，也没有替换系统包。

独立 AVD 为 `BahaPlusTV_WebView83_API29`，模拟器端口 5586／5587、专用 ADB 5049；Android 10／API 29、32 位 x86、1920×1080、2 核、1536 MiB、SwiftShader。它使用 TV 硬件配置，但系统镜像是 AOSP，不能表述为 Android TV 系统或反馈电视实机。

实际 74 引擎中，`?.` 和 `??` 分别抛出 `SyntaxError: Unexpected token .` 与 `SyntaxError: Unexpected token ?`；`replaceAll`／`replaceChildren` 均为 `undefined`，CSS `inset` 不受支持。13 份原始适配器均不能在该引擎解析，应用停留在“正在连接动画疯”，尽管官网 DOM 中已有 75 张热门卡片与 108 张本季卡片。静态和真实引擎证据分别为 `webview74-static-syntax-audit.json`、`webview74-runtime-features.json`、`webview74-runtime-adapter-syntax.json`。

修复后，适配器源码移至 `app/src/main/js/adapters/`。Gradle 在打包前调用锁定版本 esbuild 0.25.12，目标为 Chrome 74，并以 Acorn 8.15.0 检查 ECMAScript 2019 语法；生成的 13 份脚本在实际 74 引擎全部解析通过。`OfficialPortal.kt` 的消息桥、年龄确认、原生接管和生命周期等内嵌脚本也移除了可选链。该编译步骤只处理本项目适配器，不转换或替换官网脚本。

最终调试 APK 为 `artifacts/BahaPlusTV-0.3.0-debug.apk`，大小 **6,537,543 字节**，SHA-256 为 `ca147b77cf5c1c082dd2d4145e970324a9145eb9881d7284adcf3bf206ee312d`。13 份打包脚本与构建输出逐字节匹配。下表均使用这一最终 APK、官方访客会话；中文片名由测试专用输入法提交，使用一次 TAB 从输入框切到搜索按钮，其余导航使用方向键、确认和返回。

| 场景 | 实际结果 | 本机证据，均位于 `artifacts/performance/` |
| --- | --- | --- |
| 首页 | 官方海报列表与访客状态加载 | `webview74-final-home.png`、同名 JSON |
| 搜索反馈作品 | 显示《黑色五葉草 Season 2》与《黑色五葉草》两个结果 | `webview74-final-search.png`、同名 JSON |
| 反馈作品详情 | 《黑色五葉草》简介与 170 集列表加载 | `webview74-final-detail.png`、同名 JSON |
| 第 1 集 | 打开官方标题 `[1]`；官网播放器脚本解析失败，显示原生兼容性提示 | `webview74-final-first-incompatible.png`、`webview74-final-episode-1-site-state.json` |
| 第 2 集 | 方向键聚焦第 2 集并确认；官方标题 `[2]`，同样显示兼容性提示 | `webview74-final-episode-two-focus.png`、`webview74-final-second-incompatible.png`、`webview74-final-episode-2-site-state.json` |
| 返回详情 | 返回后保留 170 集列表，第 2 集保持焦点 | `webview74-final-second-return.png`、同名 JSON |
| 大集数滚动 | 从第 2 集按下 28 次到第 170 集，再按上 28 次返回第 2 集 | `webview74-final-last-episode-focus.png`、`webview74-final-scroll-return.png` |
| 返回搜索列表 | 焦点恢复到《黑色五葉草》海报 | `webview74-final-return-search.png`、同名 JSON |
| 崩溃检查 | 保留日志中应用 Java／原生崩溃、当前 PID 致命信号和应用 ANR 均为 0 | `webview74-final-crash-errors.json` |

**74 上未通过正片播放。** 初次等待 150 秒后仍无原生播放时间线。此时本项目播放器桥接脚本已经安装，但官网的 `videojs`／`animePlayer` 未定义，DOM 中没有视频节点。对官网原样公开脚本做真实 74 解析检查：`dialogify.min.js` 报 `Unexpected token ?`，`util.js` 报 `Unexpected token .`，`build/js/anime_player.js` 报 `Unexpected token (`。使用 Chrome 74 的浏览器标识重载后，播放器仍未初始化。见 `webview74-first-player-state.json`、`webview74-official-public-script-syntax.json`、`webview74-old-user-agent-player-state.json`。未修改官方授权、年龄、广告或播放逻辑，也未绕过它们取得媒体源。

应用增加了对**官方主播放器脚本语法错误**的识别：只在播放阶段、明确的官方脚本来源与控制台错误级别匹配时显示“系统 WebView 与官网不兼容”，说明更新系统 WebView 后重试，并允许返回详情。没有把广告脚本或一般运行时错误当成这一故障；来源与错误分类有 3 项 JVM 测试。最终 APK 在第 1、2 集均触发了这一提示，避免无限黑屏。官网通用脚本也有错误，因此不能据此宣称 74 的真实账号登录／同步兼容。

综合证据为 `webview74-final-session.json`、`webview74-final-regression.json`。74 实例测试后已卸载测试输入法，停止应用，清除临时代理并读回 `:0`，移除 DevTools 转发，虚拟系统关机，关闭专用 ADB 5049；5049／5586／5587 无残留监听。这个 74 测试阶段没有激活 WebView 83，也没有验证目标 API 30／ARM 电视、真实账号或整集播放。

### 实际 WebView 83：转换前基线（2026-11-13）

后续在 `BahaPlusTV_WebView83_API29` 的临时可写系统副本中替换 WebView APK 及对应 x86 原生库，`dumpsys webviewupdate` 确认活动提供程序为 **83.0.4103.120**。只读基础镜像没有修改。它仍是 Android 10／API 29 的 AOSP 系统配 TV 硬件配置，不能等同于反馈中的 Android 11／ARM 电视。

先安装不含官网转换层的 `ca147b77cf5c1c082dd2d4145e970324a9145eb9881d7284adcf3bf206ee312d` 调试包：首页、搜索、《黑色五葉草》170 集详情及焦点恢复正常；第 1、2 集的官网播放器解析失败，没有进入原生正片。实际 83 可以解析可选链和空值合并，但缺少 `String.replaceAll`／`replaceChildren`，不支持 CSS `inset`。官方原样 `anime_player.js` 在该内核报 `Unexpected token '('`；当时公开包有 116 处私有方法。见 `artifacts/performance/webview83-real-final-session.json`、`webview83-real-runtime-features.json`、`webview83-real-official-script-syntax.json`。

### 按语法探测启用转换：83 与 90 回归（2026-11-13）

以下记录使用含有限 API 补丁的 v3 编译器修订，APK SHA-256 为 `0c24e31b5e3040dca5f15d77f0484b938ba97c383f959b560bf14ce03ea40b0f`。后面的 v4 收尾回归单独记录，避免将中间包证据混称为最终包。两台测试设备都为独立访客会话，官网年龄确认和广告保留，播放后才由现有 ExoPlayer 桥接接管。

| 场景 | 实际结果 | 本机证据，均位于 `artifacts/performance/` |
| --- | --- | --- |
| 83 首页、中文搜索、详情 | 反馈作品的 170 集加载 | `compat83-v3-home`、`compat83-v3-search`、`compat83-v3-detail` 的 PNG／JSON |
| 83 转换路径 | 语法探测为 false，官网页面不暴露编译 Java 接口；6 份抽样官方脚本转换后均可由实际 83 解析 | `compat83-v3-engine.json`、`compat83-v3-script-parse.json` |
| 83 第 1 集 | 经年龄确认和官方广告进入正片，时间 `00:03 → 00:09 / 23:49` | `compat83-v3-first-result.json`、对应年龄／广告／原生截图 |
| 83 暂停与返回 | 两次采集均为 `04:36 / 23:49`；返回后 170 集仍可见 | `compat83-v3-paused.json`、`compat83-v3-paused-stable.json`、`compat83-v3-first-return.json` |
| 83 第 2 集 | 官网标题为 `[2]`，原生后端 `android-exoplayer`，播放位置 `130.272 → 207.441` 秒，时长 `1429.728` 秒；截图显示 `02:15 / 23:49` | `compat83-v3-second-state.json`、`compat83-v3-second-progress-state.json`、`compat83-v3-second-native.png` |
| 83 缓存与错误 | 15 次 compiled、85 次 cached，没有 failed；记录的最长处理时间 2729 ms，含串行等待、不含下载 | `compat83-v3-final-compat-log.json` |
| 90 原加载路径 | Android TV 11／API 30、WebView 90.0.4430.91，语法探测为 true；6 份抽样仍是原始脚本，没有编译缓存或 compiled／cached 事件 | `compat90-v3-engine.json`、`compat90-v3-original-script-parse.json`、`compat90-v3-no-translation.json`、`compat90-v3-compat-log.json` |
| 90 正片及暂停 | 《冰劍的魔術師將要統一世界 2》第 1 集原生时间 `00:55 → 01:04 / 24:15`，暂停两次均为 `04:13 / 24:15` | `compat90-v3-retry-result.json`、`compat90-v3-native-state.json`、`compat90-v3-paused.json`、`compat90-v3-paused-stable.json` |
| 两台崩溃检查 | 保留日志范围内应用 Java／原生崩溃和 ANR 均为 0 | `compat83-v3-errors.json`、`compat90-v3-errors.json` |

83 第 2 集的自动等待工具因原生控制条隐藏而超时，原始 `compat83-v3-second-result.json` 保留为失败；上表的成功依据是之后独立采集的播放状态、位置推进和截图。90 的首次自动等待也没有取得成功证据，修正测试工具为年龄确认只提交一次后重试通过；没有据此确定首次失败的具体应用原因。

83 仍记录了官网播放器的非阻断运行错误：`OutOfPageFormat` 未定义 2 次、对 null 读取 `el` 1 次；测试范围内没有阻断上述两集播放，也没有再出现解析错误或缺少 `replaceAll` 的错误。详见 `compat83-v3-js-errors.json`。这些结果不涵盖真实账号、整集播放、WebView 74 正片或反馈的 32 位 ARM 电视。

### 最终 v4 调试包回归（2026-11-13）

收尾修复了 API 补丁的插入位置，保留官方脚本的严格／非严格语义，并使实际解析检查覆盖完整响应；编译器修订升为 v4 以失效旧缓存。最终 APK 为 **7,404,245 字节**，SHA-256 为 **`f37d0b5bcfcc6ac789ac79b6f7143cee687e3fa24c41acaf17e4de9813c2166a`**。在上述两台专用访客 AVD 上重新安装，设备内 APK 哈希均与最终产物一致，见 `compat83-v4-installed.json`、`compat90-v4-installed.json`。

| 最终包场景 | 实际结果 | 本机证据，均位于 `artifacts/performance/` |
| --- | --- | --- |
| 83 搜索及选集 | 首页、中文搜索、《黑色五葉草》170 集详情正常；返回详情后可用遥控选中第 2 集 | `compat83-v4-home.json`、`compat83-v4-search.json`、`compat83-v4-detail.json`、`compat83-v4-first-return.json`、`compat83-v4-episode-two-focus.json` |
| 83 第 1 集 | 官方年龄确认及广告后进入原生正片，位置 `56.419 → 109.514` 秒，时长 `1429.428` 秒；原生画面和控制条可见 | `compat83-v4-native-state.json`、`compat83-v4-native-progress-state.json`、`compat83-v4-native.png` |
| 83 暂停 | 两次采集停在 `01:50 / 23:49`，按钮为“播放” | `compat83-v4-paused.json`、`compat83-v4-paused-stable.json` |
| 83 第 2 集 | 官方年龄确认及广告后原生时间 `00:52 → 01:02 / 23:49`；独立状态位置 `33.023 → 50.124 → 98.291` 秒，时长 `1429.728` 秒，官网标题 `[2]` | `compat83-v4-second-result.json`、`compat83-v4-second-native.png`、`compat83-v4-second-stage.json`、`compat83-v4-second-progress-state.json`、`compat83-v4-second-final-state.json` |
| 83 转换及缓存 | 语法探测 false；6 份抽样脚本的完整转换响应均能由实际 83 解析；记录到 compiled 15 次、cached 86 次、failed 0 次 | `compat83-v4-engine.json`、`compat83-v4-script-parse.json`、`compat83-v4-final-compat-log.json` |
| 90 直接加载 | 语法探测 true；6 份脚本仍为原始内容，没有兼容缓存或 compiled／cached 事件；官网页面均不暴露编译接口 | `compat90-v4-engine.json`、`compat90-v4-original-script-parse.json`、`compat90-v4-installed.json`、`compat90-v4-final-compat-log.json` |
| 90 第 1 集及暂停 | 官方年龄确认及广告后原生时间 `04:10 → 04:19 / 24:15`；两次暂停采集均为 `05:31 / 24:15` | `compat90-v4-first-result.json`、`compat90-v4-native-state.json`、`compat90-v4-paused.json`、`compat90-v4-paused-stable.json` |
| 错误检查 | 两台保留日志范围内应用 Java／原生崩溃和 ANR 均为 0；83 仍有前述 3 次官网非阻断 TypeError，没有 SyntaxError 或 replaceAll 错误 | `compat83-v4-errors.json`、`compat90-v4-errors.json`、`compat83-v4-js-errors.json` |

83 第 1 集自动等待仍因原生控制条隐藏而超时，`compat83-v4-first-result.json` 原样保留为失败，上表以独立状态推进与截图判定播放成功。第 2 集在确认原生阶段后用上键显示控制条，自动工具随后取得时间推进结果；同时采集 UI 树时有一次退出码 137，未作为播放失败或成功依据。最终包没有扩大 74 正片、真实账号、整集播放或目标 ARM 电视的验收范围。

最终 83 日志记录的单次最长处理时间为 3973 ms，包含串行等待、不包含下载；这是这台 x86 访客模拟器的观测值，不是 ARM 电视的性能承诺。旧内核首次转换有额外开销，缓存后的再次进入无需重复编译相同源码；90 没有这些转换任务。

两台最终测试均已清理：核验 AVD 身份后停止应用，重置并卸载测试输入法（如有安装），将访客代理清除并回读为 `:0`，移除对应 DevTools 转发，关闭虚拟系统和各自专用 ADB。83 的 5049／5586／5587／9229、90 的 5047／5584／5585／9230 均确认不再监听，见 `compat83-v4-cleanup.json`、`compat90-v4-cleanup.json`。没有清理其他模拟器、账号会话、ADB 服务或宿主机代理。
