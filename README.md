# iap2loc

**中文** · [English](README.en.md) · [日本語](README.ja.md)

跨平台 iOS 虚拟定位客户端，支持 **Windows 10/11 x64**、**macOS Apple Silicon (arm64) / Intel (x64)** 与 **Android 8.0+**。

通过 iAP2 协议向 iOS 设备下发定位信息（NMEA），并提供本地 Web 控制台管理设备、地图选点和参数。

> [!IMPORTANT]  
> ## 支持 iOS 12.X - 27.X 的所有版本
> 已在 iPhone 6 (iOS 12.5.8)、iPhone 11 (iOS 26.6)、iPhone 17 (iOS 27.0) 上通过测试
> >更低的 iOS 版本也可能可用，但未经过验证
> ### ⚠️ 对于 iOS 26 及更高版本：
> 自 iOS 26 开始，Apple 更改了位置融合算法和位置信息缓存。在 iOS 26 及更高版本的设备上需要 [刷新设备的定位缓存](#在设备上切换定位源) 才能使修改后的定位生效；「路径模式」在开启 Wi-Fi 或数据流量后可能不可用。


## 下载与系统要求

从 [Releases](https://github.com/D1ngtalk/iap2loc/releases) 获取对应版本；或 [从源码构建](#源码构建与-ci)
| 平台 | 构建产物 | 要求 |
| --- | --- | --- |
| Windows 10/11 x64 | `iap2loc-windows-x64.exe` | 已配对的蓝牙设备、MFi密钥 |
| Windows 10/11 x64 | `iap2loc-windows-x64-bundled.exe` | 已配对的蓝牙设备 (无需密钥文件) |
| macOS Apple Silicon | `iap2loc-macos-arm64.dmg` | macOS 蓝牙权限、已配对设备、MFi密钥 |
| macOS Intel | `iap2loc-macos-x64.dmg` | macOS 蓝牙权限、已配对设备、MFi密钥 |
| Android 8.0+ | `iap2loc-android.apk` | 带蓝牙的安卓手机、MFi密钥；App 内有新手引导，详见 [README-Android.md](README-Android.md) |

桌面发布版为自包含程序，通常无需另装 .NET；源码构建需要 **.NET 10 SDK**。macOS 版本为 ad-hoc 签名、**未进行 Apple Developer ID 公证**，仅在信任构建来源时通过系统设置批准打开，不要全局关闭 Gatekeeper。

## 快速开始

1. **安装**：Windows 运行下载的 EXE；macOS 打开对应架构 DMG，将 App 拖入 Applications，并在「系统设置 → 隐私与安全性 → 蓝牙」允许访问。安卓安装 APK 后按 App 内的新手引导完成权限、MFi 身份导入、配对与连接测试即可，见 [README-Android.md](README-Android.md)。
2. **配对蓝牙**：Windows 在操作系统的蓝牙设置中配对 iOS 设备；macOS 可直接连接同 Apple ID 下的 iOS 设备。
3. **打开控制台**：启动应用，本机访问 <http://127.0.0.1:8737/>。同一局域网内的手机或其他电脑可用 `http://<本机 IP>:8737/` 打开
4. **连接设备**：在「设备」中添加你的iOS设备，并确保连接状态变为「已认证」
5. **设置坐标**：在地图上点选位置，或使用「[路径模式](#路径模式)」。调整参数后点击「应用修改」生效。
> [!IMPORTANT]  
> ### 在设备上切换定位源：  
>  1. **关闭 Wi-Fi、数据流量和定位服务**，等待十秒后重新打开定位服务——此时不要打开 Wi-Fi。  
>  2. **打开「地图」**，确认定位源已经切换到 iAP2（蓝点应落在设定的坐标上，而不是设备实际所在位置）。  
>  3. **打开 Wi-Fi，但不要打开数据流量**，等定位稳定后再打开数据流量。
>  
> **重复以上步骤多次定位仍无法生效**：在某些情况下，受到其他定位来源缓存的影响，地图可能会显示一个大致范围圈，并提示 "无法确定你的位置"。此时请 **关闭Wi-Fi、数据流量和定位服务** 并 **重启设备** ，重启并连接蓝牙设置坐标后再从步骤 2 开始。

## 功能

- **跨平台传输**：Windows 蓝牙、macOS IOBluetooth/SDP/RFCOMM、Android BluetoothSocket RFCOMM
- **安卓 App**：同一个 Web 控制台、配置格式与全部功能，外加新手引导、前台服务后台发送与离线地图库，见 [README-Android.md](README-Android.md)
- **Web 控制台**：设备列表、连接会话、单点 / 路径两种模式、实时调整、多设备管理；窄屏自适应排版，控制台日志可在高级设置里展开到面板底部。
- **地图与坐标**：OpenStreetMap WGS-84 / 高德 GCJ-02，自动坐标转换；收藏点。
- **单点模式**：直接在地图上选择坐标点，或填入目标经纬度。
- **路径模式**：地图连点或导入 GPX；带 `<time>` 的 GPX 按录制节奏回放，否则按设定速度匀速，可循环。
- **随机化**：坐标偏移（米，填 0 关闭）、随机精度、随机高度，后两者各带区间输入；数值在应用时掷一次，之后保持不变。
- **NMEA**：GGA 和 RMC；定位质量（0–8）、卫星数（0–99）、速度、航向、高度和精度。
- **上报周期**：默认 250 ms、最小 10 ms；设备在 `0xFFFA` 指定的订阅周期优先于 `--rate`。
- **配件身份**：配置文件 `iap2loc.json` 保存配置及收藏；默认每次启动轮换配件名称与序列号，蓝牙地址可由配置管理；支持手动更换身份与地址。请注意这不等于修改真实蓝牙硬件地址或保证绕过 iOS 定位融合。
- **跨距离变化**：对超过 100 m 的位移，程序可在过渡帧携带速度与航向信息。此行为不保证 iOS 接受该位置。

## 路径模式

在控制台顶部切换到「路径」，让目标沿一条折线移动而不是停在单点。模式切换与路径改动都要点「应用修改」才会生效。

- **地图点选**：依次点击地图连成折线；拖动序号调整位置，右键删除单个点，可撤销或清空。
- **导入 GPX**：支持 `trkpt` / `rtept` / `wpt`，超过 5000 个点时自动抽稀。
- **按时间戳回放**：GPX 每个点都带 `<time>` 时，按录制节奏还原（快慢段、停留都照原样），NMEA 的速度取当前路段速度。缺少时间戳或时间倒序时自动退回匀速。
- **匀速**：按「速度 km/h」前进，可勾选循环，到终点后从头再来。
- **进度**：「已应用」一行显示点数、里程和已走距离；计时回放的路径额外显示总时长，此时速度输入不可用。
- **手动加点会清空时间戳**：在导入的 GPX 上继续点地图加点会退回匀速，避免出现半计时、半匀速的数据。

## 命令行

在 Windows 的可执行文件所在目录，或 macOS App 的 `Contents/MacOS` 目录运行（按实际文件名调用）：

```bash
./iap2loc devices
./iap2loc serve --port 8737
./iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF --lat 31.2304 --lon 121.4737
./iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF --probe -v
./iap2loc run --transport tcp --host 127.0.0.1 --port 12345 --lat 31.2304 --lon 121.4737
./iap2loc inspect-identity --identity ./identity
```

| 参数 | 用途 |
| --- | --- |
| `--lat`, `--lon` | 目标纬度、经度 |
| `--alt`, `--speed`, `--bearing`, `--accuracy` | NMEA 附加字段 |
| `--quality`, `--satellites` | GGA 质量与卫星数，默认 1 / 8 |
| `--rate` | 默认周期（毫秒），设备订阅值优先 |
| `--identity` | MFi 身份目录 |
| `--config` | 配置文件路径（默认程序目录的 `iap2loc.json`） |
| `--fixed-identity`, `--fixed-adapter` | 禁用对应的身份或地址随机行为 |
| `--probe`, `-v` | 仅探测识别、详细协议日志 |

`--sentences` 已移除，当前仅发送 GGA/RMC。

## MFi 身份与安全

完成 MFi 认证需要**合法取得**的配件身份，仓库不包含私钥或证书：

```text
identity/
├── identity.pk8       # PKCS#8 DER P-256 私钥
└── certificate.p7b    # 配件证书链
```

可通过 `--identity ./identity` 指定路径。不提供身份时可以排查设备发现、链路和识别，但不能完成 MFi 认证。**不要将密钥、证书、配置中的秘密或真实设备日志提交到 GitHub。**

## 源码构建与 CI

```bash
dotnet build Iap2Loc.csproj -c Release
# Windows 自包含单文件
dotnet publish Iap2Loc.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o dist
# macOS：在对应架构的 Mac 上使用仓库脚本
bash scripts/package-macos.sh arm64 dist/iap2loc-macos-arm64.dmg
# Intel Mac 改为 x64
# Android：需要 JDK 17+ 与 Android SDK，详见 README-Android.md
cd android && ./gradlew :core:test :app:assembleRelease
```

macOS 打包还需要 Xcode Command Line Tools（`clang`、`xcrun`）。[macOS 工作流](.github/workflows/macos.yml) 分别使用 ARM64 与 Intel runner 构建、挂载并冒烟测试 DMG。[Android 工作流](.github/workflows/android.yml) 运行单元测试（含与 C# 实现的逐字节比对和互通测试）、构建 APK 并在模拟器上冒烟测试。普通分支 push/PR 生成 Actions Artifact，不创建 Release。

### 推标签自动发布

将代码推送后，对包含新版工作流的提交创建并推送版本标签，例如：

```bash
git tag v0.3.0
git push origin v0.3.0
```

上面的版本号仅为示例，发布时使用尚未发布的新版本号。[统一发布工作流](.github/workflows/release.yml) 会并行调用 Windows、macOS 两种架构和 Android 构建；所有测试、lint、冒烟检查通过，并确认 Android 使用正式签名后，才创建草稿 Release。上传并确认七个附件齐全后，草稿自动转为公开版本，更新说明由 GitHub 自动生成。同一标签的发布会串行执行，不被普通分支 CI 取消。

标签遵循带 `v` 前缀的 SemVer，例如 `v0.3.0` 为正式版，`v0.3.0-rc.1`、`v0.3.0-beta.1`、`v0.2.1-android` 为预发布；也支持 `+build.1` 构建元数据。格式错误会在平台构建前失败。

| 平台 | 安装包 | 校验文件 |
| --- | --- | --- |
| Windows | `iap2loc-windows-x64.exe` | `iap2loc-windows-x64.exe.sha256` |
| macOS | `iap2loc-macos-arm64.dmg`、`iap2loc-macos-x64.dmg` | `iap2loc-macos.sha256` |
| Android | `iap2loc-android.apk` | `iap2loc-android.apk.sha256` |

Android 发布需配置[四项 APK 签名 Secrets](README-Android.md#签名与-ci)；[两项 MFi Secrets](README-Android.md#在-ci-中打包内置-mfi-身份) 可选，决定是否内置 MFi 身份。发布使用仓库自带的 `GITHUB_TOKEN`，无需额外 PAT。配置自检和模拟发布测试在相关 PR 中运行，不使用真实身份或创建真实 Release。

如果环境、签名配置或上传临时失败，可在 Actions 中重跑失败的作业，也可在 **Release all platforms → Run workflow** 中填写已有标签重新构建。上传中断会保留草稿，重跑时补齐附件后发布；已公开且附件齐全时跳过重复发布，已公开但附件缺失时停止并要求人工处理。代码修复应创建新标签，不要移动已发布标签；Artifact 已过期时需重跑全部构建。

现有手动发布 Release 和单平台工作流的手动重跑入口仍保留，用于向已有 Release 上传对应平台附件。自动标签发布由统一工作流直接调用构建，完成后不会再触发一轮重复构建。

## 故障排查

| 现象 | 检查方向 |
| --- | --- |
| macOS 没有设备 | 系统蓝牙权限、是否配对、IOBluetooth 枚举 |
| 找到设备但连不上 | SDP 是否公布 iAP2 UUID、RFCOMM channel 是否可用 |
| Identification 被拒绝 | 使用 `--probe -v` 检查识别消息 |
| Authentication 失败 | 检查合法 MFi 身份文件与证书链 |
| 收到订阅但定位不变 | 检查 `0xFFFA`、NMEA、iOS 多源定位融合；不保证定位覆盖 |
| 局域网打不开控制台 | 防火墙是否放行 8737 端口、两台设备是否在同一网段 |
| Actions 成功但 Releases 无 DMG | 普通分支构建只生成 Artifact；标签发布需全部平台通过，查看 Release all platforms 的发布作业和草稿状态 |

## 许可与致谢

本项目遵循 [GPL-3.0](LICENSE)，实现思路参考 [DiPlay](https://github.com/shihabal3amri/DiPlay) 和 [WinPlay](https://github.com/Roylyl/WinPlay)。分发二进制时须遵守 GPL-3.0 的许可证与源码提供义务。仅在自有设备上用于协议研究与合规测试；不要将定位伪造用于欺诈或违反服务条款的场景。

[Linux Do 社区](https://linux.do/)
