# 原生详情与选集

实现及测试日期：2026-11-13，Asia/Taipei。

本文记录详情与选集迁移阶段的实现和当时产物。随后同日已将其余非登录功能迁移到原生 HTTP，当前架构与产物以 [原生 HTTP 迁移](native-http-migration.md) 为准。

## 资料路径

参照本地 Baha-Plus 的 `app/lib/data/official_app_api.dart`，正式入口的作品简介、封面、制作资料和完整选集改为 Kotlin `HttpURLConnection` 读取官方公开 API：

- 有影片 ID：`https://api.gamer.com.tw/mobile_app/anime/v4/video.php?sn=…`。
- 只有作品 ID：先请求 `https://api.gamer.com.tw/anime/v1/search.php?kw=…`，按作品 ID 核对搜索结果并取得影片 ID，再读取 `video.php`。不会仅按片名或第一条结果选片。
- 从官方观看历史续播时优先使用该条历史的影片 ID。

客户端核对响应中的作品 ID、影片 ID 和选集归属，按官方分组顺序合并集数；拒绝重复影片 ID、缺少请求影片、无效图片来源及超过 5,000 条的集数。只映射公开资料，不读取或保存收藏、断点与账号状态。仅有评分时显示评分，不生成缺失的评分人数或分布。

详情请求不读取 WebView Cookie，也不导入或持久化响应 Cookie。只请求代码生成的官方 HTTPS 地址，禁用重定向；连接和读取超时各为 12 秒，单个响应上限为 2,000,000 字节。日志只记录固定错误码与集数。

## 界面与生命周期

详情读取在工作线程执行，结果回到主线程。新请求、返回列表、切换导航、开始播放和 Activity 销毁都会取消旧请求；已经排入主线程队列的旧结果也不能覆盖新页面。

沿用最多六部作品的内存缓存，打开时先显示缓存并刷新。读取失败显示错误与重试；已有缓存时保留内容并提示更新失败。加载占位区域保留焦点，完成后恢复到详情操作按钮，重试后也能继续使用遥控器。

`OfficialPortal.detail()`、详情 JavaScript 适配器，以及播放器内的 HTML 简介／集数解析已移除。播放器使用 API 返回的选集，不再通过 `player-detail` 覆盖它。详情失败不会导航到作品网页或回退到 HTML。

详情迁移阶段的首页、搜索界面、账号操作及播放会话仍由 `OfficialPortal` 管理。当时仍有共享 WebView，因此下文的早期详情验证不以 `WebViews: 0` 为目标。

## 详情迁移阶段的 WebView 桥接审计（历史记录）

2026-11-13 复查调用方后，移除了没有调用方的 `favorite-action.js`，以及日志白名单中的旧 `detail`、`player-detail`、`detail-error` 事件。当前追番实际使用 `tv-commands.js`，不依赖被删除的脚本。生产源码中已无详情适配器加载入口或旧详情消息处理分支。

剩余 11 份适配器均能在 `OfficialPortal` 中找到直接加载调用：

| 依赖 | 当前实现 |
| --- | --- |
| 首页目录 | `catalog.js` 从官方首页 DOM 读取卡片 |
| 搜索页 | `tv-commands.js` 在 WebView 内请求官方搜索 API，再发送结果至原生界面 |
| 扫码与网页登录 | `qr-login.js`、`account-sync.js`，沿用官方 Cookie 和身份确认 |
| 收藏、历史与追番操作 | `account-sync.js` 读取收藏页面／历史 API；`tv-commands.js` 通过官方追番控件执行更改 |
| 年龄确认与广告 | `age-confirmation.js`、`ad-skip.js` 使用当前官方页面中的实际控件 |
| 授权取源、原生接管与进度同步 | `native-hls.js`、`native-playback.js`、`player.js`、`stop-playback.js` |
| 官方页面遥控导航 | `tv-navigation.js` |

官方会话通过 `WebMessageListener` 接收消息，不支持该能力时使用 `evaluateJavascript` 读取受文档及请求标识约束的消息队列。原生端还会执行 JavaScript 下发播放器接管、时钟与前后台状态。

旧内核语法兼容器还有独立的本地 `JavascriptInterface`：只加载本地编译资源，阻止网络访问；按实际语法能力启用，需要编译时才创建额外 WebView，成功编译后空闲 10 秒释放。它不读取账号或作品详情。

审计时普通入口仍在 `MainActivity.onCreate()` 中创建共享官方 WebView，原生播放尚属独立 `nativeApi` 构建。后续已将首页、搜索、账号请求与原生播放接入普通入口，并将 WebView 改为仅在登录时创建；上表中的非登录适配器均已删除。

## 验证

- `testDebugUnitTest lintDebug assembleDebug` 通过。
- JVM 共 54 项通过，其中 13 项覆盖原生 API 资料、请求限制、身份绑定、特別篇、异常响应、重试与过期结果隔离。
- Node.js 源码与 WebView 74 编译产物各 43 项通过。播放器回归测试禁止读取 HTML 简介与集数，确认仍能上报播放状态。
- XML、JSON、JavaScript、TV 声明、许可证和 Gradle Wrapper 校验通过。
- Android Lint：0 错误、5 项既有警告。
- 最终 APK 的 v2 签名与 `zipalign -c -P 16 4` 通过。

独立访客模拟器：`BahaPlusTV_WebView83_API29`，Android 10／API 29、32 位 x86，实际 WebView `83.0.4103.120`；使用专属 ADB 端口 5093 与 `emulator-5594`。测试未使用正在试用的账号。

先加载真实首页，然后在共享 WebView 的当前文档中阻止作品／影片 HTML 的 `fetch`，并使 `DOMParser` 抛错。通过普通首页卡片及确认键打开：

| 作品 | 结果 |
| --- | --- |
| 冰劍的魔術師將要統一世界 2 | 原生详情与 6 集载入成功；模拟器验证包加载后焦点为“开始播放” |
| CLANNAD | 没有影片 ID 的热门卡片成功通过原生搜索解析；返回 24 条选集 |
| 名偵探柯南 | 没有影片 ID 的热门卡片成功解析；官方多组响应合并为 2,052 条影片项，遥控向下滚动至第 45 项成功 |

上述页面停留在首页 `/`，受阻的 HTML 详情读取／解析次数始终为零。截图及脱敏摘要保存在忽略的 `artifacts/performance/metadata-*`。官方分组可能包含相同集数标签的不同影片，2,052 是合并后的影片项数，不表示 2,052 个不同剧情集数。

上述模拟器验证所用调试包为 7,543,479 字节，SHA-256：

```text
9432d5f1bfdab8951acb1f112cd7b4f82eecc7c445331140ff5828d3ca49b4bf
```

清理无调用方的脚本和旧日志事件后，当前本地调试产物为 `artifacts/BahaPlusTV-0.3.0-debug.apk`，7,543,399 字节，SHA-256：

```text
52c94e65ba628cca2851367b0b00729bbf8aa62109b6f519af3e61da06169342
```

清理后的构建重新通过上述 JVM、JavaScript、Lint、签名和对齐检查；APK 中恰有 11 份有效适配器，不再包含 `detail.js` 和 `favorite-action.js`。清理后未重跑模拟器验证。

本次保留工作区原有版本号，未推送或发布。目标 ARM 电视、账号续播及本次改动后的完整播放流程仍需另外验收。
