using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Iap2Loc.Protocol;
using Iap2Loc.Transport;

namespace Iap2Loc.Tools;

internal static class Program
{
    private static int Main(string[] args) => args.FirstOrDefault() switch
    {
        "vectors" => Vectors(args.Length > 1 ? args[1] : null),
        "phone" => Phone(int.Parse(args[1], CultureInfo.InvariantCulture), args[2]),
        _ => Usage(),
    };

    private static int Usage()
    {
        Console.Error.WriteLine("usage: vectors [out.json] | phone PORT identity.pk8");
        return 2;
    }

    private static string Hex(byte[] data) => Convert.ToHexString(data);

    private static int Vectors(string? output)
    {
        var identity = new AccessoryIdentity("iAP2 Loc 7F3A", "Loc1,1", "carplay-loc", "LC00A1B2C3D4E5", "0.1.0", "1.0");
        var adapter = new byte[] { 0x3C, 0x22, 0xFB, 0x01, 0x02, 0x03 };

        var nmeaCases = new List<object>();
        void Nmea(double lat, double lon, double? alt, double? bearing, double? speed, double? accuracy, int quality, int satellites, string utc)
        {
            var time = DateTime.Parse(utc, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal);
            nmeaCases.Add(new
            {
                lat, lon, alt, bearing, speed, accuracy, quality, satellites, utc,
                text = Iap2Loc.Protocol.Nmea.Encode(lat, lon, alt, bearing, speed, accuracy, quality, satellites, time),
            });
        }

        Nmea(31.2304, 121.4737, 10, null, 0, 5, 1, 8, "2026-11-23T08:15:42Z");
        Nmea(-33.86882, 151.20929, 58.25, 271.456, 13.889, 2.5, 4, 14, "2026-01-02T23:59:59Z");
        Nmea(40.7127753, -74.0059728, null, 0.004, 250, null, 8, 120, "2026-06-30T00:00:00Z");
        Nmea(0.0, 0.0, -12.34, 359.999, null, 300, -3, -1, "2026-02-28T12:34:56Z");
        Nmea(89.9999999, -179.9999999, 8848.86, 90, 1.4, 0.1, 2, 3, "2026-12-31T23:00:01Z");
        Nmea(-0.000001, 0.000001, 0.05, 45.005, 0.005, 12.25, 1, 9, "2026-07-04T04:05:06Z");

        var geoPairs = new[]
        {
            (new GeoPoint(31.2304, 121.4737), new GeoPoint(39.9042, 116.4074)),
            (new GeoPoint(-33.8688, 151.2093), new GeoPoint(51.5074, -0.1278)),
            (new GeoPoint(0, 179.9), new GeoPoint(0, -179.9)),
            (new GeoPoint(35.6762, 139.6503), new GeoPoint(35.6763, 139.6504)),
        };

        var route = new RoutePath(new[]
        {
            new RouteVertex(new GeoPoint(31.2304, 121.4737, 5), 1000),
            new RouteVertex(new GeoPoint(31.2314, 121.4747, 9), 1060),
            new RouteVertex(new GeoPoint(31.2334, 121.4747, null), 1200),
            new RouteVertex(new GeoPoint(31.2334, 121.4797, 20), 1500),
        });

        var vectors = new
        {
            csm = Hex(Csm.Encode(0x1D01, Csm.Param(0, new byte[] { 1, 2, 3 }), Csm.Param(5, Array.Empty<byte>()))),
            identificationWithAdapter = Hex(Identification.Build(identity, adapter)),
            identificationWithoutAdapter = Hex(Identification.Build(identity, null)),
            nmea = nmeaCases,
            geo = geoPairs.Select(pair => new
            {
                a = new[] { pair.Item1.Latitude, pair.Item1.Longitude },
                b = new[] { pair.Item2.Latitude, pair.Item2.Longitude },
                distance = Geo.Distance(pair.Item1, pair.Item2),
                bearing = Geo.Bearing(pair.Item1, pair.Item2),
            }),
            offset = new[] { (10.0, 0.5), (250.0, 3.0), (0.0, 1.0) }.Select(item =>
            {
                var moved = Geo.Offset(new GeoPoint(31.2304, 121.4737), item.Item1, item.Item2);
                return new { distance = item.Item1, angle = item.Item2, lat = moved.Latitude, lon = moved.Longitude };
            }),
            longitudes = new[] { 181.0, -181.0, 540.0, -720.5, 179.999, 360.0 }
                .Select(value => new { input = value, output = Geo.NormalizeLongitude(value) }),
            route = new
            {
                length = route.LengthMeters,
                duration = route.DurationSeconds,
                at = new[] { -5.0, 0, 37.5, 150, 300, 420.25, 10_000 }.Select(distance => Sample(distance, route.At(distance))),
                atTime = new[] { 0.0, 30, 59.9, 60, 130, 499, 650 }.Select(seconds => Sample(seconds, route.AtTime(seconds, loop: true))),
                atTimeNoLoop = new[] { 650.0 }.Select(seconds => Sample(seconds, route.AtTime(seconds, loop: false))),
            },
        };

        var json = JsonSerializer.Serialize(vectors, new JsonSerializerOptions { WriteIndented = true });
        if (output is null) Console.WriteLine(json);
        else File.WriteAllText(output, json + "\n");
        return 0;
    }

    private static object Sample(double input, RouteSample sample) => new
    {
        input,
        lat = sample.Point.Latitude,
        lon = sample.Point.Longitude,
        alt = sample.Point.Altitude,
        bearing = sample.Bearing,
        speed = sample.SpeedMetersPerSecond,
        distance = sample.DistanceMeters,
    };

    /// <summary>
    /// A minimal iPhone: identification, MFi challenge (verified against the key), then a location
    /// subscription. Prints PASS once the accessory streamed a few NMEA frames.
    /// </summary>
    private static int Phone(int port, string keyPath)
    {
        using var key = ECDsa.Create();
        key.ImportPkcs8PrivateKey(File.ReadAllBytes(keyPath), out _);

        var listener = new TcpListener(IPAddress.Loopback, port);
        listener.Start();
        Console.WriteLine($"phone listening on 127.0.0.1:{port}");
        using var client = listener.AcceptTcpClient();
        using var transport = TcpTransport.FromClient(client, "phone");
        using var link = new Iap2Link(transport, initiate: false, "phone");

        var done = new ManualResetEventSlim();
        var result = 1;
        var challenge = RandomNumberGenerator.GetBytes(32);
        var frames = 0;

        void Finish(int code, string message)
        {
            Console.WriteLine(message);
            result = code;
            done.Set();
        }

        link.Ready += () => link.Send(0x1D00);
        link.Failed += reason => Finish(1, "FAIL link: " + reason);
        link.CsmReceived += (id, parameters) =>
        {
            switch (id)
            {
                case 0x1D01:
                    var name = Encoding.UTF8.GetString(parameters[0]).TrimEnd('\0');
                    var hasLocation = parameters.ContainsKey(22);
                    Console.WriteLine($"identification from '{name}', location component {hasLocation}, transport 17 {parameters.ContainsKey(17)}");
                    if (!hasLocation) { Finish(1, "FAIL no location component"); return; }
                    link.Send(0x1D02);
                    link.Send(0xAA00);
                    break;

                case 0xAA01:
                    Console.WriteLine($"certificate {parameters[0].Length} bytes");
                    link.Send(0xAA02, Csm.Param(0, challenge));
                    break;

                case 0xAA03:
                    var signature = parameters[0];
                    var valid = signature.Length == 64 &&
                                key.VerifyHash(challenge, signature, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
                    Console.WriteLine($"signature {signature.Length} bytes, valid {valid}");
                    if (!valid) { Finish(1, "FAIL signature"); return; }
                    link.Send(0xAA05);
                    link.Send(0xFFFA, Csm.Param(1, Array.Empty<byte>()), Csm.Param(2, Array.Empty<byte>()),
                        Csm.Param(0x8001, new byte[] { 0, 0, 0, 100 }), Csm.Param(0x8002, new byte[] { 0, 0, 0, 100 }));
                    break;

                case 0xFFFB:
                    var first = Encoding.UTF8.GetString(parameters[0]).TrimEnd('\0', '\r', '\n');
                    if (++frames == 1) Console.WriteLine("first frame: " + first);
                    if (frames >= 10) Finish(0, $"PASS {frames} location frames, last: {first}");
                    break;
            }
        };

        link.Start();
        if (!done.Wait(TimeSpan.FromSeconds(40))) Console.WriteLine("FAIL timeout");
        listener.Stop();
        return result;
    }
}
