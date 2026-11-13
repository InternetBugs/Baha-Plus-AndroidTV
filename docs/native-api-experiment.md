# 原生官方播放接口实验

测试日期：2026-11-13，Asia/Taipei。

**访客播放路径及 24 分钟整集连续播放已通过。** 在 Android 10、32 位 x86、WebView 74 的隔离模拟器中，独立 Kotlin 会话通过官方 HTTP 接口完成设备初始化、策略读取、年龄确认、真实视频广告、授权取源及 Media3 正片播放。已完成两轮控制回归，以及 10 分钟和 24 分钟单集各一轮 1 倍速自然结束测试；各轮均观察到官方进度响应 `state: "ok"`，应用进程中的 WebView 数量为 0。

此结果支持继续开发原生播放会话。登录账号的历史与跨端续播、完整广告调度、家长验证及目标 ARM 电视仍需单独验收，当前实验不能直接替换正式版播放流程。

## 分支与安装包

实验分支为 `experiment/native-playback-api`，从 `2f3777c` 创建。提交范围为原生实验专属源码、测试、构建与模拟器测试工具、本报告及 `nativeApi` 构建类型；创建分支时已有的 WebView 兼容性等未提交改动继续保留在本地工作区。

实验仅增加 `nativeApi` 构建类型及其专属源码、测试和工具。`debug`、`release` 继续使用正常入口。实验包使用独立应用 ID，不读取正常包或浏览器的 Cookie、设备标识或数据。

| 项目 | 值 |
| --- | --- |
| APK | `artifacts/BahaPlusTV-native-api-debug.apk` |
| 应用 ID | `app.bahaplus.tv.nativeapi` |
| 版本 | `0.3.0-native-api`，versionCode 3 |
| 启动入口 | `app.bahaplus.tv.experiment.NativeApiActivity` |
| 最低与目标 API | 24 / 36 |
| 签名 | Android 调试签名，v2 验证通过 |
| SHA-256 | `a9f68deb42a9ccb077ee9bad839325adf69a601203bd01ef0c0e9b3351ff4750` |

## 实现范围

[`NativeApiClient`](../app/src/nativeApi/java/app/bahaplus/tv/experiment/NativeApiClient.kt) 使用内存中的独立访客 Cookie 和 `HttpURLConnection` 请求官方接口；[`NativeApiActivity`](../app/src/nativeApi/java/app/bahaplus/tv/experiment/NativeApiActivity.kt) 使用原生 View 和 Media3，未创建 WebView，也未执行官网 JavaScript。

流程为：

1. 请求官方影片页的静态 HTML，取得设备及影片资料。
2. 读取官方策略；遇到家长密码要求或服务端错误立即停止。
3. 展示官方分级对应的原生确认窗口。
4. 需要广告时，从当前官方配置解析 `video` 广告，实际播放并正常回报开始事件。累计至少 30 秒实际媒体推进后，用户才能选择跳过，随后回报结束事件。
5. 请求本次会话获准的播放清单，接收服务端设备标识更新，交给 Media3。
6. 执行设备检查与开始播放通知；暂停、恢复和后台切换回报状态；每累计 60 秒实际媒体推进回传进度。快进、暂停及缓冲不计入观看累计值。

广告解析仅将公共配置中的数组作为 JSON 读取。短于 30 秒的广告会停止实验，完整的官方广告回退链尚未实现。跳过按钮保持禁用，直到真实播放累计达到阈值且开始事件的请求成功。

[`NativeApiPolicy`](../app/src/nativeApi/java/app/bahaplus/tv/experiment/NativeApiPolicy.kt) 限制当前支持的官方 HTTPS 媒体来源、设备类型和进度参数。接近片尾的完成标记保留官方语义；下文的整集测试另核对自然片尾后的进度响应。

## 实测结果

测试实例为 `BahaNativeApiGuest_API29`，ADB 端口 5071，设备 `emulator-5592`。系统镜像为 `system-images;android-29;default;x86`，系统 WebView 为 `74.0.3729.185`，配置为 2 核、1536 MiB RAM、1920×1080、SwiftShader。下表的两轮控制回归使用先前实验包，SHA-256 为 `2f8eb0772e66f2d58ca26e6e0cbfbfb73a206f0a8af709288a85a75593657250`。

该实例使用独立 userdata，没有复制用户账号或已有模拟器的数据。每次启动实验包均重新建立内存访客会话。测试期间仅此实例临时使用宿主已有代理入口 `10.0.2.2:7890`；没有更改宿主代理或用户正在使用的模拟器。

| 检查 | 第 1 集 `40190` | 第 2 集 `40191` |
| --- | --- | --- |
| 官方片名 | 放學後少年花子君 [1] | 放學後少年花子君 [2] |
| 实际广告累计 | 31.883 秒 | 32.225 秒 |
| 正片解码尺寸 | 640×360 | 640×360 |
| 测试结束时实际观看累计 | 66.060 秒 | 65.732 秒 |
| 测试结束时播放位置 | 77.728 秒 | 82.674 秒 |
| 暂停、恢复及前进 10 秒 | 通过 | 通过 |
| 快进距离未计入观看累计 | 通过 | 通过 |
| 1.5 倍速 | 通过 | 通过 |
| Home 后暂停，返回后需手动播放 | 通过 | 通过 |
| 官方进度 `ok` 响应 | 3 次，包含 60 秒定时回报 | 3 次，包含 60 秒定时回报 |
| `dumpsys meminfo` 的 WebViews | 0 | 0 |
| 该隔离 Android 实例中的 WebView 渲染进程 | 0 | 0 |
| 当前进程崩溃缓冲中的 fatal 记录 | 0 | 0 |

“实际观看累计”按真实媒体推进计算，倍速会增加媒体推进速度。它与墙钟时间、播放位置和跳转距离分别处理。官方 `ok` 表示接口接受了该访客请求，**不能据此确认登录账号观看历史或跨端续播成功**。

第二集播放后期、关闭实例前的一次应用进程 PSS 采样为 78,749 KiB，WebViews 为 0，见本机 `memory-sample.json`。这是单次资源观测，没有同条件正式版对照，不能换算为节省比例或目标电视的内存承诺。

首轮调试还观察到约 112 秒视频广告自然结束后，正常获得正片来源；表内两个最终包样本均测试了实际播放超过 30 秒后使用跳过按钮的路径。

## 10 分钟整集连续播放

同日使用最新实验包，在同一隔离模拟器中新建访客会话，以 1 倍速从片头连续播放《放學後少年花子君 [1]》至自然结束。正片期间没有发送暂停、跳转、倍速或后台操作。

| 检查 | 结果 |
| --- | --- |
| 播放清单时长 | 600.016 秒 |
| 从首次播放到自然结束的墙钟时间 | 600.111 秒 |
| 结束位置及实际观看累计 | 均为 600.028 秒 |
| 官方广告实际累计 | 30.041 秒 |
| 官方进度 `ok` 响应 | 12 次，包含自然结束后的最终回报 |
| 视频解码尺寸 | 640×360 |
| 11 次应用进程内存采样 | PSS 64,269～66,263 KiB，约 62.8～64.7 MiB |
| 各次采样的 WebViews／结束时 WebView 渲染进程 | 均为 0 |
| 正片首次播放后的缓冲状态切换 | 0 次 |
| 当前进程崩溃缓冲中的 fatal 记录 | 0 次 |

证据位于 `artifacts/performance/native-api/full-episode-40190-final/`，包含 `summary.json`、`continuous-samples.json`、`continuous-events.json`、`memory-samples.json`、`final-session.json` 和 `natural-end.png`。自然片尾画面保留了模拟器软键盘，此次结果仅用于播放链路与连续运行验收；没有听音或人工评价音画同步。内存数据是离散采样，不是峰值或与正式版的对照。

准备整集测试时发现：30.03 秒广告自然结束，旧计时器仅累计 29.40 秒；只补开始／结束采样后仍累计 29.82 秒。最终实现增加开始和结束采样，并在每段连续播放内累计媒体与墙钟增量后再取较小值，避免逐次取较小值持续损失时钟抖动。暂停、缓冲和跳转会分隔累计段，真实不足 30 秒的广告仍会停止。新增测试分别覆盖自然结束最后一小段、29.9 秒短广告拒绝，以及时钟抖动、墙钟上限、暂停和跳转排除。两次失败的启动证据分别保留在 `full-episode-40190/` 与 `full-episode-40190-retest/`。

## 24 分钟整集连续播放

酥鱼随后要求测试完整 24 分钟。使用同一最新 APK，重新启动隔离访客实例，选择官方公开资料标注为 24 分钟、访客权限通过的《Lycoris Recoil 莉可麗絲 [1]》，影片编号 `30008`。实际清单为 1,442.024 秒，超过脚本要求的 1,440 秒最低时长。正片从片头以 1 倍速播放到自然结束，期间没有操作暂停、跳转、倍速或后台切换。

| 检查 | 结果 |
| --- | --- |
| 播放清单时长 | 1,442.024 秒，即 24 分 2.024 秒 |
| 从首次播放到自然结束的墙钟时间 | 1,442.292 秒，即 24 分 2.292 秒 |
| 结束位置及实际观看累计 | 均为 1,442.026 秒 |
| 官方广告实际累计 | 30.039 秒 |
| 官方进度 `ok` 响应 | 26 次，包含自然结束后的最终回报 |
| 视频解码尺寸 | 640×360 |
| 25 次应用进程内存采样 | PSS 62,384～68,394 KiB，约 60.9～66.8 MiB |
| 各次采样的 WebViews／结束时 WebView 渲染进程 | 均为 0 |
| 正片首次播放后的缓冲状态切换 | 0 次 |
| 当前进程崩溃缓冲中的 fatal 记录 | 0 次 |

运行结果于 2026-11-13 21:33:16（Asia/Taipei）保存，证据位于 `artifacts/performance/native-api/full-episode-30008-24min/`。`continuous-samples.json` 记录媒体位置、观看累计和观察端墙钟；`continuous-events.json` 保存完整脱敏事件，`summary.json` 明确记录最低时长、1 倍速、未使用跳转、自然结束及最终进度被接受。另保存每分钟内存采样、开始和结束截图、最终会话状态及清理结果。

这次结果确认了该访客样本的完整 24 分钟播放链路。缓冲统计来自播放器状态事件，不能替代逐帧掉帧统计或主观音画同步评价；账号历史和目标 ARM 电视仍按后续验收范围处理。

## Cookie 兼容修正

官方设备接口返回的访客 `ANIME_SIGN` 使用 `Domain=gamer.com.tw`，另一访客 Cookie 使用 `.gamer.com.tw`。本次 Android 上的旧 `java.net.CookieManager` 接收规则遗漏了前者，导致官方策略返回错误 1007。另用独立宿主访客会话比较，Cookie 值带引号发送也会产生 1007。

[`GuestCookieJar`](../app/src/nativeApi/java/app/bahaplus/tv/experiment/GuestCookieJar.kt) 为两个固定官方 HTTPS 来源保存浏览器格式的 Cookie，支持父域、host-only、路径、Secure、替换与过期删除，发送时不加旧式 Cookie 版本语法。它不是通用浏览器 Cookie 库，未实现完整公共后缀规则，不用于导入账号 Cookie。日志与证据只记录数量和是否存在所需名称。

## 构建与复测

准备项目既有 Android SDK 与 Gradle 缓存后，执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build-NativeApi.ps1 -Offline
```

脚本执行 `testNativeApiUnitTest lintNativeApi assembleNativeApi`，复制 APK 并生成同名 `.sha256` 文件。整集测试前的最新构建中，51 项 JVM 测试全部通过，其中 10 项为新增的协议、Cookie 与计时测试；Lint 为 0 错误、12 项警告。最新包的 APK v2 签名和 16 KiB 对齐检查通过，先前已核对独立包名与启动入口。

上述整集测试 APK 来自保留原有 WebView 兼容性改动的本地工作区，该工作区的构建还需 Node.js 依赖。独立实验分支不包含这些兼容性改动，因此不需要 Node.js；纯净分支构建与上述实测 APK 的校验值不能混用。

推送前另从 `2f3777c` 导出纯净副本，仅加入上述原生实验文件及构建类型，执行相同的 Gradle 检查与打包任务，44 项 JVM 测试全部通过，其中 10 项为实验测试；Lint 为 0 错误、12 项警告，APK 构建成功。此副本未重复整集实播，24 分钟结果仍对应上表记录的实测 APK。

运行测试前，使用新的空白 API 29 x86 AVD，名称设为 `BahaNativeApiGuest_API29`。将它置于单独的 `ANDROID_AVD_HOME`，使用独立的 `ANDROID_USER_HOME`／`ANDROID_EMULATOR_HOME`，以 `-ports 5592,5593 -no-window -no-audio -no-snapshot -gpu swiftshader` 启动，并将 `ANDROID_ADB_SERVER_PORT` 设为 5071。不可从已有账号实例复制 userdata，也不可借用其端口。

```powershell
python -X utf8 tools/test-native-api-emulator.py --install
python -X utf8 tools/test-native-api-emulator.py --install --video 40191 --output-name episode-40191
python -B -X utf8 tools/test-native-api-emulator.py --install --video 40190 --full-episode --output-name full-episode-40190-final
python -B -X utf8 tools/test-native-api-emulator.py --install --video 30008 --full-episode --minimum-duration-seconds 1440 --output-name full-episode-30008-24min
```

[`test-native-api-emulator.py`](../tools/test-native-api-emulator.py) 固定核对上述 AVD 名称、端口及启动状态，随后才安装和操作独立包。所有界面动作均使用遥控按键。脚本读取包内已脱敏的证据，校验正片推进、暂停、快进、倍速、后台状态、后续定时进度回报、运行时 WebView 数量和崩溃计数。

`--full-episode` 使用单独的连续播放路径：从片头以 1 倍速播放，正片期间不发送控制按键；核对每次采样均无跳转或倍速变化，等待自然结束及结束后的进度 `ok`。脚本同时检查媒体时间、实际观看累计与墙钟时间，避免将快进或加速播放算成整集通过。运行期间保存连续状态、脱敏事件和每分钟一次的应用进程内存采样。`--minimum-duration-seconds 1440` 另要求实际清单达到 24 分钟，避免误选短篇。

本机证据保存在 `artifacts/performance/native-api/`，第二集位于其 `episode-40191/` 子目录：

- `environment.json`：系统、WebView、ABI 和 APK 校验值。
- `content-entry.json`、`pause.json`、`seek.json`、`speed.json`、`background.json`：各阶段状态。
- `final-session.json`、`summary.json`：脱敏事件与验收结果。
- `real-advertisement.png`、`native-content.png`、`native-final.png`：实际广告与正片截图。
- `cookie-comparison.json`：独立宿主访客的 Cookie 格式比较，仅含名称和结果。
- `memory-sample.json`、`cleanup.json`：最后一次内存采样及代理清除、实例关机结果。

这些本机产物被 Git 忽略，未包含 Cookie、设备标识、带会话的媒体 URL、原始网络响应或 logcat。构建测试报告位于 `app/build/test-results/testNativeApiUnitTest/` 与 `app/build/reports/lint-results-nativeApi.html`。

本次已强制停止实验包，将测试实例代理清除并回读为 `:0`，随后正常关机并确认其控制台端口关闭。复测结束后同样执行：

```powershell
& .\.tools\android-sdk\platform-tools\adb.exe -P 5071 -s emulator-5592 shell am force-stop app.bahaplus.tv.nativeapi
& .\.tools\android-sdk\platform-tools\adb.exe -P 5071 -s emulator-5592 shell settings put global http_proxy :0
& .\.tools\android-sdk\platform-tools\adb.exe -P 5071 -s emulator-5592 shell settings get global http_proxy
& .\.tools\android-sdk\platform-tools\adb.exe -P 5071 -s emulator-5592 shell reboot -p
```

## 后续验收范围

接入正常应用前，仍需完成登录会话及官方历史的双向核对、服务端设备标识轮换和并发拒绝测试、账号失效处理、完整广告调度、年龄与家长验证交互，以及目标 32 位 ARM 电视的整集验收。当前只从官方广告配置选择视频广告，未覆盖 IMA、rewarded、native 和 PAD，也未接入主应用的目录、选集与自动下一集。

移除官网播放器 JavaScript 依赖的访客路径已经实测成立。登录、验证码及其他官方要求的验证可以继续保留必要的官方 WebView；正式播放会话需要在上述验收后再逐步接入。

## 官方协议来源

本实现依据 2026-11-13 取得的官方网页播放器静态源码，未声称已验证当前官方 Android App 的私有协议：

- `https://i2.bahamut.com.tw/build/js/anime_player.js`，HTTP 200，Last-Modified 为 2026-10-15，SHA-256 为 `808699c5419999781f1b6833184551e5ee4ded53cac017521792b1af9b63d9d5`。
- `https://i2.bahamut.com.tw/JS/ad/animeVideo.js`，运行时读取当前公开视频广告配置。
- 影片及取源：`api.gamer.com.tw/anime/v1/video.php`、`video_src.php`。
- 会话与进度：`ani.gamer.com.tw/ajax/getdeviceid.php`、`token.php`、`checklock.php`、`unlock.php`、`elapse.php`。
- 广告事件：`ani.gamer.com.tw/ajax/videoCastcishu.php`。

这些是官方网页客户端使用的业务接口，未找到面向本项目的公开稳定性承诺，服务端或广告配置变化时实验会停止并报告阶段错误。
