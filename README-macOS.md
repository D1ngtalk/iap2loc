# iap2loc macOS Bluetooth 适配版

这是基于 D1ngtalk/iap2loc (GPL-3.0) 的 **非官方** macOS 移植版本。
原始项目：https://github.com/D1ngtalk/iap2loc

## 支持范围

- Apple Silicon (arm64) / Intel (x64) 分别提供自包含 .app，不需要另外安装 .NET。
- 使用原生 IOBluetooth.framework 读取已配对的经典蓝牙设备与本机蓝牙适配器地址。
- 配对设备的 SDP 查询，寻找 iAP2 服务 UUID 00000000-deca-fade-deca-deafdecacafe。
- 根据服务记录获取 RFCOMM channel ID，打开 RFCOMM，按 MTU 分块双向收发字节。
- .NET 原有 iAP2 协议层、TCP 模式、Web 控制台等保留。

## 使用

1. 打开 DMG，将符合自己 CPU 的 .app 拖到 Applications。
2. 双击安装的 .app，允许 macOS 蓝牙权限（系统设置 > 隐私与安全性 > 蓝牙）。
3. Web 控制台在 http://127.0.0.1:8737/ 。默认仅监听本机。
4. 先在 macOS 系统设置 > 蓝牙中配对 iPhone。Mac 所允许看到的设备不一定公布 iAP2 RFCOMM 服务。
5. 在 Web 控制台选择设备并添加。关注 SDP、RFCOMM、iAP2 Identification、MFi Authentication 日志。

终端高级命令，进入 app 的 Contents/MacOS 目录：

    ./iap2loc devices
    ./iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF --probe --connect-timeout 15

也可以用 TCP 模式：

    ./iap2loc run --transport tcp --host 127.0.0.1 --port 12345

结束服务可在 macOS 活动监视器中结束 iap2loc 进程，或对终端前台运行按 Ctrl+C。

## 非常重要的限制

- **设备枚举成功不代表 iAP2 服务存在**。Apple 可能因设备类型、系统版本、配对状态或授权要求而不公开该服务，SDP 找不到 UUID 则 RFCOMM 无法建立。
- iAP2 Identification 与 Authentication 需要实际 iPhone 测试。成功连接 RFCOMM 不等于已通过 iAP2 认证或 iOS 使用配件定位。
- 需要你合法拥有并使用的 MFi 身份（identity.pk8/certificate.p7b），本包不含身份材料，亦不承诺 iOS 定位一定切换。
- 首次运行可能触发蓝牙权限弹窗；未授权将影响设备枚举/连接。
- 本包使用开发机本地临时(ad-hoc)签名，未做 Apple Developer ID 签名及 Apple 公证。来自互联网的 DMG 可能被 Gatekeeper 阻止；只在信任源码和构建来源的情况下从“系统设置 > 隐私与安全性”批准打开，不要全局关闭 Gatekeeper。
- 两种 CPU 版本均已构建；实际运行验证以 Apple Silicon Mac mini 为主，Intel 架构未在 Intel 机器上独立运行验证。

源码与构建脚本包含 Native/MacBluetoothBridge.m、Transport/MacBtRfcommTransport.cs、scripts/package-macos.sh。遵守 GPL-3.0。

## GitHub Actions 全自动构建 / 验证 / 分发

构建不依赖开发者的 Mac。工作流 `.github/workflows/macos.yml` 在分支更新、PR 与版本标签时触发：

`https://github.com/D1ngtalk/iap2loc/actions/workflows/macos.yml`

流水线执行：
1. **矩阵构建**：`macos-15`（Apple Silicon）产出 `iap2loc-macos-arm64.dmg`，`macos-15-intel`（Intel）产出 `iap2loc-macos-x64.dmg`；
2. 每个架构各自编译通用 IOBluetooth 动态库、发布自包含 `.app`、ad-hoc 签名、打包 DMG 并生成 `SHA256SUMS`；
3. **在自己的架构上验证**：挂载刚生成的 DMG，校验 SHA256、签名、可执行文件架构，启动程序并测试 HTTP 控制页面与 JSON API；
4. 通过后上传 Actions Artifacts，保留 30 天；
5. 仅当推送 `v*` 标签且两个架构都通过时，才创建 GitHub Release，附两个 DMG 与 `iap2loc-macos.sha256`。PR 与普通分支不会创建 Release。

macOS 15 GitHub runner 的蓝牙硬件/iPhone 通常不可用。因此 CI 检测 DMG 可安装与可启动、正常调用程序和 Web API；不会伪称通过真实 iAP2、RFComm/MFi 连接、定位改变和授权。最终实机验收是单独的审核关口。

本项目未配置 Apple Developer ID 或公证密钥，发布内容仅 ad-hoc 签名。若未来需要正式公证，必须安全配置 Apple 开发者账户与专用 CI 密钥，并单独设计不向 fork PR 暴露凭据的公证 job。
