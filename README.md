# iap2loc

**中文** · [English](README.en.md)

Windows 下的 iAP2 定位客户端，通过 iAP2 协议向 iOS 设备下发定位（NMEA），并提供一个本地 Web 控制台用于选点和调参。

定位最终由 iOS 统一融合：配件只是其中一个来源，并不等于系统级替换。

## 快速开始

### 1. 准备

- 从 [Releases](https://github.com/D1ngtalk/iap2loc/releases) 下载 `iap2loc-windows-x64.exe` 或 [构建](#构建) 你自己的版本 (需要一份可用的 MFi 配件身份 `identity.pk8` + `certificate.p7b` ，放入 `./identity` 文件夹，仓库不包含任何身份材料)
- 在 Windows「设置 → 蓝牙和其他设备」中配对 iOS 设备

### 2. 连接

1. 双击下载的 exe，浏览器会自动打开控制台 <http://localhost:8737/>
2. 在「设备」下拉中选中刚配对的 iOS 设备（默认只列 Apple 设备，可勾「显示全部」）
3. 点「添加」，按日志确认三个关键节点：
   - `0x1D02 IdentificationAccepted`：识别通过
   - `0xAA05 AuthenticationSucceeded`：认证通过，说明身份材料有效
   - `0xFFFA StartLocationInformation`：iOS 已订阅定位

### 3. 选点

1. 在地图上点击目标位置，或用收藏、本机定位填充
2. 按需调整精度、高度、上报周期；随机偏移与精度区间在「高级设置」里
3. 点「应用修改」，下一次上报（默认 1 秒）即生效，不需要重连
4. 多台设备可以同时连接，共享同一个目标点

### 4. 在 iOS 上切换定位源

定位没有变化时，按下面的顺序走一遍：

1. **关闭 Wi-Fi、数据流量和定位服务**，等待十秒后重新打开定位服务——此时不要打开 Wi-Fi。
2. **打开「地图」**，确认定位源已经切换到 iAP2（蓝点应落在设定的坐标上，而不是设备实际所在位置）。
3. **打开 Wi-Fi，但不要打开数据流量**，等定位稳定后再打开数据流量。

原因是 Wi-Fi 与基站定位、以及其他 Apple 设备缓存的定位都会参与系统融合，
先把这些来源摘掉，配件上报的位置才会被当成当前来源。

## 功能

- **地图选点**：OSM（WGS-84）与高德（GCJ-02）底图一键切换，坐标自动换算，避免国内外基准差导致的偏移
- **实时改点**：点地图 → 点「应用修改」，下一次上报即生效，无需重连或重新认证
- **多设备**：每台 iOS 设备一条独立会话，共享同一个目标点；Web 端可单独停止或移除
- **设备发现**：自动枚举已配对的蓝牙设备，按名称 / 设备类 / OUI 判断并优先展示 Apple 设备
- **随机化**：可选的坐标偏移与精度抖动，在设定目标时掷一次，之后固定不变
- **收藏**：常用坐标保存在 `%LOCALAPPDATA%\iap2loc\favorites.json`

## 环境

| 项 | 要求 |
|---|---|
| 系统 | Windows 10 / 11 x64 |
| 运行时 | 发布版自包含，无需安装 .NET；构建需要 .NET 10 SDK |
| 蓝牙 | 使用蓝牙链路时，iOS 设备需先在 Windows 蓝牙设置里完成配对 |
| 身份 | 需要一份可用的 MFi 配件身份（`identity.pk8` + `certificate.p7b`）；仓库不包含任何身份材料 |

## 构建

```bash
dotnet build -c Release

# 单文件、自包含，产出 dist/iap2loc.exe
dotnet publish -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o dist
```

## 使用

图形界面的完整流程见[快速开始](#快速开始)。周期、句子集、MFi 密钥、传输方式、随机偏移都在「高级设置」里；配件身份每次启动自动更换，也可在那里立即换一次。
如果 iPhone 在订阅定位时指定了上报间隔（`0xFFFA` 里的 `0x8001` / `0x8002`），以设备指定的为准，`--rate` 只作为设备没提要求时的默认值。可以只启动控制台：

```bash
iap2loc serve --port 8737
```

### 命令行

```bash
iap2loc devices                       # 列出已配对的蓝牙设备
iap2loc serve --port 8737             # 只启动控制台

iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF \
  --lat 31.2304 --lon 121.4737

iap2loc run --transport tcp --host 127.0.0.1 --port 12345 \
  --lat 31.2304 --lon 121.4737

iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF --probe   # 只验证到识别为止
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--lat` / `--lon` | 目标坐标，默认 31.2304, 121.4737 |
| `--alt` / `--speed` / `--bearing` / `--accuracy` | 写入 NMEA 的附加字段 |
| `--sentences gga,rmc,gsa,gsv` | NMEA 句子集，默认 `gga,rmc` |
| `--rate` | 上报周期（毫秒），默认 250，最小 10 |
| `--identity` | MFi 密钥目录（`identity.pk8` + `certificate.p7b`）；缺省时链路与识别可用，认证不会通过 |
| `--config` | 配置文件，默认程序目录下的 `iap2loc.json` |
| `--fixed-identity` | 不轮换配件身份（默认每次启动换一个 serial） |
| `--fixed-adapter` | 上报本机蓝牙地址（默认首次启动生成一个随机值并固定） |
| `--name` / `--model` / `--serial` … | 覆盖本次运行的身份字段 |
| `--probe` | 收到 `0x1D02` / `0x1D03` 即退出 |
| `-v` | 打印协议级日志 |

## 配置与收藏

程序目录下的 `iap2loc.json` 保存配件身份、随机开关和收藏点：

- 每次启动生成新的 `serial` 与 `name`，iOS 会把它当成一台新配件。iOS 按配件记住上一次的定位，跨城修改时这一步是必要的。
- 蓝牙地址在首次启动时生成，之后固定，配对关系不变。
- 收藏点写在同一个文件里。
- `--config` 可以指向别的文件；`--fixed-identity` / `--fixed-adapter` 临时关掉随机；删掉文件即回到默认身份。

## MFi 密钥

```bash
iap2loc inspect-identity --identity ./identity   # 查看证书链与扩展
iap2loc run ... --identity ./identity
```

目录内需包含：

```
identity/
├─ identity.pk8        PKCS#8 DER，P-256 私钥
└─ certificate.p7b     配件证书链（原样下发）
```

`--identity` 可以指向任意位置；不提供时链路与识别仍可用，只是认证不会通过。仓库不包含任何身份材料。

## 注意事项

- **失败优先看日志**：`--probe` 可在不涉及认证的情况下确认识别是否被接受；`-v` 打印完整的协议往返。
- **合规**：请仅用于自有设备、协议研究与隐私测试。将定位伪造用于考勤、网约车、保险里程、游戏等场景可能构成欺诈或违约；使用来源不明的 MFi 身份可能违反 Apple 的授权条款。仓库不提供任何认证材料，使用者需自行确保身份来源合法。

## 许可

本项目以 GNU General Public License v3.0 发布，全文见 [LICENSE](LICENSE)。

实现思路参考了 [DiPlay](https://github.com/shihabal3amri/DiPlay) 与 [WinPlay](https://github.com/Roylyl/WinPlay)（两者均为 GPL-3.0 系），因此同样采用 GPL-3.0。分发二进制时请一并附带许可证文本与源码获取方式。

[Linux Do 社区](https://linux.do/) 
