# 独立模拟器性能验证与优化

执行日期：2026-11-13，Asia/Taipei。测试基于当前工作区调试包，包含测试开始前已有的播放缓存进度条改动，不将此前发布附件作为比较基线。

本轮确认了低资源模式列表的 CPU 与绘制负载下降，以及两种模式的封面内存下降。启动速度和通常模式的滚动帧耗时没有得到改善，不能将结果表述为所有性能指标均提升。目标 ARM 电视、会员高画质和整集播放仍需另行验收。

## 隔离环境

| 项目 | 实际配置 |
| --- | --- |
| AVD | 全新 `BahaPlusTV_Perf_20261113` |
| 数据目录 | `.tools/avd-performance/`，只复用设备配置与只读系统镜像，没有复制旧 AVD 的 userdata、快照或应用数据 |
| ADB | 独立服务端口 `5041`，只连接 `127.0.0.1:5571`；每次操作核对 AVD 名称 |
| 系统 | Android TV 11／API 30，32 位 x86 |
| 资源 | 2 个虚拟核心、1536 MiB RAM、1920×1080、density 320 |
| 图形 | SwiftShader 软件渲染、WHPX，无窗口、无宿主音频、无快照 |
| WebView | `com.google.android.webview` 90.0.4430.91 |
| 会话 | 新建访客会话，未登录用户试用账号 |
| 内容 | 官方首页，以及《冰劍的魔術師將要統一世界 2》第 1 集，时长 24:15 |
| 网络 | 只在该 AVD 内设置 `10.0.2.2:7890`，不修改宿主代理 |

截图、内存及 CPU 采样、帧统计保存在本机 `artifacts/performance/`，已从 Git 排除。没有保存 Cookie、账号凭据、媒体 URL 或原始 logcat。

| APK | SHA-256 |
| --- | --- |
| 优化前，11,189,589 字节 | `618fa43ac330852be0776ed70cdb38040f375d6e20b46d3f84adc365637471ff` |
| 优化后，11,189,589 字节 | `4f314e85837d63bcd7f4adbe97734bdb90c99ba340ba529b52093935fee5d111` |

## 测量口径

首页在进程冷启动及缓存预热后采样约 40 秒。列表使用相同的 48 次方向键操作：下移 6 次、上移 6 次，重复 4 轮；每次按键后等待 200 ms，另含 ADB 调用耗时。没有在有效比较采样期间并行编译或启动其他应用测试。

CPU 由 `/proc/PID/stat` 的时间增量计算，100% 表示占满一个虚拟核心，不是占满整台双核设备。内存采用 PSS，分别统计应用、该独立 AVD 上的全部 WebView 渲染进程及共享媒体进程。共享媒体进程不能全部归因于应用；PSS 也不覆盖全部宿主 GPU、模拟器与解码缓冲开销。

`gfxinfo` 只用于界面绘制，不能用作视频解码帧率或掉帧统计。静止首页没有新帧时，不报告帧耗时。重启打断的滚动采样、已经退出播放页的采样及自动化返回键导致离开详情的未完成循环均不纳入有效比较。

## 首页与列表

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 低资源首页主进程 PSS 中位数 | 104.62 MiB | 99.56 MiB |
| 通常模式首页主进程 PSS 中位数 | 122.32 MiB | 103.85 MiB |
| 低资源列表操作后主进程 PSS | 109.30 MiB | 101.65 MiB |
| 通常模式列表操作后主进程 PSS | 179.54 MiB | 114.79 MiB |
| 低资源列表主进程平均 CPU，单核口径 | 16.79% | 4.63% |
| 低资源列表 UI 绘制帧数 | 756 | 49 |
| 低资源列表 P50／P95 帧耗时 | 14／40 ms | 9／25 ms |
| 低资源列表 janky 帧数／总帧数 | 359／756 | 11／49 |
| 低资源列表 P99 帧耗时 | 48 ms | 105 ms |
| 通常模式列表 P50／P95 帧耗时 | 8／26 ms | 22／48 ms |

低资源模式绘制帧数减少约 93.5%，主进程平均 CPU 减少约 72.4%，主要来自取消跨行焦点平滑滚动。通常模式保留平滑滚动，未测到帧耗时改善，仍有明显绘制开销。低资源模式的 P99 也未改善；优化后的帧样本数量较少，不能只依据 P95 声称没有卡顿。

通常模式滚动后的主进程 PSS 减少约 64.75 MiB，即约 36.1%。官网内容、WebView 回收与缓存状态会引起波动；这里记录的是本次相同操作序列的观测，不是所有电视的资源保证。

原始证据：`baseline-low-home-warm`、`optimized-low-home`、`baseline-normal-home`、`optimized-normal-home`、`baseline-low-scroll-warm`、`optimized-low-scroll`、`baseline-normal-scroll`、`optimized-normal-scroll` 对应的 JSON 与 gfx 文本。

## 启动

| 模式 | 优化前 Activity 启动中位数 | 优化后 Activity 启动中位数 |
| --- | ---: | ---: |
| 低资源，5 次 | 729 ms | 1044 ms |
| 通常，3 次 | 719 ms | 850 ms |

每次先 `am force-stop`，保留磁盘及 WebView 缓存，启动后等待 6 秒。此项出现时间增加，本轮不宣称启动优化成功。它不是官方内容首屏时间，也没有替代发布包 Macrobenchmark；调试包安装、运行时状态及模拟器调度的影响尚未单独分离。

## 播放与回归

优化前确认原生播放后连续采样 90 秒：主进程 PSS 中位数 115.57 MiB、WebView 渲染进程 95.14 MiB，共享媒体进程 26.58 MiB；CPU 分别为单核的 9.53%、0.75%、5.30%。这些进程需分别观察，不能只用主进程内存代表总开销。

优化后访客播放、重复进出与后台暂停结果在本轮最终检查完成后填入本节。

详情返回列表已经回归：前后选中的同一张封面边界均为 `[354,191][715,577]`，见 `optimized-normal-home-after-scroll.json` 与 `optimized-focus-restored.json`。

## 实施的优化

1. 封面解码按真实长边上限采样，低资源为 320 像素、通常为 512 像素；修正此前可达到两倍上限的采样条件。常见 640×360 图片在低资源模式下解码为 320×180，像素量减少 75%。
2. 页面重建前回收旧列表卡片，取消其待处理封面请求；删除进出播放及隐藏官方页面时的重复界面重建。
3. 一次预计算封面所在列，缓存网格跨度索引，避免绑定卡片和左键操作时反复从列表头扫描。
4. 低资源模式的遥控焦点滚动直接定位；通常模式保留平滑滚动。
5. 播放控制条隐藏时跳过进度界面刷新；可见时只在秒数变化后重建时间文字，缓存进度仍更新。官方实际播放时间、暂停与授权确认继续独立回传。
6. 旧 WebView 的消息轮询改为按需调度，空闲、后台、导航中及原生接管后停止；更旧 WebView 提供程序的完整兼容矩阵尚未覆盖。

构建 `testDebugUnitTest lintDebug assembleDebug` 通过，7 项 JVM 测试、33 项 JavaScript 适配器测试及 XML／JSON／JavaScript／许可证／Wrapper 检查通过。新增采样预算测试覆盖横图、竖图、奇数边长、极端比例与无效尺寸。

## 复现

必须新建测试 AVD，不能复制含登录状态的 userdata 或快照。示例命令中的实例名、串号及 ADB 服务端口应与自己新建的隔离实例一致；身份不匹配时采集工具会拒绝操作。

```powershell
python tools/performance-probe.py --serial 127.0.0.1:5571 --adb-port 5041 --expected-avd BahaPlusTV_Perf_20261113 info environment
python tools/performance-probe.py --serial 127.0.0.1:5571 --adb-port 5041 --expected-avd BahaPlusTV_Perf_20261113 startups sample-startup --count 5
python tools/performance-probe.py --serial 127.0.0.1:5571 --adb-port 5041 --expected-avd BahaPlusTV_Perf_20261113 measure sample-home --seconds 40 --interval 10
```

手动用遥控方向键将焦点放到首页第一张封面后：

```powershell
python tools/performance-probe.py --serial 127.0.0.1:5571 --adb-port 5041 --expected-avd BahaPlusTV_Perf_20261113 scroll sample-scroll
python tools/performance-probe.py --serial 127.0.0.1:5571 --adb-port 5041 --expected-avd BahaPlusTV_Perf_20261113 capture sample-result
python tools/performance-probe.py --serial 127.0.0.1:5571 --adb-port 5041 --expected-avd BahaPlusTV_Perf_20261113 errors sample-errors
```

播放采样必须先看到原生控制条并确认时间推进；不要在控制条已自动隐藏时发送返回键后继续采样，否则可能测到已经退出的详情页。
