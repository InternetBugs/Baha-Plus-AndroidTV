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
