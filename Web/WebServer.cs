using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Text.Json;
using Iap2Loc.Engine;
using Iap2Loc.Protocol;
using Iap2Loc.Transport;

namespace Iap2Loc.Web;

/// <summary>
/// Minimal HTTP server for the local control page. Written on TcpListener so it needs no URL
/// reservation and no extra packages. Serves one embedded page plus a small JSON API.
/// </summary>
internal sealed class WebServer : IDisposable
{
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly TcpListener _listener;
    private readonly CancellationTokenSource _cancellation = new();
    private readonly byte[] _index;
    private readonly SessionManager _manager;
    private readonly FavoriteStore _favorites;
    private readonly object _logGate = new();
    private readonly List<LogEntry> _log = new();
    private long _sequence;

    public WebServer(int port, SessionManager manager, FavoriteStore favorites)
    {
        _manager = manager;
        _favorites = favorites;
        _index = LoadIndex();
        _listener = new TcpListener(IPAddress.Loopback, port);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        Log.Emitted += OnLog;
    }

    public int Port { get; }

    public void Start()
    {
        var thread = new Thread(AcceptLoop) { IsBackground = true, Name = "web-accept" };
        thread.Start();
        Log.Ok($"控制页面：http://localhost:{Port}/");
    }

    private static byte[] LoadIndex()
    {
        var assembly = Assembly.GetExecutingAssembly();
        using var stream = assembly.GetManifestResourceStream("Iap2Loc.Web.index.html")
                           ?? throw new InvalidOperationException("embedded index.html not found");
        using var memory = new MemoryStream();
        stream.CopyTo(memory);
        return memory.ToArray();
    }

    private void OnLog(DateTime timestamp, string level, string message)
    {
        lock (_logGate)
        {
            _log.Add(new LogEntry(++_sequence, timestamp.ToString("HH:mm:ss.fff"), level, message));
            if (_log.Count > 800)
            {
                _log.RemoveRange(0, _log.Count - 800);
            }
        }
    }

    private void AcceptLoop()
    {
        while (!_cancellation.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = _listener.AcceptTcpClient();
            }
            catch (Exception)
            {
                return;
            }

            _ = Task.Run(() => Handle(client));
        }
    }

    private void Handle(TcpClient client)
    {
        using (client)
        {
            try
            {
                client.NoDelay = true;
                using var stream = client.GetStream();
                var request = ReadRequest(stream);
                if (request is null)
                {
                    return;
                }

                var (method, path, query, body) = request.Value;
                switch (path)
                {
                    case "/" or "/index.html":
                        Respond(stream, 200, "text/html; charset=utf-8", _index);
                        return;

                    case "/api/state":
                        RespondJson(stream, _manager.Snapshot());
                        return;

                    case "/api/devices":
                        RespondJson(stream, BluetoothDevices.GetDevices());
                        return;

                    case "/api/target" when method == "POST":
                        RespondJson(stream, ApplyTarget(body));
                        return;

                    case "/api/sessions" when method == "POST":
                        RespondJson(stream, StartSession(body));
                        return;

                    case "/api/sessions/remove" when method == "POST":
                        RespondJson(stream, RemoveSession(body));
                        return;

                    case "/api/stop-all" when method == "POST":
                        _manager.StopAll();
                        RespondJson(stream, new { ok = true });
                        return;

                    case "/api/identity/rotate" when method == "POST":
                        RespondJson(stream, RotateIdentity());
                        return;

                    case "/api/favorites" when method == "POST":
                        RespondJson(stream, AddFavorite(body));
                        return;

                    case "/api/favorites":
                        RespondJson(stream, _favorites.All);
                        return;

                    case "/api/favorites/remove" when method == "POST":
                        RespondJson(stream, RemoveFavorite(body));
                        return;

                    case "/api/log":
                        var since = query.TryGetValue("since", out var raw) && long.TryParse(raw, out var value) ? value : 0;
                        RespondJson(stream, LogSince(since));
                        return;

                    default:
                        Respond(stream, 404, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("not found"));
                        return;
                }
            }
            catch (Exception ex)
            {
                try
                {
                    using var stream = client.GetStream();
                    Respond(stream, 500, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes(ex.Message));
                }
                catch
                {
                    // Client already gone.
                }
            }
        }
    }

    private object ApplyTarget(string body)
    {
        var payload = Deserialize<TargetPayload>(body);
        if (payload is null)
        {
            return new { ok = false, error = "请求格式错误" };
        }

        _manager.ApplyTarget(payload.ToUpdate(), payload.Rate);
        return new { ok = true, target = _manager.Target.Snapshot() };
    }

    private object StartSession(string body)
    {
        var payload = Deserialize<StartSessionPayload>(body);
        if (payload is null)
        {
            return new { ok = false, error = "请求格式错误" };
        }

        var transport = string.IsNullOrWhiteSpace(payload.Transport) ? "bt" : payload.Transport!;
        if (transport.Equals("bt", StringComparison.OrdinalIgnoreCase) && string.IsNullOrWhiteSpace(payload.Mac))
        {
            return new { ok = false, error = "请先选择设备" };
        }

        var request = new RunRequest
        {
            Transport = transport,
            Mac = payload.Mac,
            Host = payload.Host,
            Port = payload.Port ?? 0,
            IdentityDirectory = payload.Identity,
            Rate = payload.Rate ?? 250,
            Sentences = payload.Sentences,
            Label = string.IsNullOrWhiteSpace(payload.Label) ? (payload.Mac ?? "iPhone") : payload.Label!,
        };

        var session = _manager.Add(request);
        return new { ok = session.Error is null, error = session.Error, id = session.Id };
    }

    private object RotateIdentity()
    {
        var profile = _manager.RotateIdentity(BluetoothDevices.TryGetLocalAdapterAddress());
        return new
        {
            ok = true,
            identity = new
            {
                name = profile.Name,
                model = profile.Model,
                serial = profile.Serial,
                adapter = profile.AdapterAddress,
            },
        };
    }

    private object RemoveSession(string body)
    {
        var payload = Deserialize<IdPayload>(body);
        if (payload?.Id is not { } id)
        {
            return new { ok = false, error = "缺少 id" };
        }

        return new { ok = _manager.Remove(id) };
    }

    private object AddFavorite(string body)
    {
        var payload = Deserialize<FavoritePayload>(body);
        if (payload?.Latitude is not { } latitude || payload.Longitude is not { } longitude)
        {
            return new { ok = false, error = "缺少坐标" };
        }

        var favorite = _favorites.Add(payload.Name, latitude, longitude, payload.Altitude, payload.Accuracy);
        return new { ok = true, favorite };
    }

    private object RemoveFavorite(string body)
    {
        var payload = Deserialize<IdPayload>(body);
        return payload?.Id is { } id ? new { ok = _favorites.Remove(id) } : new { ok = false };
    }

    private object LogSince(long since)
    {
        lock (_logGate)
        {
            var entries = _log.Where(entry => entry.Id > since)
                .Select(entry => new { id = entry.Id, time = entry.Time, level = entry.Level, text = entry.Text })
                .ToArray();
            return new { last = _sequence, entries };
        }
    }

    private static T? Deserialize<T>(string body)
    {
        try
        {
            return JsonSerializer.Deserialize<T>(body, Json);
        }
        catch
        {
            return default;
        }
    }

    private static void RespondJson(NetworkStream stream, object payload) =>
        Respond(stream, 200, "application/json; charset=utf-8", JsonSerializer.SerializeToUtf8Bytes(payload, Json));

    private static void Respond(NetworkStream stream, int status, string contentType, byte[] body)
    {
        var header = new StringBuilder()
            .Append("HTTP/1.1 ").Append(status).Append(status == 200 ? " OK" : " Error").Append("\r\n")
            .Append("Content-Type: ").Append(contentType).Append("\r\n")
            .Append("Content-Length: ").Append(body.Length).Append("\r\n")
            .Append("Cache-Control: no-store\r\n")
            .Append("Connection: close\r\n\r\n")
            .ToString();

        var headerBytes = Encoding.ASCII.GetBytes(header);
        stream.Write(headerBytes, 0, headerBytes.Length);
        stream.Write(body, 0, body.Length);
        stream.Flush();
    }

    private static (string Method, string Path, Dictionary<string, string> Query, string Body)? ReadRequest(NetworkStream stream)
    {
        var buffer = new List<byte>(1024);
        var single = new byte[1];
        while (buffer.Count < 65536)
        {
            var read = stream.Read(single, 0, 1);
            if (read <= 0)
            {
                return null;
            }

            buffer.Add(single[0]);
            if (buffer.Count >= 4 &&
                buffer[^4] == '\r' && buffer[^3] == '\n' && buffer[^2] == '\r' && buffer[^1] == '\n')
            {
                break;
            }
        }

        var head = Encoding.ASCII.GetString(buffer.ToArray());
        var lines = head.Split("\r\n", StringSplitOptions.RemoveEmptyEntries);
        if (lines.Length == 0)
        {
            return null;
        }

        var parts = lines[0].Split(' ');
        if (parts.Length < 2)
        {
            return null;
        }

        var method = parts[0];
        var target = parts[1];
        var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var path = target;
        var question = target.IndexOf('?');
        if (question >= 0)
        {
            path = target[..question];
            foreach (var pair in target[(question + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                var equals = pair.IndexOf('=');
                if (equals > 0)
                {
                    query[Uri.UnescapeDataString(pair[..equals])] = Uri.UnescapeDataString(pair[(equals + 1)..]);
                }
            }
        }

        var contentLength = 0;
        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon > 0 && line[..colon].Trim().Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
            {
                int.TryParse(line[(colon + 1)..].Trim(), out contentLength);
            }
        }

        var body = string.Empty;
        if (contentLength > 0)
        {
            var payload = new byte[contentLength];
            var offset = 0;
            while (offset < contentLength)
            {
                var read = stream.Read(payload, offset, contentLength - offset);
                if (read <= 0)
                {
                    break;
                }

                offset += read;
            }

            body = Encoding.UTF8.GetString(payload, 0, offset);
        }

        return (method, path, query, body);
    }

    public void Dispose()
    {
        Log.Emitted -= OnLog;
        _cancellation.Cancel();
        _listener.Stop();
    }

    private sealed record LogEntry(long Id, string Time, string Level, string Text);

    private sealed class TargetPayload
    {
        public double? Latitude { get; set; }
        public double? Longitude { get; set; }
        public double? Altitude { get; set; }
        public double? Accuracy { get; set; }
        public double? Speed { get; set; }
        public double? Bearing { get; set; }
        public bool? JitterPosition { get; set; }
        public double? JitterMeters { get; set; }
        public bool? JitterAccuracy { get; set; }
        public double? AccuracyMin { get; set; }
        public double? AccuracyMax { get; set; }
        public int? Rate { get; set; }

        public TargetUpdate ToUpdate() => new()
        {
            Latitude = Latitude,
            Longitude = Longitude,
            Altitude = Altitude,
            Accuracy = Accuracy,
            Speed = Speed,
            Bearing = Bearing,
            JitterPosition = JitterPosition,
            JitterMeters = JitterMeters,
            JitterAccuracy = JitterAccuracy,
            AccuracyMin = AccuracyMin,
            AccuracyMax = AccuracyMax,
        };
    }

    private sealed class StartSessionPayload
    {
        public string? Transport { get; set; }
        public string? Mac { get; set; }
        public string? Host { get; set; }
        public int? Port { get; set; }
        public string? Identity { get; set; }
        public int? Rate { get; set; }
        public string? Sentences { get; set; }
        public string? Label { get; set; }
    }

    private sealed class IdPayload
    {
        public string? Id { get; set; }
    }

    private sealed class FavoritePayload
    {
        public string? Name { get; set; }
        public double? Latitude { get; set; }
        public double? Longitude { get; set; }
        public double? Altitude { get; set; }
        public double? Accuracy { get; set; }
    }
}
