# iap2loc

[中文](README.md) · **English**

An iAP2 location client for Windows. It pushes location updates (NMEA) to iOS devices over iAP2 and ships a local web console for picking points and tuning parameters.

iOS fuses the result with its own sources: the accessory is only one input, not a system-level override.

## Quick start

> The console UI is currently in Chinese; the UI labels quoted below are translations.

### 1. Prepare

- Download `iap2loc-windows-x64.exe` from [Releases](https://github.com/D1ngtalk/iap2loc/releases), or [build](#build) your own. Either way you need a working MFi accessory identity (`identity.pk8` + `certificate.p7b`) placed in `./identity`; this repository contains no authentication material.
- Pair the iOS device in Windows Settings → Bluetooth & devices.

### 2. Connect

1. Double-click the downloaded executable; the console opens in your browser at <http://localhost:8737/>
2. Pick the paired iOS device from the **Device** dropdown (only Apple devices are listed by default; tick "show all" to see everything)
3. Click **Add** and confirm three key log lines:
   - `0x1D02 IdentificationAccepted` — identification accepted
   - `0xAA05 AuthenticationSucceeded` — authentication passed, so the identity material is valid
   - `0xFFFA StartLocationInformation` — iOS has subscribed to location updates

### 3. Pick a point

1. Click the target on the map, or fill it from a favorite / your own location
2. Adjust accuracy, altitude and update interval as needed; random offset and accuracy range live under **Advanced**
3. Click **Apply**; the next update (1 s by default) uses it, no reconnection needed
4. Several devices can run at once and share the same target

### 4. Switch the location source on iOS

If the position does not change, go through this sequence:

1. **Turn off Wi-Fi, cellular data and Location Services**, wait ten seconds, then turn Location Services back on — leave Wi-Fi off.
2. **Open Maps** and confirm the source has switched to iAP2 (the blue dot should sit on the chosen coordinate instead of the device's real position).
3. **Turn Wi-Fi on, but keep cellular data off**; turn cellular data back on once the position is stable.

The reason: Wi-Fi and cell positioning, plus cached fixes from other Apple devices, all take part in the system fusion. Remove them first, and the accessory's report becomes the current source.

## Features

- **Map picking**: switch between OSM (WGS-84) and AMap (GCJ-02) tiles with automatic coordinate conversion, so the China datum offset does not show up
- **Live target changes**: click the map, press Apply, and the next report uses it — no reconnect, no re-authentication
- **Multiple devices**: one session per iOS device, all following the same target; each can be stopped or removed from the web console
- **Device discovery**: enumerates paired Bluetooth devices and ranks Apple ones first, using name, device class and OUI
- **Randomization**: optional coordinate offset and accuracy jitter, rolled once when the target is set and then kept fixed
- **Favorites**: saved to `%LOCALAPPDATA%\iap2loc\favorites.json`

## Requirements

| Item | Requirement |
|---|---|
| OS | Windows 10 / 11 x64 |
| Runtime | Release builds are self-contained, no .NET needed; building requires the .NET 10 SDK |
| Bluetooth | With the Bluetooth link, the iOS device must be paired in Windows first |
| Identity | A working MFi accessory identity (`identity.pk8` + `certificate.p7b`); the repository ships none |

## Build

```bash
dotnet build -c Release

# single-file, self-contained, produces dist/iap2loc.exe
dotnet publish -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -o dist
```

## Usage

The GUI walkthrough is in [Quick start](#quick-start). Update interval, NMEA sentence set, identity directory, transport and randomization all live under **Advanced**, together with **rotate accessory identity**, which applies to devices added afterwards. When the iPhone specifies a period while subscribing (`0x8001` / `0x8002` inside `0xFFFA`), that period wins; `--rate` only applies when the device asks for nothing. To start only the console:

```bash
iap2loc serve --port 8737
```

### Command line

```bash
iap2loc devices                       # list paired Bluetooth devices
iap2loc serve --port 8737             # start the console only

iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF \
  --lat 31.2304 --lon 121.4737

iap2loc run --transport tcp --host 127.0.0.1 --port 12345 \
  --lat 31.2304 --lon 121.4737

iap2loc run --transport bt --mac AA:BB:CC:DD:EE:FF --probe   # stop after identification
```

Common options:

| Option | Meaning |
|---|---|
| `--lat` / `--lon` | Target coordinate, default 31.2304, 121.4737 |
| `--alt` / `--speed` / `--bearing` / `--accuracy` | Extra NMEA fields |
| `--sentences gga,rmc,gsa,gsv` | NMEA sentence set, default `gga,rmc` |
| `--rate` | Update interval in ms, default 250, minimum 10 |
| `--identity` | Identity directory; without it the link and identification still work, but authentication will not |
| `--profile` | Accessory identity file, default `%LOCALAPPDATA%\iap2loc\accessory.json` |
| `--new-identity` | Generate and store a new accessory identity (serial, name) |
| `--random-adapter` | Report a randomized Bluetooth address (experimental, may be rejected during identification) |
| `--name` / `--model` / `--serial` … | Override identity fields for this run |
| `--probe` | Exit on `0x1D02` / `0x1D03` |
| `-v` | Protocol-level logging |

## Identity

```bash
iap2loc inspect-identity --identity ./identity   # inspect the certificate chain and extensions
iap2loc run ... --identity ./identity
```

The directory must contain:

```
identity/
├─ identity.pk8        PKCS#8 DER, P-256 private key
└─ certificate.p7b     accessory certificate chain (sent as-is)
```

`--identity` may point anywhere; without it the link and identification still work, only authentication fails. The repository contains no identity material.

## Notes

- **Read the log first**: `--probe` tells you whether identification is accepted without touching authentication; `-v` prints the full protocol exchange.
- **Legal**: use this only on devices you own, for protocol research and privacy testing. Using a spoofed location for attendance, ride-hailing, insurance mileage or games may constitute fraud or breach of contract; an MFi identity of unknown origin may violate Apple's terms. No authentication material is provided here, and you are responsible for the legitimacy of whatever identity you supply.

## License

Released under the GNU General Public License v3.0; see [LICENSE](LICENSE).

The implementation draws on ideas from [DiPlay](https://github.com/shihabal3amri/DiPlay) and [WinPlay](https://github.com/Roylyl/WinPlay) (both GPL-3.0 family), hence the same license here. When distributing binaries, include the license text and a way to obtain the source.
