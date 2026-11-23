# golden-vectors

Test tool for the Android port. It compiles the desktop C# sources (`Protocol/`, `Cli.cs`, the
transport interface) into a small console program, so the Kotlin code in `android/core` can be
checked against the implementation that is known to work with real iPhones.

```bash
# Reference outputs for GoldenVectorsTest (CSM, identification, NMEA, geo, route sampling)
dotnet run --project android/tools/golden-vectors/GoldenVectors.csproj -c Release -- \
  vectors "$PWD/android/core/src/test/resources/golden/csharp-vectors.json"

# A minimal iPhone on 127.0.0.1:PORT, driven by the C# Iap2Link in device role. It verifies the
# MFi signature with the given key and prints PASS after ten location frames.
openssl ecparam -name prime256v1 -genkey -noout -out /tmp/key.pem
openssl pkcs8 -topk8 -nocrypt -in /tmp/key.pem -outform DER -out /tmp/identity.pk8
dotnet run --project android/tools/golden-vectors/GoldenVectors.csproj -c Release -- phone 18751 /tmp/identity.pk8 &
cd android && IAP2LOC_INTEROP_PORT=18751 IAP2LOC_INTEROP_KEY=/tmp/identity.pk8 \
  ./gradlew :core:test --tests '*CSharpInteropTest*' --rerun
```

Regenerate the committed vectors whenever `Protocol/` changes; CI always tests against vectors
generated from the current C# code. Use throwaway keys only — never real MFi material.
