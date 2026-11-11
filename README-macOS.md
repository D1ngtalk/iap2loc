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

构建不依赖开发者的 Mac mini。GitHub Actions 工作流在仓库分支有更新时自动触发：
`https://github.com/shzxm/iap2loc/actions/workflows/macos-dmg.yml`

流水线执行：
1. **Intel GitHub-hosted `macos-15-intel` runner** 安装 .NET 10，编译通用 IOBluetooth 动态库与 ARM64、Intel 两个独立 .app。此步骤不依赖 Apple Silicon runner 的排队情况；
2. ad-hoc 本地签名，打包包含许可证、源码和补丁的 DMG，生成 SHA256SUMS；
3. **在 Intel 构建机上**直接挂载刚生成的 DMG，验证 SHA256、签名和 x86_64 可执行文件，启动 Intel app 并测试 HTTP 控制界面与 JSON API；
4. **立刻上传已通过 Intel 验收的 DMG**、源码与校验和作为 Actions Artifacts，保留 30 天。即使后续 ARM64 runner 暂时分配不到，构建产物仍可下载；
5. **另一台 Apple Silicon GitHub-hosted `macos-15` runner** 下载同一份 DMG，在 ARM64 CPU 上复验 SHA256、签名、ARM64 app 启动及 HTTP API。ARM64 验收独立于 Intel 的构建/上传，不能反过来阻断已上传的产物；
6. 仅当用户 Fork `shzxm/iap2loc` 推送 `v*` 版本标签，且 Intel 与 ARM64 测试**全部成功**时，通过工作流发布 GitHub **Prerelease**，附 DMG、源码和校验和。不在 PR 或普通分支提交时自动创建 Release。

macOS 15 GitHub runner 的蓝牙硬件/iPhone 通常不可用。因此 CI 检测 DMG 可安装与可启动、正常调用程序和 Web API；不会伪称通过真实 iAP2、RFComm/MFi 连接、定位改变和授权。最终实机验收是单独的审核关口。

本项目未配置 Apple Developer ID 或公证密钥，发布内容仅 ad-hoc 签名。若未来需要正式公证，必须安全配置 Apple 开发者账户与专用 CI 密钥，并单独设计不向 fork PR 暴露凭据的公证 job。
