# iap2loc

**中文** · [English](README.en.md) · [macOS 技术说明](README-macOS.md)

跨平台 iAP2 定位客户端，支持 **Windows 10/11 x64** 与 **macOS Apple Silicon (arm64) / Intel (x64)**。通过蓝牙经典协议或 TCP 传输 iAP2/NMEA 定位信息，并提供本地 Web 控制台管理设备、地图选点和参数。iOS 会融合多种定位来源，因此连接成功不保证系统定位一定改变。

> **验证边界**：Windows 与 macOS 均有构建流程；macOS CI 验证编译、DMG 挂载、签名、程序启动和 Web/API 冒烟测试。CI 不具备真实 iPhone/MFi 硬件，不能据此宣称 iAP2 认证或定位修改已经通过实机测试。

## 下载与系统要求

从 [Releases](https://github.com/D1ngtalk/iap2loc/releases) 获取对应版本；如果没有可下载的 Release，请查看 [Actions](https://github.com/D1ngtalk/iap2loc/actions) 的构建产物（Artifacts 可能要求登录且有保留期限）。

| 平台 | 构建产物 | 要求 |
| --- | --- | --- |
| Windows 10/11 x64 | `iap2loc-windows-x64.exe` | 已配对的蓝牙设备 |
| macOS Apple Silicon | `iap2loc-macos-arm64.dmg` | macOS 蓝牙权限、已配对设备 |
| macOS Intel | `iap2loc-macos-x64.dmg` | macOS 蓝牙权限、已配对设备 |

发布版为自包含程序，通常无需另装 .NET；源码构建需要 **.NET 10 SDK**。macOS 版本为 ad-hoc 签名、**未进行 Apple Developer ID 公证**，仅在信任构建来源时通过系统设置批准打开，不要全局关闭 Gatekeeper。

## 快速开始

1. **安装**：Windows 运行下载的 EXE；macOS 打开对应架构 DMG，将 App 拖入 Applications，并在「系统设置 → 隐私与安全性 → 蓝牙」允许访问。
2. **配对**：先在操作系统的蓝牙设置中配对 iPhone。macOS 使用 IOBluetooth 发现已配对设备、查询 SDP 并尝试建立 RFCOMM；设备被列出不代表它公开 iAP2 服务。
3. **打开控制台**：启动应用，访问 <http://127.0.0.1:8737/>；选择设备并添加。必要时勾选「显示全部」。
4. **查看日志**：`0x1D02 IdentificationAccepted` 表示识别接受；`0xAA05 AuthenticationSucceeded` 表示认证成功；`0xFFFA StartLocationInformation` 表示订阅定位。**这三个状态不能互相替代。**
5. **设置坐标**：在地图上选择位置，调整精度、高度、周期等，点击「应用修改」。多设备可使用同一目标坐标。定位最终是否变化取决于 iOS 的定位融合。

## 功能

- **跨平台传输**：Windows 蓝牙、macOS IOBluetooth/SDP/RFCOMM；保留 TCP 模式。
- **Web 控制台**：设备列表、连接会话、地图选点、实时调整、多设备管理。
- **地图与坐标**：OpenStreetMap WGS-84 / 高德 GCJ-02，自动坐标转换；收藏点。
- **NMEA**：GGA 和 RMC；定位质量（0–8）、卫星数（0–99）、速度、航向、高度和精度。
- **上报周期**：默认 250 ms、最小 10 ms；设备在 `0xFFFA` 指定的订阅周期优先于 `--rate`。
- **配件身份**：配置文件 `iap2loc.json` 保存配置及收藏；默认每次启动轮换配件名称与序列号，蓝牙地址可由配置管理；支持手动更换身份与地址。请注意这不等于修改真实蓝牙硬件地址或保证绕过 iOS 定位融合。
- **跨距离变化**：对超过 100 m 的位移，程序可在过渡帧携带速度与航向信息。此行为不保证 iOS 接受该位置。

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

完成 MFi 认证需要**合法取得**的配件身份，仓库和发布包均不包含私钥或证书：

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
```

macOS 打包还需要 Xcode Command Line Tools（`clang`、`xcrun`）。自动化工作流：[macOS](.github/workflows/macos.yml)；它分别使用 ARM64 与 Intel runner 构建、挂载并冒烟测试 DMG。普通 push/PR 仅上传 Actions Artifact；发布或手动重跑指定 tag 时才尝试将两个 DMG 附加到已有 Release。具体以该次工作流结果为准。

## 故障排查

| 现象 | 检查方向 |
| --- | --- |
| macOS 没有设备 | 系统蓝牙权限、是否配对、IOBluetooth 枚举 |
| 找到设备但连不上 | SDP 是否公布 iAP2 UUID、RFCOMM channel 是否可用 |
| Identification 被拒绝 | 使用 `--probe -v` 检查识别消息 |
| Authentication 失败 | 检查合法 MFi 身份文件与证书链 |
| 收到订阅但定位不变 | 检查 `0xFFFA`、NMEA、iOS 多源定位融合；不保证定位覆盖 |
| Actions 成功但 Releases 无 DMG | 查看是否为普通分支构建；核对 Release 事件、发布 Job 和资产列表 |

## 许可与致谢

本项目遵循 [GPL-3.0](LICENSE)，实现思路参考 [DiPlay](https://github.com/shihabal3amri/DiPlay) 和 [WinPlay](https://github.com/Roylyl/WinPlay)。分发二进制时须遵守 GPL-3.0 的许可证与源码提供义务。仅在自有设备上用于协议研究与合规测试；不要将定位伪造用于欺诈或违反服务条款的场景。

[Linux Do 社区](https://linux.do/)
