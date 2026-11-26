# Actions 构建与发布

Windows、macOS 与 Android 的构建、冒烟检查及标签自动发布由 `.github/workflows/` 中的工作流负责。各平台均通过后才公开 Release；普通分支与 PR 只生成测试产物。标签格式、附件清单与失败重跑方法见 [README 自动发布说明](README.md#推标签自动发布)。

## Android 签名与 CI

工作流 [`.github/workflows/android.yml`](.github/workflows/android.yml) 会：用临时生成的材料验证 MFi 打包脚本、用 C# 源码重新生成对照数据并运行核心与 App 身份加载单元测试、与 C# 链路层做互通测试、构建并 lint 发布版 APK、在模拟器上安装启动并测试控制台与 JSON API。推送 `v*` 标签时，[统一发布工作流](.github/workflows/release.yml) 调用此流程，并等待 Windows、macOS 和 Android 全部通过后统一创建和发布 Release；单平台手动发布与重跑仍可向已有 Release 附加 APK。脚本测试与 App 单元测试在 PR 中也会执行，无需真实密钥。标签格式和重跑方法见[自动发布说明](README.md#推标签自动发布)。

所有 Android CI 入口的 `versionCode` 统一按本次 GitHub run 的 `created_at` 计算：距 `2020-01-01 00:00:00 UTC` 的秒数加 `1,000,000`，并检查 `1..2,100,000,000` 的范围。切换工作流不会从小编号重新开始；重跑同一 run 编号保持不变，同秒创建的运行可以同号。`versionName` 为去掉 `v` 的标签，分支测试构建使用开发版名称。本地 Gradle 默认编号仍为 `1`，若需覆盖 CI 安装包，请通过 `-PappVersionCode` 明确指定不小于已安装版本的编号。

发布版必须用项目自己的签名密钥签名（安卓拒绝用不同签名的安装包覆盖升级）。在仓库 Secrets 中配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` 的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |

可用 `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias iap2loc` 生成密钥库，并妥善离线备份；**不要把密钥库提交到仓库**。
未配置时，普通构建会用临时调试密钥签名（可安装测试），但发布步骤会失败，不会把调试签名的 APK 发到 Release。

本地构建时可以通过环境变量 `IAP2LOC_KEYSTORE`、`IAP2LOC_KEYSTORE_PASSWORD`、`IAP2LOC_KEY_ALIAS`、`IAP2LOC_KEY_PASSWORD` 使用自己的密钥。

### 在 CI 中打包内置 MFi 身份

在仓库 Settings → Secrets and variables → Actions → Repository secrets 中，另外添加以下两项（与 APK 签名的四项 Secrets 用途不同）：

| Secret | 内容 |
| --- | --- |
| `MFI_IDENTITY_PK8_BASE64` | `base64 -w0 identity.pk8` 的完整输出；接受未加密的 PKCS#8 或 SEC1 DER 私钥 |
| `MFI_CERTIFICATE_P7B_BASE64` | `base64 -w0 certificate.p7b` 的完整输出 |

两项必须同时配置。CI 会先在内存中将私钥统一转换为未加密的 PKCS#8 DER（包括 SEC1 输入，密钥对不变），检查 P-256 曲线、PKCS#7 DER 证书链及二者匹配，再将规范化后的材料写入 runner 临时目录，通过 `IAP2LOC_MFI_ASSETS` 把 `offline-mfi/` 加入 Release assets；完成后清理临时目录。转换或校验失败会终止构建，不导出 assets 路径，不等待密码输入。此入口不接受 PEM，也不提供加密私钥的密码配置。密钥原文件不提交到 Git，也不会打印到构建日志。普通 push、Release 和手动构建可注入，PR 构建不会注入；两项都未配置时继续构建需手动导入身份的普通版，只配置一项或材料不匹配时构建失败。

APK 文件名仍为 `iap2loc-android.apk`，签名与发布流程不变。App 优先使用控制台显式指定的身份目录，其次使用完整的手动导入文件对，最后使用完整的 APK 内置身份；不会混用不同来源的单个文件。只导入一份自定义文件时会持续提示缺失文件，并继续使用内置身份；普通版没有内置身份时仍可连接和识别，但无法完成认证。补齐两份文件后切换到导入身份，删除导入文件后恢复内置身份。显式指定的目录有误或完整导入文件对无法解析时会报错，不会静默切换来源。

**内置身份会随 APK 分发，任何持有 APK 的人都可以解压提取。Secrets 仅保护构建前的材料；发布者应确认材料可再分发。** 本地构建也可将 `IAP2LOC_MFI_ASSETS` 指向包含 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b` 的绝对目录；Debug 构建不打包这些文件。
