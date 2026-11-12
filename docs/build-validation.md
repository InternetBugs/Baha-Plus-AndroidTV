# 本地构建验证

构建日期：2026-11-12；模拟器测试跨至 2026-11-13，Asia/Taipei。

## 产物

- 文件：`artifacts/BahaPlusTV-0.1.0-debug.apk`
- 大小：6,452,064 字节，约 6.15 MiB。
- 应用 ID：`app.bahaplus.tv`
- 版本：0.1.0，versionCode 1。
- 最低 API：24；目标 API：36。
- SHA-256：`631257a3b2ac85181ef40199aa571e5ec0c6c1629b1e2d04a558d558ddd62836`
- 签名：Android 调试证书，APK Signature Scheme v2 验证通过；用于本地侧载测试。

## 已执行

`tools/Build.ps1` 已成功运行 `testDebugUnitTest lintDebug assembleDebug`。

| 检查 | 结果 |
| --- | --- |
| Kotlin 与 Android 资源编译 | 通过 |
| JVM 模型单元测试 | 4 项通过，0 失败、0 跳过 |
| 官方适配器离线测试 | 12 项通过 |
| XML、JSON、JavaScript、Wrapper 与许可证检查 | 通过 |
| Android Lint | 0 错误、6 警告 |
| `apksigner verify --verbose --print-certs` | 通过 |
| `zipalign -c -P 16 4` | 通过 |
| `aapt dump badging` | 包名、版本、API 24／36、普通及 Leanback 启动入口符合配置 |
| APK 原生库检查 | 没有 `lib/**/*.so`，没有仅提供 arm64 的库 |
| APK 许可证检查 | 包含 MIT、NOTICE 与 Apache 2.0 文本 |
| ADB 安装与运行 | 已安装到本机 Android TV API 30／32 位 x86 模拟器并测试；没有连接目标电视 |

APK 中不包含原生库，说明应用没有通过打包排除 32 位 ARM 运行环境；实际系统 WebView、硬件解码与设备兼容性仍需真机确认。

## 尚存的 Lint 提示

- `UnusedAttribute`：新版返回回调开关仅在 API 33 及以上使用；旧版保留遥控按键与旧版系统回调。
- `VectorRaster`：电视启动横幅为 320×180 的矢量资源，需检查目标电视桌面的实际显示效果。
- 三项版本更新提示：Gradle、WebKit 与仅用于单元测试的 JSON 依赖。当前构建保留已固定的版本。
- `DiscouragedApi`：电视界面声明横屏，新版 Android 在部分大屏设备上可能忽略固定方向。

旧版 `onBackPressed` 的 `GestureBackNavigation` 检查在该方法上做了局部抑制：API 33 及以上已经注册平台 `OnBackInvokedDispatcher`，旧版回调只用于兼容。平台返回与遥控返回均调用同一套播放控制条关闭逻辑。没有关闭整个 Lint 检查或新增基线来绕过错误。

## 真机边界

已完成的模拟器安装、启动、官方广告与原生播放测试见 [模拟器测试记录](emulator-validation.md)。本记录不代表目标电视验收通过。尚未验证 TCL 55Q9K 上的遥控输入、官方验证码、广告、原生接管、官方续播与追番写回，也未测量该电视的实际内存、启动时间、掉帧或音画同步。

按 [TV 安装与验收](acceptance.md) 连接目标电视后再记录实际结果。

## 2026-11-13 扫码登录增量构建

接入官方手机授权流程并加入 ZXing Core 3.5.4 后，本地调试 APK 已更新。版本仍为 0.1.0，大小为 11,189,144 字节，SHA-256 为 `42f750e6a6853828e0abed15e7b17792f7ba1438212615e88acc2296b087512a`。前文的旧 APK 大小和哈希保留为当时的构建记录。

本轮 `testDebugUnitTest lintDebug assembleDebug` 成功，5 项 JVM 测试及 20 项 Node.js 测试通过，Lint 为 0 错误、6 项既有警告；APK v2 签名验证与 `zipalign -c -P 16 4` 均通过。最终离线构建中 Kotlin daemon 因沙箱不能写入用户目录而自动回退到不使用 daemon 的编译方式，最终构建成功。

2026-11-13，酥鱼在修复网络后的交互模拟器中测试并反馈“账号功能完全正常”。这是用户的账号验收反馈，不代表目标电视或每个账号子功能已分别取得独立测试证据。

本次用户测试的同一 APK 以 `v0.1.0-qr.1` 标签发布，应用内版本仍为 0.1.0／versionCode 1；详见 [发布说明](release-notes-0.1.0-qr.1.md)。扫码协议、真实浏览器验证及模拟器网络处理见 [扫码登录记录](qr-login.md)。
