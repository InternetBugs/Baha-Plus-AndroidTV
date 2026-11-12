# 官方手机扫码登录

实施与验证日期：2026-11-13，Asia/Taipei。

来源：酥鱼要求继续添加手机扫码登录，复用官方接口。本次未使用用户日常浏览器或其中的登录会话。Playwright MCP 因配置的 Chrome 路径不存在而未能启动，真实浏览器验证使用本机现有 Playwright Chromium 的全新临时上下文，结束后关闭。

## 使用方式

“登录账号”默认打开原生扫码页。用手机相机扫描二维码，进入巴哈姆特官方网页，登录并确认授权。电视通过官方接口获取会话，重新加载官方页面确认账号身份，再关闭扫码页并沿用原有追番、历史同步。

有效期由服务器给出，实测为 180 秒。页面显示倒计时；过期时隐藏二维码，选择“刷新二维码”可重新申请。保留“使用账号密码登录”和“返回”按钮，均可用方向、确认及返回键操作。

## 官方协议依据

以下为本次直接向官方公开接口及其页面脚本核实的行为，并非公开稳定 API 的承诺：

| 用途 | 官方资源 | 本次核实的行为 |
| --- | --- | --- |
| 申请授权链接 | `POST https://api.gamer.com.tw/user/v1/link_login_request.php` | 返回 `data.sessionId`、Unix 秒级 `data.expiredAt` 和 `data.link` |
| 电视轮询／领取会话 | `POST https://api.gamer.com.tw/user/v1/link_login.php` | 表单字段为 `sessionId`；等待时返回 `error.code=1001`、`error.status=NOT_YET` |
| 手机授权入口 | `https://user.gamer.com.tw/link_login.php?token=…` | 未登录时进入官方登录页，随后回到授权流程 |
| 手机确认接口 | `https://api.gamer.com.tw/user/v1/link_login_authorize.php` | 官方 `login.js` 的 `linkLoginPage.authorizeLogin` 使用该接口；本项目不替用户确认 |
| CSRF 机制 | `https://i2.bahamut.com.tw/js/csrf.js` | 官方 `Bahamut.Csrf` 写入 `ckBahamutCsrfToken` 并提供 `X-Bahamut-Csrf-Token` |

请求由动画疯官方主框架内执行，使用 `credentials: include`，Cookie 由 WebView 接收和管理。申请与轮询禁止跳转，并检查响应 URL。授权链接只接受官方 `user.gamer.com.tw/link_login.php`，原生层再次校验地址和有效期。

`sessionId` 仅保留在当前网页适配器闭包中，不传给原生 UI、不写入日志或偏好。授权链接仅在内存中交给本地 ZXing 编码显示，不调用第三方二维码图片服务。所有示例均省略授权凭据。

## 会话与资源处理

- 正常轮询间隔为 2 秒，一次请求完成后再安排下一次；网络异常间隔 3 秒，连续失败 3 次停止并提供重试。
- 单次请求超时为 12 秒。官方拒绝、无效响应或过期不会被当成成功。
- 进入后台时停止调度并取消进行中的请求；回到前台先检查有效期。
- 刷新、返回、切换密码登录和页面离开时取消旧请求，旧响应无法更新新二维码。
- 领取成功后重新读取官方页面的账号身份；仅轮询返回成功不足以让原生界面认定已经登录。
- 退出登录沿用现有清理 Cookie 与 WebStorage 的行为。

## 已执行验证

- 20 项 Node.js 离线测试全部通过，其中新增 8 项扫码测试，覆盖官方来源、CSRF／Cookie 请求选项、授权链接替换、过期、刷新与迟到响应、后台暂停、拒绝和有上限的网络重试。
- Android 调试构建、5 项 JVM 测试与 Lint 通过，Lint 为 0 错误、6 项既有警告。原生测试增加官方授权地址及期限校验；APK 的 v2 签名及 16 KiB 对齐检查通过。
- 在真实 Playwright Chromium 中，适配器直接从官方页面成功申请二维码；随后两次实际轮询均得到 HTTP 200 / `NOT_YET`。暂停后观察到不再发起轮询。未伪造或跳过官方授权。
- Android TV API 30 模拟器可打开原生扫码页，默认聚焦“更新 QR Code”，并切换至密码登录；最终包返回后恢复首页侧栏焦点。首轮模拟器访问官方页面出现 `net::ERR_CONNECTION_CLOSED`，宿主系统与模拟器 DNS 均曾返回与公开 DoH 不一致的地址；仅为测试模拟器指定公共 DNS 后仍无法解析官网，未修改 Windows 或应用的 DNS 设置。该阶段未在模拟器中取得真实二维码；后续修复结果见下一项。
- 同日交互测试中定位并修复上述环境问题：实际 Windows 用户使用 Mihomo 本地代理 `127.0.0.1:7890`，模拟器未配置该代理，重启后也没有连接虚拟 Wi-Fi。将模拟器连接至 `AndroidWifi`，并仅在模拟器中设置 HTTP 代理 `10.0.2.2:7890` 后，网络获得 `VALIDATED` 状态，应用首页真实番剧加载成功，原生扫码页显示真实二维码及“等待手機確認 · 164 秒後過期”。模拟器二维码申请及显示已验证。此设置依赖宿主机现有代理运行，未更改应用源码或 Windows／Mihomo 配置。
- 2026-11-13，酥鱼继续在交互模拟器中测试后反馈“账号功能完全正常”，随后授权上传更改。该反馈作为用户对本次账号功能的验收记录；未另外逐项记录手机确认、Cookie 持久化、追番、历史同步及退出登录的独立操作证据。
- 最终包安装、启动、扫码入口和返回操作后，当前进程及保留日志中的 Java fatal exception、native fatal signal 和该包 ANR 计数均为 0。该采样不能代替长时间运行和完整账号测试。

本次账号功能已有用户实测反馈；离线成功响应测试用于验证客户端状态转换。目标 ARM 电视上的登录与遥控操作、验证码、两步验证、长时间会话保持及整集播放仍需实机验收。

本次 APK 仍使用项目已有的 `0.1.0` 调试版本号及 versionCode 1，发布标签为 [`v0.1.0-qr.1`](https://github.com/InternetBugs/Baha-Plus-AndroidTV/releases/tag/v0.1.0-qr.1)。该发布附带本次用户测试的 `BahaPlusTV-0.1.0-debug.apk` 与 SHA-256 校验文件，旧 `v0.1.0` 发布保留。

最终 APK 大小为 11,189,144 字节，SHA-256 为 `42f750e6a6853828e0abed15e7b17792f7ba1438212615e88acc2296b087512a`。
