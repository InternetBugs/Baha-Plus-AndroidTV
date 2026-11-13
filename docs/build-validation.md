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

## 2026-11-13 WebView 83 选集兼容性修复

用户转交的 `BahaPlusTV-log.txt` 显示：应用 0.3.0／versionCode 3，Android 11／API 30，设备标识 `tcl_mt5879_cn`，ABI 为 `armeabi-v7a, armeabi`，WebView 为 `83.0.4103.120`。日志中 `ui.detail` 后约 1.5 秒返回 `official.detail-error`，截图中未出现选集列表。此前模拟器使用 WebView 90，未覆盖这台反馈设备的引擎版本。

详情和播放器的评分人数解析无条件调用 `String.replaceAll()`；该方法在 Chrome／WebView 85 起提供。因而即使页面未提供评分，WebView 83 也会在这一行抛出异常，中断整份详情及选集的提交。离线测试关闭此 API 后，原代码分别复现 `detail-error` 和播放器的 `TypeError`。反馈日志未保留异常栈，原电视仍需安装修复版本回归。

本次更改：

- 详情和播放器使用全局正则替换评分人数中的逗号。
- 原生接管脚本改用普通空值判断，移除 WebView 83 无法解析的 `??=`。
- 使用 `textContent` 和 `append` 清理 DOM，替换较新的 `replaceChildren()`，保留原有官网节点与事件监听器。
- 播放区域改用 `top/right/bottom/left` 定位，避免依赖较新的 `inset` 简写。
- 新增详情解析回归测试，并补充重复暂停接管时的播放状态测试。现有接管测试的 DOM 不提供 `replaceChildren()`。

验证结果：

| 检查 | 结果 |
| --- | --- |
| Node.js 离线测试 | 38 项通过，0 失败 |
| 全部 13 份适配器的 ECMAScript 2020 解析检查 | 通过，无逻辑赋值新语法 |
| XML、JSON、JavaScript 语法、TV 声明、许可证及 Wrapper 校验 | 通过 |
| `tools/Build.ps1 -Offline` | `testDebugUnitTest lintDebug assembleDebug` 成功 |
| JVM 单元测试 | 6 组、34 项通过，0 失败、0 跳过 |
| Android Lint | 0 错误、6 项警告 |
| APK 中适配器与工作区源码逐字节比较 | 三份修改后的适配器均匹配 |
| 独立浏览器真实 DOM 回归 | 通过；Headless Edge 157 中关闭 `replaceAll`／`replaceChildren`，验证选集、元数据、接管清理、节点及监听器保留、官方进度回调与授权撤销 |

浏览器验证只使用本地合成的官方结构页面，不读取账号或访问真实媒体。它模拟缺少 API 的环境，并非实际运行 WebView 83。证据保留于本机 `.tools/webview83-dom-result.html`；脚本与独立浏览器配置也位于忽略的 `.tools/` 中。

本次本地调试产物为 `artifacts/BahaPlusTV-0.3.0-debug.apk`，大小 6,473,428 字节，SHA-256 为 `96001f05b9b12d02fe0a8f0efe433b86e74fb4134f3ee98ef6deaa60ae0d2d18`。版本号未变；本次没有推送或发布正式升级包。Kotlin daemon 在沙箱内写入用户目录受限后自动使用回退编译，最终全部构建检查成功。

尚待原反馈电视验证：《黑色五葉草》详情及选集、年龄确认与广告、原生接管、选集切换和官网进度同步。

## 2026-11-13 WebView 74 兼容构建

根据用户要求，进一步在 AOSP API 29／x86 独立访客模拟器上测试实际 WebView 74.0.3729.185。发现所有适配器使用的可选链、部分空值合并以及 Kotlin 内嵌脚本无法由该引擎解析。

适配器源码移至 `app/src/main/js/adapters/`，构建时以锁定的 esbuild 0.25.12 编译为 Chrome 74 语法，并以 Acorn 8.15.0 校验 ECMAScript 2019。Gradle 在 `preBuild` 前生成 `app/build/generated/webviewAssets/adapters/`，APK 中仍使用 `assets/adapters/` 路径。Kotlin 内嵌脚本改为显式空值判断。首次构建需要 Node.js 18+ 与 `npm ci --ignore-scripts`；Actions 已加入该安装步骤和源码／编译产物双份回归，后续可离线编译。

另增加官网主播放器语法错误识别及原生提示。实际 74 上的首页、搜索、《黑色五葉草》170 集详情、滚动到最后一集和返回焦点通过；官网原始播放器脚本本身仍不能由 74 解析，第 1、2 集打开后显示更新 WebView 的提示，**没有通过 74 正片播放测试**。具体边界及证据见 [模拟器记录](emulator-validation.md)。

| 检查 | 最终结果 |
| --- | --- |
| Node.js 适配器回归 | 源码 38 项＋编译产物 38 项，全部通过，共 76 次测试执行 |
| 实际 WebView 74 语法验证 | 13 份编译产物全部解析通过；13 份原始源码均失败 |
| Android 构建 | `testDebugUnitTest lintDebug assembleDebug` 成功 |
| JVM 单元测试 | 7 组、37 项通过，0 失败、0 错误 |
| Android Lint | 0 错误、6 项既有警告 |
| 打包脚本核验 | 13 份编译脚本齐全，与构建输出逐字节一致 |
| APK 签名及对齐 | v2 签名验证与 `zipalign -c -P 16 4` 通过 |
| 项目 XML／JSON／JavaScript／许可证／Wrapper 检查 | 通过 |

最终 APK 仍为 `artifacts/BahaPlusTV-0.3.0-debug.apk`／versionCode 3，大小 6,537,543 字节，SHA-256 为 `ca147b77cf5c1c082dd2d4145e970324a9145eb9881d7284adcf3bf206ee312d`。它替换本机之前的调试产物；本文前面的哈希保留为各阶段记录。最终构建使用进程内 Kotlin 编译，避免了该环境的 daemon 用户目录写入限制。本轮没有推送、发版或改变版本号。

## 2026-11-13 按内核语法启用官方脚本转换

新增客户端兼容层，实际语法探测失败时才创建本地 Babel 编译 WebView，正常内核保留原加载路径。官方来源的外部静态 JavaScript 统一按资源规则处理，不维护逐个版本或脚本文件名单。源码内容、编译器修订与实际内核目标共同决定缓存键。83 回归发现的 `String.replaceAll` 缺失由单独打包的 core-js 补丁处理；只在旧内核的转换响应中、且方法不存在时安装。实现范围见 [语法兼容层](webview-compatibility.md)。

最终调试包：`artifacts/BahaPlusTV-0.3.0-debug.apk`，7,404,245 字节，SHA-256 为 `f37d0b5bcfcc6ac789ac79b6f7143cee687e3fa24c41acaf17e4de9813c2166a`。版本仍为 0.3.0／3，本轮没有发布到 GitHub。

最终编译器修订为 `babel-7.28.5-corejs-3.47.0-v4`：API 补丁经 AST 插入在程序指令之后，保留原始严格／非严格语义，实际设备的解析检查覆盖完整响应。新增测试验证严格指令、自定义指令、非严格脚本、模块导入及补丁的先行安装。此前 v3 中间包与其运行证据在模拟器记录中明确区分。

| 检查 | 实际结果 |
| --- | --- |
| Node.js 回归 | 源码 46 项＋编译产物 46 项，92 次执行，全部通过 |
| Debug JVM | 8 个套件、41 项，全部通过 |
| Release JVM | 8 个套件、41 项，全部通过 |
| Debug Lint | 0 错误、6 项警告 |
| Release Lint | 0 错误、3 项警告 |
| Debug 与 Release APK 构建 | 通过，Release 的 R8 代码及资源压缩通过 |
| R8 的本地编译接口 | `polyfills`、`ready`、`input`、`output`、`finish` 方法名保留 |
| APK 生成资源比较 | 19 个文件与生成目录逐字节一致，包括适配器、编译器、API 补丁与许可证 |
| 编译器和 API 补丁语法 | Babel、compiler.js、polyfills.js 的 ECMAScript 2019 检查通过 |
| APK 签名与对齐 | 调试包 v2 签名验证、16 KiB 对齐检查通过 |
| ABI 范围 | APK 没有原生 `.so`，未引入仅支持 arm64 的替代引擎 |
| 项目资源检查 | XML／JSON／JavaScript、TV 清单、MIT／Apache／Babel／core-js 许可与 Wrapper 校验通过 |

对应本机摘要为 `artifacts/performance/compat-v4-build.json`。最终回归中两台访客 AVD 的已安装 APK SHA-256 均与上述产物一致，见 `compat83-v4-installed.json`、`compat90-v4-installed.json` 和 [模拟器验证](emulator-validation.md)；Release 记录属于本地构建与压缩检查，正式签名发版仍应使用既有 GitHub Actions 流程。
