# AGENTS.md

## Layout

- Desktop (Windows / macOS): C# in the repository root (`Protocol/`, `Engine/`, `Transport/`, `Web/`), `Iap2Loc.csproj`, .NET 10.
- Android: Kotlin in `android/` (Gradle). `android/core` is a pure-JVM port of `Protocol/`, `Engine/` and `Web/WebServer.cs`;
  `android/app` adds the Bluetooth transport, foreground service and Compose UI. `Iap2Loc.csproj` excludes `android/**`.
- `Web/index.html` is shared: the desktop embeds it, `android/core` packages it as `/web/index.html`. Keep the JSON API
  of `Web/WebServer.cs` and `android/core/.../web/WebServer.kt` identical.

## Commands

```bash
dotnet build Iap2Loc.csproj -c Release                 # desktop
cd android && ./gradlew :core:test                      # protocol/engine tests, incl. parity with C# golden vectors
cd android && ./gradlew :app:lintRelease :app:assembleRelease
bash scripts/smoke-android.sh android/app/build/outputs/apk/release/app-release.apk   # needs an emulator or device
```

- After changing anything in `Protocol/`, regenerate the vectors and port the change to `android/core`:
  `dotnet run --project android/tools/golden-vectors/GoldenVectors.csproj -c Release -- vectors "$PWD/android/core/src/test/resources/golden/csharp-vectors.json"`.
- C# ↔ Kotlin interop run: see `android/tools/golden-vectors/README.md`.
- Local Android SDK: `android/local.properties` with `sdk.dir=...` (not committed).

## Rules

- Never commit MFi material (`*.pk8`, `*.p7b`), `iap2loc.json`, or signing keys (`*.jks`, `*.keystore`). Tests generate throwaway keys.
- Android release signing only via the `IAP2LOC_KEYSTORE*` environment variables / CI secrets.
