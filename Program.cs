using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Iap2Loc.Engine;
using Iap2Loc.Protocol;
using Iap2Loc.Transport;
using Iap2Loc.Web;

namespace Iap2Loc;

internal static class Program
{
    private static int Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        var launchedByDoubleClick = args.Length == 0;

        try
        {
            if (launchedByDoubleClick)
            {
                // Double-clicking the exe opens the control page; no console arguments to type.
                return RunWeb(Options.Parse(Array.Empty<string>()), openBrowser: true);
            }

            if (args[0] is "-h" or "--help" or "help")
            {
                Help();
                return 0;
            }

            var command = args[0];
            var options = Options.Parse(args.Skip(1).ToArray());
            Log.Verbose = options.Flag("verbose") || options.Flag("v");

            return command switch
            {
                "run" => Run(options),
                "devices" => Devices(),
                "inspect-identity" => InspectIdentity(options),
                "serve" or "web" => RunWeb(options),
                _ => Unknown(command),
            };
        }
        catch (Exception ex)
        {
            Log.Error(ex.Message);
            if (ex.InnerException is not null)
            {
                Log.Error("  " + ex.InnerException.Message);
            }

            // A double-clicked console closes instantly; keep the message readable.
            if (launchedByDoubleClick)
            {
                Pause();
            }

            return 1;
        }
    }

    private static void Pause()
    {
        Console.WriteLine();
        Console.WriteLine("按回车退出…");
        try
        {
            Console.ReadLine();
        }
        catch
        {
            // No console attached.
        }
    }

    private static int RunWeb(Options options, bool openBrowser = false)
    {
        var port = options.Int("port", 8737);
        var manager = new SessionManager(options.Double("lat", 31.2304), options.Double("lon", 121.4737));
        var favorites = new FavoriteStore();

        // Let a custom build warm up its identity source before any session starts.
        IdentitySource.Prime();

        using var server = new WebServer(port, manager, favorites);
        server.Start();

        if (openBrowser)
        {
            OpenBrowser($"http://localhost:{server.Port}/");
        }

        Log.Info("控制页面已就绪，Ctrl+C 退出");
        var stop = new ManualResetEventSlim();
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            stop.Set();
        };

        stop.Wait();
        server.Dispose();
        manager.Dispose();
        return 0;
    }

    /// <summary>Opens the control page. IAP2LOC_NO_BROWSER=1 suppresses it (useful for tests).</summary>
    private static void OpenBrowser(string url)
    {
        if (Environment.GetEnvironmentVariable("IAP2LOC_NO_BROWSER") == "1")
        {
            return;
        }

        try
        {
            if (OperatingSystem.IsWindows())
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            else if (OperatingSystem.IsMacOS())
            {
                Process.Start("open", url);
            }
            else
            {
                Process.Start("xdg-open", url);
            }
        }
        catch (Exception ex)
        {
            Log.Warn("无法自动打开浏览器：" + ex.Message);
        }
    }

    private static int Unknown(string command)
    {
        Log.Error($"unknown command '{command}'");
        Help();
        return 2;
    }

    private static int Devices()
    {
        BluetoothDevices.List();
        return 0;
    }

    private static int Run(Options options)
    {
        var transportName = options.Text("transport", "bt").ToLowerInvariant();
        var identityDirectory = options.Text("identity");

        var signer = IdentitySource.Resolve(identityDirectory, out var identitySource);
        if (signer is not null)
        {
            Log.Info($"MFi material: {identitySource}, certificate {signer.Certificate.Length} bytes");
        }
        else
        {
            Log.Warn("no identity available: identification and the link will work, authentication will not. " +
                     "The phone ignores location data from an unauthenticated accessory.");
        }

        using var transport = OpenTransport(transportName, options);

        byte[]? bluetoothAdapter = null;
        if (transportName is "bt" or "bluetooth")
        {
            bluetoothAdapter = BluetoothDevices.TryGetLocalAdapterAddress();
            if (bluetoothAdapter is null)
            {
                Log.Warn("could not read the local Bluetooth adapter address; iOS rejects identification " +
                         "without transport component 17");
            }
            else
            {
                Log.Info($"local Bluetooth adapter {string.Join(':', bluetoothAdapter.Select(b => b.ToString("X2")))}");
            }
        }

        var identity = new AccessoryIdentity(
            options.Text("name", "iAP2 Loc"),
            options.Text("model", "Loc1,1"),
            options.Text("manufacturer", "carplay-loc"),
            options.Text("serial", "LOC000000001"),
            options.Text("firmware", "0.1.0"),
            options.Text("hardware", "1.0"));

        var target = new LocationTarget(
            options.Double("lat", 31.2304),
            options.Double("lon", 121.4737));

        target.Apply(new TargetUpdate
        {
            Altitude = options.NullableDouble("alt"),
            Speed = options.NullableDouble("speed"),
            Bearing = options.NullableDouble("bearing"),
            Accuracy = options.NullableDouble("accuracy"),
        });

        var rate = Math.Max(200, options.Int("rate", 200));

        using var link = new Iap2Link(transport, initiate: true, "accessory");
        using var session = new LocationSession(
            link,
            target,
            identity,
            signer,
            rate,
            Nmea.ParseSentences(options.Text("sentences")),
            bluetoothAdapter);

        var stop = new ManualResetEventSlim();

        if (options.Flag("probe"))
        {
            session.IdentificationFinished += (accepted, detail) =>
            {
                if (accepted)
                {
                    Log.Ok("PROBE: identification accepted; stopping before authentication");
                }
                else
                {
                    Log.Error($"PROBE: identification rejected ({detail})");
                }

                stop.Set();
            };
        }
        link.Ready += () =>
        {
            Log.Ok("iAP2 link ready, waiting for the phone to identify the accessory");
        };
        link.Failed += reason =>
        {
            if (!stop.IsSet)
            {
                Log.Error("link failed: " + reason);
            }

            stop.Set();
        };

        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            stop.Set();
        };

        var snapshot = target.Snapshot();
        Log.Step($"accessory '{identity.Name}' over {transport.Describe}, " +
                 $"target {snapshot.Latitude:F6},{snapshot.Longitude:F6} every {rate} ms");

        link.Start();

        var duration = options.Int("duration", 0);
        if (duration > 0)
        {
            stop.Wait(TimeSpan.FromSeconds(duration));
        }
        else
        {
            Log.Info("press Ctrl+C to stop");
            stop.Wait();
        }

        Log.Info($"stopped: rx={link.BytesIn}B tx={link.BytesOut}B, control messages={link.CsmMessages}, " +
                 $"location updates={session.MessagesPushed}, authenticated={session.Authenticated}");
        return 0;
    }

    private static ITransport OpenTransport(string name, Options options) => name switch
    {
        "bt" or "bluetooth" => BtRfcommTransport.Connect(
            options.Text("mac") ?? throw new ArgumentException("--mac AA:BB:CC:DD:EE:FF is required for --transport bt"),
            options.Int("connect-timeout", 20)),

        "tcp" or "wifi" => TcpTransport.Connect(
            options.Text("host", "127.0.0.1"),
            options.Int("port", 0) is var port && port > 0
                ? port
                : throw new ArgumentException("--port is required for --transport tcp")),

        _ => throw new ArgumentException($"unknown transport '{name}', use bt or tcp"),
    };

    /// <summary>
    /// Dumps the MFi material. The certificate's subject and extensions are what tell iOS which
    /// kind of accessory this is (CarPlay head unit, GPS accessory, ...), which decides whether
    /// accessory-supplied location is honoured outside a CarPlay session.
    /// </summary>
    private static int InspectIdentity(Options options)
    {
        var directory = options.Text("identity") ?? options.Positional.FirstOrDefault() ?? "identity";
        var keyPath = Path.Combine(directory, "identity.pk8");
        var certificatePath = Path.Combine(directory, "certificate.p7b");
        if (!File.Exists(keyPath) || !File.Exists(certificatePath))
        {
            Log.Error($"expected {keyPath} and {certificatePath}");
            return 1;
        }

        var pk8 = File.ReadAllBytes(keyPath);
        var certificateBytes = File.ReadAllBytes(certificatePath);
        Log.Info($"source   : files in {Path.GetFullPath(directory)}");

        using (var key = ECDsa.Create())
        {
            key.ImportPkcs8PrivateKey(pk8, out _);
            Log.Info($"private key : {key.KeySize} bit, curve {key.ExportParameters(false).Curve.Oid.FriendlyName ?? "unknown"}");
        }

        var certificates = new X509Certificate2Collection();
        try
        {
            certificates.Import(certificateBytes);
        }
        catch (Exception ex)
        {
            Log.Error($"certificate.p7b is not a parseable PKCS#7: {ex.Message}");
            return 1;
        }

        Log.Info($"certificates: {certificates.Count}");
        foreach (var certificate in certificates)
        {
            Log.Info($"subject  : {certificate.Subject}");
            Log.Info($"issuer   : {certificate.Issuer}");
            Log.Info($"valid    : {certificate.NotBefore:yyyy-MM-dd} .. {certificate.NotAfter:yyyy-MM-dd}");
            Log.Info($"serial   : {certificate.SerialNumber}");

            foreach (var extension in certificate.Extensions)
            {
                var raw = extension.RawData;
                Log.Info($"ext {extension.Oid?.Value} ({extension.Oid?.FriendlyName}) {raw.Length} bytes");
                var printable = Printable(raw);
                if (printable.Length > 0)
                {
                    Log.Info("    " + (printable.Length > 220 ? printable[..220] + "..." : printable));
                }
            }
        }

        return 0;
    }

    private static string Printable(byte[] data)
    {
        var builder = new StringBuilder();
        foreach (var value in data)
        {
            builder.Append(value is >= 32 and < 127 ? (char)value : '.');
        }

        var text = builder.ToString();
        return text.Count(character => character != '.') >= 6 ? text : string.Empty;
    }

    private static void Help()
    {
        Console.WriteLine("""
            iap2loc - 通过 iAP2 给 iPhone 下发定位

            直接双击 exe（不带参数）会启动控制页面并自动打开浏览器，端口 8737。

            用法:
              iap2loc run --transport bt  --mac AA:BB:CC:DD:EE:FF [--identity 目录] [选项]
              iap2loc run --transport tcp --host 127.0.0.1 --port 12345 [--identity 目录] [选项]
              iap2loc devices
              iap2loc inspect-identity [--identity 目录]
              iap2loc serve [--port 8737] [--identity 目录]   启动本地控制页面

            运行选项:
              --lat / --lon        目标坐标（默认 31.2304, 121.4737）
              --alt --speed --bearing --accuracy    NMEA 附加字段
              --sentences gga,rmc,gsa,gsv          NMEA 句子集合（默认 gga,rmc）
              --rate               上报周期，毫秒（默认 200）
              --identity           identity.pk8 + certificate.p7b 所在目录（默认 ./identity）
              --name --model --manufacturer --serial --firmware --hardware   配件身份
              --probe              只跑到识别为止（收到 0x1D02/0x1D03 即退出）
              --duration           多少秒后自动退出，便于脚本测试
              -v, --verbose        打印协议级细节

            说明:
              run 需要 iPhone 已在 Windows 蓝牙设置里配对。
              身份材料放在 --identity 指向的目录里，本程序不包含任何 MFi 材料。
            """);
    }
}
