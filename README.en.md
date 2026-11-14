# iap2loc

[中文](README.md) · **English**

A cross-platform iAP2 location client for **Windows 10/11 x64** and
**macOS Apple Silicon (arm64) / Intel (x64)**.

It sends location information (NMEA) to iOS devices over the iAP2
protocol and provides a local web console for device management,
map-based location picking, and parameter configuration. iOS fuses
multiple location sources, so a successful connection does not guarantee
that the system location will change.  
>⚠️ macOS is currently only experimentally supported and has not been tested in real environments. Availability is not guaranteed

## Downloads and system requirements

Get the matching build from
[Releases](https://github.com/D1ngtalk/iap2loc/releases), or [build from
source](#building-from-source-and-ci).

| Platform            | Build artifact                    | Requirements                                               |
|---------------------|-----------------------------------|------------------------------------------------------------|
| Windows 10/11 x64   | `iap2loc-windows-x64.exe`         | Paired Bluetooth device, MFi credentials                   |
| Windows 10/11 x64   | `iap2loc-windows-x64-bundled.exe` | Paired Bluetooth device (no credential files required)     |
| macOS Apple Silicon | `iap2loc-macos-arm64.dmg`         | macOS Bluetooth permission, paired device, MFi credentials |
| macOS Intel         | `iap2loc-macos-x64.dmg`           | macOS Bluetooth permission, paired device, MFi credentials |

Release builds are self-contained and normally do not require a separate
.NET installation; building from source requires the **.NET 10 SDK**.
The macOS build is ad-hoc signed and **has not been notarized with an
Apple Developer ID**. Only approve it through System Settings when you
trust the build source, and do not disable Gatekeeper globally.

## Quick start

1.  **Install**: On Windows, run the downloaded EXE. On macOS, open the
    DMG for your architecture, drag the app into Applications, and allow
    access under **System Settings → Privacy & Security → Bluetooth**.

2.  **Pair**: Pair your iPhone in the operating system's Bluetooth
    settings.

3.  **Open the console**: Launch the application and visit
    <http://127.0.0.1:8737/> on the same machine. A phone or another
    computer on the same LAN can open `http://<host IP>:8737/`.

4.  **Connect a device**: Add your iOS device under **Devices** and make
    sure its connection status becomes **Authenticated**.

5.  **Set coordinates**: Pick a location on the map, or use [Path
    mode](#path-mode). Adjust the parameters and click **Apply Changes**.
    Whether the device's location ultimately changes depends on iOS
    location fusion.

6.  **Switch the location source on the device**:

    1.  **Turn off Wi-Fi, cellular data, and Location Services.** Wait
        ten seconds, then turn Location Services back on — do not turn
        Wi-Fi on yet.
    2.  **Open Maps** and confirm the location source has switched to
        iAP2. The blue dot should land on the configured coordinates
        rather than the device's actual location.
    3.  **Turn Wi-Fi on, but keep cellular data off.** Wait for the
        location to stabilize, then turn cellular data back on.

    > **If the location still cannot be changed after repeating these
    > steps:** In some cases, cached data from other location sources
    > can make Maps show an approximate-area circle and the message
    > "Unable to determine your location." If so, turn off Wi-Fi,
    > cellular data, and Location Services and restart the device. After
    > restarting, connect over Bluetooth and set the coordinates again,
    > then resume from step 2.

## Features

- **Cross-platform transport**: Windows Bluetooth; macOS
  IOBluetooth/SDP/RFCOMM.
- **Web console**: Device list, connection sessions, point and path
  modes, real-time adjustments, and multi-device management; the layout
  adapts to narrow screens, and the console log can be expanded at the
  bottom of the panel under Advanced.
- **Maps and coordinates**: OpenStreetMap WGS-84 / Amap GCJ-02,
  automatic coordinate conversion, and saved locations.
- **Point mode**: Pick a coordinate directly on the map, or enter the
  target latitude and longitude.
- **Path mode**: Build a polyline on the map or import a GPX file; a GPX
  with `<time>` is replayed at its recorded pace, otherwise the target
  moves at the configured speed and can loop.
- **Randomisation**: Position offset in metres (0 disables it), random
  accuracy, and random altitude, the latter two with their own ranges;
  values are rolled once on Apply and then kept fixed.
- **NMEA**: GGA and RMC; positioning quality (0–8), satellite count
  (0–99), speed, bearing, altitude, and accuracy.
- **Reporting interval**: 250 ms by default, 10 ms minimum; the
  subscription interval specified by the device in `0xFFFA` takes
  precedence over `--rate`.
- **Accessory identity**: `iap2loc.json` stores settings and saved
  locations. By default, the accessory name and serial number are
  rotated at each launch, and the Bluetooth address can be managed in
  the configuration. Manual identity and address changes are also
  supported. Note that this does not modify the real Bluetooth hardware
  address or guarantee bypassing iOS location fusion.
- **Large-distance changes**: For displacements beyond 100 m, the
  program can carry speed and bearing in the transition frame. This does
  not guarantee that iOS accepts the location.

## Path mode

Switch to **Path** at the top of the console to move the target along a
polyline instead of holding a single point. Mode changes and path edits
only take effect after **Apply Changes**.

- **Map picking**: Click the map in order to build a polyline; drag a
  numbered marker to move it, right-click to delete one point, and undo
  or clear as needed.
- **GPX import**: `trkpt`, `rtept`, and `wpt` are supported; tracks with
  more than 5000 points are downsampled.
- **Timestamp replay**: When every point carries `<time>`, the recording
  pace is reproduced — fast and slow sections and stops are kept as
  recorded — and the NMEA speed is the speed of the current segment.
  Missing timestamps or decreasing times fall back to constant speed.
- **Constant speed**: Move at the configured km/h; enable looping to
  start over at the end.
- **Progress**: The applied line shows point count, length, and distance
  travelled; a timestamped path also shows its total duration, and the
  speed input is disabled.
- **Adding a point clears timestamps**: Clicking the map on an imported
  GPX falls back to constant speed, avoiding data that is half timed and
  half constant.

## Command line

Run from the directory containing the executable on Windows, or from the
macOS app's `Contents/MacOS` directory (use the actual filename):

```bash
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
| `--fixed-identity`, `--fixed-adapter`         | Disable the corresponding identity or address randomisation                        |
| `--probe`, `-v`                               | Identification probe only; verbose protocol logs                                   |

`--sentences` has been removed; only GGA/RMC are sent.

## MFi identity and security

Completing MFi authentication requires accessory identity credentials
**obtained legitimately**. This repository does not include private keys
or certificates:

```text
identity/
├── identity.pk8       # PKCS#8 DER P-256 private key
└── certificate.p7b    # Accessory certificate chain
```

Specify the path with `--identity ./identity`. Without an identity you
can still troubleshoot device discovery, the link, and identification,
but MFi authentication cannot complete. **Do not commit keys,
certificates, secrets from configuration files, or logs containing real
device information to GitHub.**

## Building from source and CI

```bash
dotnet build Iap2Loc.csproj -c Release
# Windows self-contained single file
dotnet publish Iap2Loc.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o dist
# macOS: run the repository script on a Mac with the matching architecture
bash scripts/package-macos.sh arm64 dist/iap2loc-macos-arm64.dmg
# Use x64 for an Intel Mac
```

macOS packaging also requires Xcode Command Line Tools (`clang`,
`xcrun`). Automation workflow: [macOS](.github/workflows/macos.yml); it
builds, mounts, and smoke-tests the DMGs on separate ARM64 and Intel
runners. Regular pushes and pull requests only upload Actions Artifacts;
the two DMGs are attached to an existing Release only when a release is
published or the workflow is manually rerun for a specified tag. Refer
to the result of the specific workflow run for details.

## Troubleshooting

| Symptom                                            | What to check                                                                                           |
|----------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| No devices on macOS                                | System Bluetooth permission, pairing status, and IOBluetooth enumeration                                |
| Device found but cannot connect                    | Whether SDP advertises the iAP2 UUID and whether the RFCOMM channel is available                        |
| Identification rejected                            | Use `--probe -v` to inspect identification messages                                                     |
| Authentication fails                               | Check the legitimately obtained MFi identity files and certificate chain                                |
| Subscription received but location does not change | Check `0xFFFA`, NMEA, and iOS multi-source location fusion; location override is not guaranteed         |
| The console is unreachable from the LAN            | Whether the firewall allows port 8737, and whether both devices are on the same subnet                  |
| Actions succeeds but no DMG appears in Releases    | Check whether this was a regular branch build; verify the Release event, publishing job, and asset list |

## License and acknowledgements

This project is licensed under [GPL-3.0](LICENSE). Its implementation is
informed by [DiPlay](https://github.com/shihabal3amri/DiPlay) and
[WinPlay](https://github.com/Roylyl/WinPlay). When distributing binaries,
you must comply with GPL-3.0 and its source-availability requirements.
Use this only on devices you own for protocol research and compliance
testing; do not use location spoofing for fraud or in ways that violate
service terms.

[Linux Do Community](https://linux.do/)
