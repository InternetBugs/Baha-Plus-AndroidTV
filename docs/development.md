# 开发与构建

本文介绍本地构建、检查与发版入口，并汇总实现与验证资料。安装和日常使用请先阅读 [README](../README.md)。

## 环境要求

- JDK 17 或 21。
- Android SDK Platform 36 与 Build Tools 35.0.0。
- Node.js 18 或更高版本，用于构建与测试登录适配脚本。
- Python 3，用于项目资源检查。

Gradle Wrapper 固定为 8.14.3，并校验分发包。应用最低系统为 Android 7.0／API 24，使用 Kotlin、原生 View／RecyclerView 与 Media3。

## 配置 Android SDK

已有 SDK 时，在项目根目录新建不提交的 `local.properties`，根据实际安装位置填写：

```properties
sdk.dir=D\:/Android/Sdk
```

需要下载项目专用 SDK 时，先审阅 [初始化脚本](../tools/Bootstrap-Android.ps1)，再在项目根目录执行：

```powershell
.\tools\Bootstrap-Android.ps1
```

脚本从 Google 官方源下载并校验命令行工具，在 `.tools/android-sdk` 下安装编译所需组件，并在终端提示 SDK 许可。明确接受这些许可后，可使用 `-AcceptSdkLicenses` 自动回答。脚本不修改系统 PATH，不创建系统任务。

## 本地构建

在项目根目录运行 Windows PowerShell：

```powershell
npm ci --ignore-scripts
.\tools\Build.ps1
```

构建脚本执行 `testDebugUnitTest lintDebug assembleDebug`，将调试 APK 与 SHA-256 复制到 `artifacts/`。优先使用已有 Gradle 分发包；构建缓存和 Android 用户目录放在项目 `.tools/` 下。

依赖与工具已缓存时，可以使用：

```powershell
.\tools\Build.ps1 -Offline
```

也可以使用标准 Wrapper：

```powershell
npm ci --ignore-scripts
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

标准 Wrapper 的 APK 输出位于 `app/build/outputs/apk/debug/`。本地调试包与正式签名包不同，不能直接互相覆盖安装。

## 适配脚本与离线检查

登录适配器源码位于 `app/src/main/js/adapters/`。Gradle 使用锁定版本的 esbuild 按 Chrome／WebView 74 编译，检查 ECMAScript 2019 语法，再打包到 APK 的 `assets/adapters/`。

生成文件位于 `app/build/generated/webviewAssets/`，不需要手工修改或提交。安装 npm 构建依赖后可离线编译；DOM API 兼容性和官方授权流程仍需在实际 WebView 上测试。

```powershell
npm ci --ignore-scripts
npm test
python tools/check-project.py
```

`npm test` 分别运行源码与 WebView 74 编译产物的适配器回归测试。JVM 测试位于 `app/src/test`，通过对应 Gradle 单元测试任务执行。

## 实现与验证范围

普通入口的首页、搜索、详情、账号资料、追番、历史与播放使用原生 HTTP。WebView 仅在扫码登录时创建，用于承接官方授权会话，关闭登录界面后销毁；登出清理官方浏览器会话。请求与验证边界见 [原生 HTTP 迁移](native-http-migration.md)。

登录页面按实际内核语法能力决定是否启用兼容转换；支持时直接加载，不创建编译器。旧内核兼容层仅处理官方来源的外部静态 JavaScript，不参与原生播放。早期实现和验证见 [WebView 语法兼容层](webview-compatibility.md)。

低资源模式默认开启，限制海报尺寸、缓存、预加载和播放器缓冲，资源预算与视频画质分开设置。预算不等于实测内存或帧率，系统 WebView、解码器和图形缓冲另有开销，详见 [性能验证](performance-validation.md)。

项目已记录 Android 构建、单元测试、Lint、APK 检查及独立模拟器中的浏览、扫码和播放验证。历史记录对应各自的代码版本；迁移前的测试不能代替迁移后的验收。目标 ARM 电视上的硬件解码、长时间会话、整集播放与跨设备同步仍需继续验证，不能从模拟器结果推断所有电视均可稳定播放。

当前没有配置仅限 64 位的 ABI 过滤；若新增原生库，应同时检查目标 32 位设备所需 ABI 的打包情况。

## GitHub Actions 发版

工作流为 [release.yml](../.github/workflows/release.yml)，在 Actions 中显示为 **Android release**。推送 `v*` 标签会自动发版；也可在 Actions 页面选择 `main` 手动运行。手动运行默认只构建，开启 `publish` 才发布到 GitHub Releases。

流程执行测试、资源检查、Lint、Release 构建、固定密钥签名、验签、对齐检查和 SHA-256 校验。签名配置、版本号及发布说明的操作见 [发版说明](releasing.md)。

## 技术资料索引

| 资料 | 内容 |
| --- | --- |
| [实现规格](specification.md) | 已确认的产品范围与电视交互要求 |
| [验收清单](acceptance.md) | 安装、遥控器、账号、播放与性能的真机检查项 |
| [扫码登录](qr-login.md) | 官方手机授权流程与验证记录 |
| [原生详情与选集](native-details.md) | 详情资料、影片 ID 与选集请求 |
| [原生 HTTP 迁移](native-http-migration.md) | 当前请求路径、会话处理与迁移后的验证 |
| [原生播放实验](native-api-experiment.md) | 播放协议实验的历史记录 |
| [日志与反馈](diagnostics.md) | 采集、隐私、手机取日志与测试范围 |
| [WebView 语法兼容层](webview-compatibility.md) | 旧内核兼容机制与历史验证 |
| [构建验证](build-validation.md) | APK、签名、对齐及构建检查记录 |
| [模拟器验证](emulator-validation.md) | 遥控器、浏览、账号与播放的历史测试 |
| [性能验证](performance-validation.md) | 隔离模拟器采样、优化与未覆盖项 |
| [发版说明](releasing.md) | 云端构建、签名与发布步骤 |

开源许可见 [LICENSE](../LICENSE)，来源和第三方依赖声明见 [NOTICE](../NOTICE)。
