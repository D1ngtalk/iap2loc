# iap2loc 安卓版

**中文** · 与 [Windows / macOS 版](README.md) 共用同一个网页控制台和配置格式。

安卓版让一部安卓手机通过蓝牙扮演 MFi 认证的 GPS 配件，向 iPhone 下发定位（NMEA）。它是一个独立 App：
连接 iPhone、MFi 认证、单点 / 路径模式、GPX 导入、收藏、随机化、多设备、TCP 传输、控制台日志等桌面版的全部功能都在手机上完成，不需要电脑。

> [!IMPORTANT]
> 和桌面版一样：需要你**合法取得**的 MFi 配件身份（`identity.pk8` + `certificate.p7b`），App 不包含任何密钥或证书；
> 不保证 iOS 一定采用配件定位；iOS 26 及以上需要按说明[刷新定位缓存](README.md#在设备上切换定位源)。

## 下载与要求

| 项目 | 要求 |
| --- | --- |
| 安装包 | [Releases](https://github.com/D1ngtalk/iap2loc/releases) 中的 `iap2loc-android.apk`（约 1.5 MB） |
| 安卓手机 | Android 8.0 及以上，有经典蓝牙 |
| iPhone | iOS 12 – 27 |
| MFi 身份 | `identity.pk8`（PKCS#8 DER 格式 P-256 私钥）与 `certificate.p7b`（证书链） |

安装时如果系统提示「未知来源」，在提示里允许本次安装即可。只从你信任的来源下载 APK，并可用同一 Release 中的 `iap2loc-android.apk.sha256` 校验。

## 新手引导

第一次打开 App 会进入新手引导，一步一屏，大约 5 分钟：

1. **欢迎**：需要准备什么、工作原理和使用须知。
2. **权限**：「附近的设备」（蓝牙）与蓝牙开关是必需的；通知推荐打开；位置用于把控制台的默认位置设为手机当前位置，以及「当前位置」按钮，可以不给（默认位置则为上海人民广场附近）。Android 11 及以下用「位置」权限代替「附近的设备」来搜索蓝牙。
3. **后台运行**：关闭电池优化，并按你的手机品牌（小米、华为 / 荣耀、OPPO / realme / 一加、vivo / iQOO、三星等）给出放行后台的具体路径。
4. **MFi 身份**：选择 `identity.pk8` 和 `certificate.p7b`（也可以选包含它们的 `.zip`，支持 PEM 格式私钥）。App 会显示私钥类型、证书主题与有效期，并检查私钥和证书是否匹配，相当于桌面版的 `inspect-identity`。
5. **配对 iPhone**：在 iPhone 上停留在「设置 → 蓝牙」页面，在 App 里搜索并配对，或改用系统蓝牙设置配对。App 会尝试检测 iPhone 的 iAP2 服务。
6. **连接测试**：自动连接选中的 iPhone，逐项显示「蓝牙连接 → iAP2 链路 → 配件识别 → MFi 认证 → iPhone 请求定位」，失败时给出对应的处理办法。
7. **iPhone 设置**：按步骤让 iPhone 改用 iap2loc 的定位；iPhone 开始请求定位后会实时显示已发送的条数。

引导可以随时从控制台右上角菜单 →「新手引导」重新打开。

## 控制台

引导结束后进入控制台。它就是桌面版的网页控制台（`Web/index.html`），由 App 内置的服务提供，功能完全一致：

- 地图基准 OSM（WGS-84）/ 高德（GCJ-02），自动坐标换算；
- 设备列表与多设备连接，单点 / 路径两种模式，「应用修改」后生效；
- 路径模式：地图连点、拖动序号调整、长按删除，或「导入 GPX」（系统文件选择器）；
- 随机偏移、随机精度与高度，定位质量、卫星数、上报周期；
- 收藏、「当前位置」（使用手机定位）、复制坐标；
- 高级设置：更换身份与蓝牙地址、MFi 密钥目录、TCP 传输、控制台日志。

地图库 Leaflet 随 App 离线打包，不依赖 unpkg CDN；地图图块（OSM / 高德）仍需联网，没网时可以手动输入经纬度。

顶部的放大镜是安卓版独有的**地点搜索**：输入地名、地址或景点后按键盘上的搜索键，从候选列表里选一个，控制台会切到单点模式、移动到该地点并立即应用（相当于在地图上点选后按「应用修改」）。

右上角菜单还提供：iPhone 端切换定位源、帮助与故障排查、设置、局域网访问、刷新页面、导出日志（分享给自己或用于反馈）、退出。

### 后台运行

连接 iPhone 后 App 以前台服务运行，通知栏会一直显示状态（已连接设备数、已发送条数）和「停止并退出」按钮；
只有在有设备连接时才保持 CPU 唤醒，屏幕可以正常熄灭。按返回键只会切到后台、不会断开；要完全停止，用通知里的「停止并退出」或菜单里的「退出」。

### 地点搜索

在「设置 → 地点搜索 → 服务商与 Key」中选择服务商并填写各家的认证信息，修改即时保存：

| 服务商 | 需要的认证 | 说明 |
| --- | --- | --- |
| OpenStreetMap · Photon（默认） | 无 | komoot 的免费服务，覆盖全球；可改填自建服务器地址 |
| OpenStreetMap · Nominatim | 无 | OSM 基金会的官方服务，擅长完整地址；公共服务器每秒最多一次请求，可改填自建服务器 |
| Android 系统 Geocoder | 无 | 系统自带服务，通常来自 Google Play 服务，很多国内手机没有 |
| 高德地图 | 「Web服务」Key，可选数字签名私钥 | [高德控制台](https://console.amap.com/dev/key/app)创建 Key 时服务平台选「Web服务」 |
| 百度地图 | 「服务端」AK，可选 SK | [百度控制台](https://lbsyun.baidu.com/apiconsole/key)创建「服务端」应用，校验方式选 sn 校验（填 SK）或 IP 白名单 `0.0.0.0/0` |
| 腾讯位置服务 | Key，可选 SK | [腾讯控制台](https://lbs.qq.com/dev/console/application/mine)创建 Key 并勾选 WebServiceAPI，推荐签名校验（填 SK） |

- 所有服务商都通过 HTTPS 调用，App 不打包任何地图厂商的 SDK；自建服务器地址必须是 `https://`。
- 高德、百度、腾讯返回 GCJ-02 坐标（百度通过 `ret_coordtype=gcj02ll` 请求 GCJ-02），App 会换算成 WGS-84 再发给 iPhone，与控制台「高德」底图的换算公式相同；系统 Geocoder 在中国境内的结果默认也按 GCJ-02 处理，可在设置里关闭。
- 搜索词会发送给所选服务商；Photon 和腾讯还会收到当前目标坐标，用来优先显示附近的结果。Key 只保存在本机 App 私有存储中，不随系统备份。
- 只在按下搜索键时才请求，不做边输边搜，避免超出公共服务器的使用政策和各家的免费配额。

### 局域网访问

默认只有本机能打开控制台（`127.0.0.1`）。在「设置 → 允许局域网访问」打开后，同一 Wi-Fi 下的电脑或平板可以用设置页显示的地址（如 `http://192.168.1.23:8737/`）打开同一个控制台。
同一网络里的任何人都能操作，用完请关闭。修改后需要点「立即重启控制台」，正在连接的设备会断开。

## 设置与命令行参数的对应

| 桌面版 | 安卓版 |
| --- | --- |
| `serve --port` | 设置 → 控制台 → 端口 |
| `--identity` | 引导或设置里导入的 MFi 身份；控制台「MFi密钥」留空即使用它 |
| `--fixed-identity` | 关闭「每次启动更换配件身份」 |
| `--fixed-adapter` | 关闭「上报随机蓝牙地址」后用「手动填写地址」 |
| `-v` / `--verbose` | 打开「详细协议日志」 |
| `devices` | 控制台「设备」列表、引导的「配对 iPhone」 |
| `run --probe` | 引导的「连接测试」 |
| `inspect-identity` | 引导或设置里的 MFi 身份详情 |

安卓不允许普通 App 读取本机蓝牙地址，所以默认上报一个随机生成并固定保存的地址（与桌面版的随机地址行为相同，iOS 接受改写后的地址）。

配置与收藏保存在 App 私有目录的 `iap2loc.json`，格式与桌面版相同；MFi 身份保存在私有目录的 `identity/` 中。两者都不会被系统备份或换机迁移带走。

## 故障排查

| 现象 | 处理 |
| --- | --- |
| 设备列表里没有 iPhone | 先在引导「配对 iPhone」中配对；检查「附近的设备」权限与蓝牙开关；控制台可勾选「显示非 Apple 设备」 |
| 连接超时 / read failed | 解锁 iPhone 并停留在「设置 → 蓝牙」页面，两台手机靠近；关闭再打开两边蓝牙；仍失败时两边都删除配对后重新配对 |
| 识别被拒绝（0x1D03） | 控制台「高级设置」→「更换身份与蓝牙地址」后重连；打开详细协议日志查看被拒绝的参数 |
| 认证失败（0xAA04） | 身份文件无效、证书链不完整或与私钥不匹配，重新导入 |
| 已认证但位置不变 | 按「iPhone 端切换定位源」操作；iOS 26+ 必须刷新定位缓存 |
| 锁屏或切后台后断开 | 允许通知、关闭电池优化，并按引导「后台运行」中的品牌设置放行 |
| 电脑打不开控制台 | 打开「允许局域网访问」，两台设备在同一 Wi-Fi，使用设置页显示的地址 |

遇到问题时，菜单 →「导出日志」可以把控制台日志连同 App 版本与手机型号一起分享出去。

## 源码构建

安卓版位于 [`android/`](android/)，Kotlin + Jetpack Compose，Gradle 构建：

```text
android/
├── core/    纯 JVM 模块：iAP2 链路、识别、MFi 签名、NMEA、路径回放、配置、会话与网页服务（逐行移植自 Protocol/、Engine/、Web/），以及地点搜索的 HTTP 客户端（geocode/）
├── app/     安卓 App：蓝牙 RFCOMM 传输、前台服务、新手引导、WebView 控制台、地点搜索、设置与帮助
└── tools/golden-vectors/   用桌面版 C# 源码生成对照数据，并可扮演 iPhone 做互通测试
```

需要 JDK 17+ 与 Android SDK（platform 36）。在 `android/` 下：

```bash
./gradlew :core:test            # 协议与引擎单元测试（含与 C# 实现逐字节比对、模拟 iPhone 端到端）
./gradlew :app:assembleRelease  # 生成 app/build/outputs/apk/release/app-release.apk
./gradlew :app:lintRelease
bash ../scripts/smoke-android.sh app/build/outputs/apk/release/app-release.apk  # 需已连接模拟器或手机
```

控制台页面直接取自仓库根目录的 `Web/index.html`，修改网页会同时作用于桌面版和安卓版。

### 签名与 CI

工作流 [`.github/workflows/android.yml`](.github/workflows/android.yml) 会：用 C# 源码重新生成对照数据并运行单元测试、与 C# 链路层做互通测试、构建并 lint 发布版 APK、在模拟器上安装启动并测试控制台与 JSON API；发布 Release 或手动重跑指定 tag 时把 `iap2loc-android.apk` 附加到 Release。

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

## 限制

- 模拟器没有可用的经典蓝牙对端，CI 只验证安装、启动、前台服务和控制台；与 iPhone 的蓝牙连接、iAP2 认证和定位生效需要实机验证。
- 部分安卓系统读不到 iPhone 的 iAP2 SDP 记录，引导会提示但不阻止测试；连接时依次尝试加密与非加密 RFCOMM。
- 国内厂商系统的后台管理差异很大，引导给出的路径可能随系统版本变化。

遵循 [GPL-3.0](LICENSE)。仅在自有设备上用于协议研究与合规测试；不要将定位伪造用于欺诈或违反服务条款的场景。
