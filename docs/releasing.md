# GitHub Actions 发版

工作流：`.github/workflows/release.yml`，在 GitHub Actions 中显示为 **Android release**。

每次执行都会运行适配器测试、项目资源检查、Release JVM 单元测试与 Lint，再生成开启代码与资源压缩的 Release APK。APK 使用固定的 PKCS#12 签名密钥，检查签名、16 KiB 对齐并生成 SHA-256。检查失败时不发布。

## 签名配置

仓库 **Settings → Secrets and variables → Actions** 需要以下 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `.p12` 密钥库文件的单行 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 签名私钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名私钥密码 |

签名密钥仅在签名步骤中还原至运行器临时目录，步骤退出时删除。密码通过环境变量传入 `apksigner`，不写入 Gradle 配置。工作流不上传密钥库；构建附件只包含 APK、校验文件、可选的发布说明及独立的检查报告。

后续版本应继续使用相同签名。早期 `v0.1.0`／`v0.1.0-qr.1` 测试 APK 使用调试签名；从调试包切换至正式签名包需要先卸载旧应用，原有本地登录会话与设置会随卸载清除。

## 手动构建或发版

打开 **Actions → Android release → Run workflow**，选择 `main`：

- `tag`：例如 `v0.1.1` 或 `v0.1.1-rc.1`。APK 内 `versionName` 自动采用去掉 `v` 的版本。
- `version_code`：可选的正整数，正式升级必须大于已安装版本。留空时采用 `app/build.gradle.kts` 中的默认值。
- `publish`：默认关闭，仅构建、签名并上传 Actions Artifacts；开启后创建 GitHub Release，并为当前提交创建标签。
- `prerelease`：标记预发布；`v0.1.1-rc.1` 等带后缀的标签会自动标记预发布。

构建下载位于对应运行的 **Artifacts → BahaPlusTV-版本号**。检查报告与混淆映射在独立的 `android-reports-*` 附件中。APK 附件保留 30 天，报告保留 14 天；正式发布后 Release 中的 APK 与校验文件持续保留。

发布只接受 `main` 上的提交；如果指定的已有标签指向其他提交，工作流会拒绝发布。已经存在的 Release 不会被覆盖。

## 推送标签自动发版

1. 在 `app/build.gradle.kts` 中递增 `releaseVersionCode` 的 `.orElse("…")` 默认值，并更新 `releaseVersionName` 的默认值。
2. 根据实际改动编写 `docs/release-notes-版本号.md`，例如 `docs/release-notes-0.1.1.md`。工作流原样使用这份说明，不翻译提交标题；没有该文件时使用 GitHub 自动生成的更新记录。
3. 将改动提交并推送到 `main`，再为该提交推送版本标签：

```powershell
git tag v0.1.1
git push origin v0.1.1
```

只有 `v*` 标签推送会自动发布；普通 `main` 推送不会发版。正式标签如 `v0.1.1` 创建正式 Release，带后缀的标签如 `v0.1.1-rc.1` 创建预发布。手动构建时输入的版本不会自动回写源码，下一次推送标签前仍需核对源码默认版本号。

Release 先以草稿创建并上传 APK、SHA-256，成功后才公开。若上传中途失败，检查并处理残留草稿后再重跑。

## 本地验证同样的 Release 构建

```powershell
npm ci --ignore-scripts
npm test
python tools/check-project.py
.\gradlew.bat testReleaseUnitTest lintRelease assembleRelease `
  '-PreleaseVersionName=0.1.1' '-PreleaseVersionCode=2'
```

本地命令生成 `app/build/outputs/apk/release/app-release-unsigned.apk`；Actions 会完成后续对齐、签名、验签及校验文件生成。本地不需要在 Gradle 中配置签名凭据。
