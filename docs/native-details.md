# 原生详情与选集

实现及测试日期：2026-11-13，Asia/Taipei。

本文记录详情与选集迁移阶段的实现和当时产物。随后同日已将其余非登录功能迁移到原生 HTTP，当前架构与产物以 [原生 HTTP 迁移](native-http-migration.md) 为准。

## 资料路径

参照本地 Baha-Plus 的 `app/lib/data/official_app_api.dart`，正式入口的作品简介、封面、制作资料和完整选集改为 Kotlin `HttpURLConnection` 读取官方公开 API：

- 有影片 ID：`https://api.gamer.com.tw/mobile_app/anime/v4/video.php?sn=…`。
- 只有作品 ID：先请求 `https://api.gamer.com.tw/anime/v1/search.php?kw=…`，按作品 ID 核对搜索结果并取得影片 ID，再读取 `video.php`。不会仅按片名或第一条结果选片。
- 从官方观看历史续播时优先使用该条历史的影片 ID。

客户端核对响应中的作品 ID、影片 ID 和选集归属，按官方分组顺序合并集数；拒绝重复影片 ID、缺少请求影片、无效图片来源及超过 5,000 条的集数。只映射公开资料，不读取或保存收藏、断点与账号状态。2026-11-14 新增评分统计的独立原生请求，见下文；只有平均分时不生成缺失的评分人数或分布。

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

## 2026-11-14 原生评分表

在简介／播放按钮与选集之间新增原生评分卡，显示平均分、评价人数及五星到一星的分布和各档人数。采用静态 `LinearLayout`、`TextView` 与水平 `ProgressBar`，沿用电视的深色背景和青绿色强调色，无网页绘制、图片星标或动画依赖。支持简繁中文，评分区域不接受遥控焦点。

数据来自与官网共用的公开 JSON 接口：

1. 核对作品归属后的 `video.php` 返回 `anime.fanId`，注意它与作品 `animeSn`、百科 `acgSn` 不同。
2. `GET https://api.gamer.com.tw/acg/v1/reviews_list.php?sn=<fanId>&t=4`。
3. 仅映射 `data.rating.avg`、`count` 与 `star_chart` 的 `1`～`5`，不映射或保存返回的评价正文、作者及个人评价状态。

评分请求与详情使用独立的可取消任务。先显示简介与选集，再异步更新评分控件，不重建选集列表或改变当前焦点。结果与详情一起进入最多六部作品的内存缓存；切换作品、返回列表、开始播放与销毁 Activity 都取消旧请求，已经排队的旧回调也不能覆盖新页面。从播放返回时，若评分尚未取得则重新请求。

评分不可用时保留已读取的平均分；有缓存则保留缓存。缺少有效 `fanId` 不影响选集，统计请求失败也不影响播放。只有 API 明确给出零评价时才显示零人数和空分布。客户端拒绝负数、小数或越界人数、缺失／多余星级、各档人数之和与总人数不符等响应。请求沿用详情的 HTTPS、重定向、类型、超时和大小限制，不读取 WebView Cookie，也没有桥接或 HTML 回退。

验证使用独立源码快照和全新的访客 Android TV API 30／x86 模拟器，没有读取或操作现有登录会话：

- `testDebugUnitTest` 与 `testNativeApiUnitTest` 各 98 项通过，其中新增 8 项测试覆盖真实字段映射、`fanId`、可选统计、零评价、异常响应、传输限制、请求取消及过期回调。
- Debug、实验入口与 Release 的 Lint 均无错误，分别保留 3／12／3 项既有警告，新增评分控件没有 Lint 警告；Debug 与 Release 构建通过，Release 完成 R8。调试 APK 的 v2 签名及 16 KiB ZIP 对齐检查通过。
- 实际进入《鐵旅！meet with 鐵道少女》详情，原生显示 4.8 分、649 人；五星到一星为 563／55／20／4／7，与本次官网 HTTP 返回一致。这些数字仅为验证时的快照，应用没有硬编码。
- 实际方向键向下跨过评分表进入集数，再向右移动至第 6 集；详情及选集截图的应用 WebView 数量均为 0。
- 测试模拟器直连存在 DNS／网络问题，最终使用主机既有的网络代理读取真实 HTTPS 服务；应用协议和证书验证未改变。此结果不代替目标 ARM 电视的验收。

截图及焦点证据位于忽略目录 `artifacts/performance/rating-*.png`／`.json`；隔离构建记录为 `.tools/rating-isolated-final-build.log`。本地调试包为 `artifacts/BahaPlusTV-0.3.1-rating-debug.apk`，校验值见相邻 `.sha256`。版本号仍为 0.3.1／versionCode 4，未新增正式发布；测试包只叠加本次评分功能，不包含工作区同时进行的其他功能改动。

最终 APK 为 7,366,068 字节，SHA-256：`40f06ed3f52b5975cdb57dec0b2ebfe498a63bd5a04bf0ac24e59f866873e160`。
