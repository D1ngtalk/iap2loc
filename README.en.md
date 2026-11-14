# iap2loc

[中文](README.md) · **English**

A cross-platform iAP2 location client for **Windows 10/11 x64** and
**macOS Apple Silicon (arm64) / Intel (x64)**.

It sends location information (NMEA) to iOS devices over the iAP2
protocol and provides a local web console for device management,
map-based location selection, and parameter configuration. iOS combines
multiple location sources, so a successful connection does not guarantee
that the system location will change.

## Downloads and system requirements

Download the appropriate version from
[Releases](https://github.com/D1ngtalk/iap2loc/releases), or [build from
source](#building-from-source-and-ci).

| Platform            | Build artifact                    | Requirements                                                    |
|---------------------|-----------------------------------|-----------------------------------------------------------------|
| Windows 10/11 x64   | `iap2loc-windows-x64.exe`         | Paired Bluetooth device, MFi credentials                        |
| Windows 10/11 x64   | `iap2loc-windows-x64-bundled.exe` | Paired Bluetooth device (no separate credential files required) |
| macOS Apple Silicon | `iap2loc-macos-arm64.dmg`         | macOS Bluetooth permission, paired device, MFi credentials      |
| macOS Intel         | `iap2loc-macos-x64.dmg`           | macOS Bluetooth permission, paired device, MFi credentials      |

Release builds are self-contained and generally do not require a
separate .NET installation. Building from source requires the **.NET 10
SDK**. The macOS build is ad-hoc signed and **has not been notarized
with an Apple Developer ID**. Only approve it through System Settings if
you trust the build source. Do not disable Gatekeeper globally.

## Quick start

1.  **Install**: On Windows, run the downloaded EXE. On macOS, open the
    DMG for your architecture, drag the app into Applications, then
    allow Bluetooth access under **System Settings → Privacy & Security
    → Bluetooth**.

2.  **Pair**: Pair your iPhone in the operating system's Bluetooth
    settings first. On macOS, the app uses IOBluetooth to discover
    paired devices, query SDP, and attempt to establish an RFCOMM
    connection. Seeing a device in the list does not mean that it
    exposes an iAP2 service.

3.  **Open the console**: Launch the application and visit
    <http://127.0.0.1:8737/>.

4.  **Connect a device**: Add your iOS device under **Devices** and make
    sure its connection status changes to **Authenticated**.

5.  **Set coordinates**: Select a location on the map, adjust parameters
    such as accuracy, altitude, and update interval, then click **Apply
    Changes**. Multiple devices can use the same target coordinates.
    Whether the location ultimately changes depends on iOS location
    fusion.

6.  **Switch the location source on the device**:

    1.  **Turn off Wi-Fi, cellular data, and Location Services.** Wait
        ten seconds, then turn Location Services back on. Do not turn
        Wi-Fi on yet.
    2.  **Open Maps** and check whether the location source has switched
        to iAP2. The blue dot should appear at the configured
        coordinates rather than the device's actual location.
    3.  **Turn Wi-Fi on, but keep cellular data off.** Once the location
        stabilizes, turn cellular data back on.

    > **If the location still cannot be changed after repeating these
    > steps:** In some cases, cached data from other location sources
    > may cause Maps to display an approximate-area circle and the
    > message "Unable to determine your location." If this happens, turn
    > off Wi-Fi, cellular data, and Location Services, then restart the
    > device. After restarting, connect over Bluetooth and set the
    > coordinates again, then resume from step 2 above.

## Features

- **Cross-platform transport**: Windows Bluetooth; macOS
  IOBluetooth/SDP/RFCOMM.
- **Web console**: Device list, connection sessions, map-based location
  selection, real-time adjustments, and multi-device management.
- **Maps and coordinates**: OpenStreetMap WGS-84 / Amap GCJ-02,
  automatic coordinate conversion, and saved locations.
- **NMEA**: GGA and RMC sentences; positioning quality (0–8), satellite
  count (0–99), speed, bearing, altitude, and accuracy.
- **Reporting interval**: 250 ms by default, with a minimum of 10 ms.
  The subscription interval specified by the device in `0xFFFA` takes
  precedence over `--rate`.
- **Accessory identity**: `iap2loc.json` stores settings and saved
  locations. By default, the accessory name and serial number are
  rotated at each launch. The Bluetooth address can be managed through
  the configuration. Manual changes to the identity and address are also
  supported. Note that this does not modify the real Bluetooth hardware
  address or guarantee that iOS location fusion will be bypassed.
- **Large-distance changes**: For movements exceeding 100 m, the program
  can include speed and bearing information in transition frames. This
  does not guarantee that iOS will accept the location.

## Command line

Run the executable from its directory on Windows, or from the macOS
app's `Contents/MacOS` directory. Use the actual filename for your
build:

``` bash
./iap2loc devices
./iap2loc serve --port 8737
./iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF --lat 31.2304 --lon 121.4737
./iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF --probe -v
./iap2loc run --transport tcp --host 127.0.0.1 --port 12345 --lat 31.2304 --lon 121.4737
./iap2loc inspect-identity --identity ./identity
```

| Option                                        | Purpose                                                                            |
|-----------------------------------------------|------------------------------------------------------------------------------------|
| `--lat`, `--lon`                              | Target latitude and longitude                                                      |
| `--alt`, `--speed`, `--bearing`, `--accuracy` | Additional NMEA fields                                                             |
| `--quality`, `--satellites`                   | GGA quality and satellite count; defaults to 1 / 8                                 |
| `--rate`                                      | Default interval in milliseconds; the device's subscription value takes precedence |
| `--identity`                                  | MFi identity directory                                                             |
| `--config`                                    | Configuration file path (defaults to `iap2loc.json` in the application directory)  |
| `--fixed-identity`, `--fixed-adapter`         | Disable the corresponding identity or address rotation behavior                    |
| `--probe`, `-v`                               | Identification probe only; verbose protocol logs                                   |

`--sentences` has been removed. Only GGA/RMC are currently sent.

## MFi identity and security

Completing MFi authentication requires accessory identity credentials
**obtained legitimately**. This repository does not include private keys
or certificates:

``` text
identity/
├── identity.pk8       # PKCS#8 DER P-256 private key
└── certificate.p7b    # Accessory certificate chain
```

Use `--identity ./identity` to specify the directory. Without an
identity, you can troubleshoot device discovery, the connection link,
and identification, but MFi authentication cannot be completed. **Do not
commit keys, certificates, secrets in configuration files, or logs
containing real device information to GitHub.**

## Building from source and CI

``` bash
dotnet build Iap2Loc.csproj -c Release
# Windows self-contained single-file build
dotnet publish Iap2Loc.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o dist
# macOS: run the repository script on a Mac with the corresponding architecture
bash scripts/package-macos.sh arm64 dist/iap2loc-macos-arm64.dmg
# For an Intel Mac, use x64 instead
```

macOS packaging also requires Xcode Command Line Tools (`clang`,
`xcrun`). Automation workflows: [macOS](.github/workflows/macos.yml).
The workflow uses separate ARM64 and Intel runners to build, mount, and
smoke-test the DMGs. Regular pushes and pull requests only upload
Actions Artifacts. The two DMGs are attached to an existing Release only
when a release is published or a workflow is manually rerun for a
specified tag. Refer to the result of the specific workflow run for
details.

## Troubleshooting

| Symptom                                                   | What to check                                                                                           |
|-----------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| No devices appear on macOS                                | System Bluetooth permission, pairing status, and IOBluetooth enumeration                                |
| Device is found but cannot connect                        | Whether SDP advertises the iAP2 UUID and whether the RFCOMM channel is available                        |
| Identification is rejected                                | Use `--probe -v` to inspect identification messages                                                     |
| Authentication fails                                      | Check that the MFi identity files and certificate chain are valid and legitimately obtained             |
| Subscription is received but the location does not change | Check `0xFFFA`, NMEA data, and iOS multi-source location fusion; location override is not guaranteed    |
| Actions succeeds but no DMG appears in Releases           | Check whether this was a regular branch build; verify the Release event, publishing job, and asset list |

## License and acknowledgements

This project is licensed under [GPL-3.0](LICENSE). Its implementation is
informed by [DiPlay](https://github.com/shihabal3amri/DiPlay) and
[WinPlay](https://github.com/Roylyl/WinPlay). When distributing
binaries, you must comply with the GPL-3.0 license and its source-code
availability requirements. Use this project only on devices you own for
protocol research and compliance testing. Do not use location spoofing
for fraud or in ways that violate service terms.

[Linux Do Community](https://linux.do/)
