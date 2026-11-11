# iap2loc

通过 iAP2 协议向 iPhone 下发定位信息（NMEA），并提供一个本地 Web 控制台用于选点和调参。

iPhone 侧的定位由 iOS 统一融合：该定位会作为配件来源参与融合，并不等于系统级替换。

## 功能

- **地图选点**：OSM（WGS-84）与高德（GCJ-02）底图一键切换，坐标自动换算，避免国内外基准差导致的偏移
- **实时改点**：点地图 → 点「应用修改」，下一次上报即生效，无需重连或重新认证
- **多设备**：每个 iPhone 一条独立会话，共享同一个目标点；Web 端可单独停止或移除
- **设备发现**：自动枚举已配对的蓝牙设备，按名称 / 设备类 / OUI 判断并优先展示 Apple 设备
- **随机化**：可选的坐标偏移与精度抖动，在设定目标时掷一次，之后固定不变
- **收藏**：常用坐标保存在 `%LOCALAPPDATA%\iap2loc\favorites.json`

## 环境

| 项 | 要求 |
|---|---|
| 系统 | Windows 10 / 11 x64 |
| 运行时 | 发布版自包含，无需安装 .NET；构建需要 .NET 10 SDK |
| 蓝牙 | 使用蓝牙链路时，iPhone 需先在 Windows 蓝牙设置里完成配对 |
| 身份 | 需要一份可用的 MFi 配件身份（`identity.pk8` + `certificate.p7b`）；仓库不包含任何身份材料 |

## 构建

```bash
dotnet build -c Release

# 单文件、自包含，产出 dist/iap2loc.exe
dotnet publish -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o dist
```

## 使用

### 图形界面

双击 `iap2loc.exe`（不带参数）会启动控制台并自动打开浏览器，默认端口 8737：

```
http://localhost:8737/
```

也可以显式启动：`iap2loc serve --port 8737`

界面流程：选设备 → 添加 → 点地图选点 → 点「应用修改」。其余参数（周期、句子集、身份目录、传输方式）在「高级设置」里。

### 命令行

```bash
iap2loc devices                       # 列出已配对的蓝牙设备
iap2loc serve --port 8737             # 只启动控制台

iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF \
  --lat 31.2304 --lon 121.4737 --rate 1000

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
| `--rate` | 上报周期（毫秒），默认 1000，最小 200 |
| `--identity` | 身份目录；缺省时使用内置身份 |
| `--name` / `--model` / `--serial` … | 配件身份展示字段 |
| `--probe` | 收到 `0x1D02` / `0x1D03` 即退出 |
| `-v` | 打印协议级日志 |

## 身份

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

- **iPhone 侧配合**：若定位未生效，可先关闭定位服务、Wi-Fi、蓝牙，等待十余秒后重新打开再连接。其他 Apple 设备缓存的定位会参与融合。
- **失败优先看日志**：`--probe` 可在不涉及认证的情况下确认识别是否被接受；`-v` 打印完整的协议往返。
- **合规**：请仅用于自有设备、协议研究与隐私测试。将定位伪造用于考勤、网约车、保险里程、游戏等场景可能构成欺诈或违约；使用来源不明的 MFi 身份可能违反 Apple 的授权条款。仓库不提供任何认证材料，使用者需自行确保身份来源合法。

## 许可

本项目以 GNU General Public License v3.0 发布，全文见 [LICENSE](LICENSE)。

实现思路参考了 [DiPlay](https://github.com/shihabal3amri/DiPlay) 与 [WinPlay](https://github.com/Roylyl/WinPlay)（两者均为 GPL-3.0 系），因此同样采用 GPL-3.0。分发二进制时请一并附带许可证文本与源码获取方式。
