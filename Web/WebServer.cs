using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
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
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
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
        _listener = new TcpListener(IPAddress.Parse("0.0.0.0"), port);
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

                    case "/api/route":
                        RespondJson(stream, GetCurrentRoute());
                        return;

                    case "/api/path/control" when method == "POST":
                        RespondJson(stream, ControlPath(body));
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

                    case "/api/routes" when method == "POST":
                        RespondJson(stream, AddSavedRoute(body));
                        return;

                    case "/api/routes":
                        RespondJson(stream, _favorites.AllRoutes);
                        return;

                    case "/api/routes/remove" when method == "POST":
                        RespondJson(stream, RemoveSavedRoute(body));
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

    private object GetCurrentRoute()
    {
        var route = _manager.Target.GetRouteSnapshot();
        return new
        {
            signature = route.Signature,
            inputSignature = route.InputSignature,
            autoClose = route.AutoClose,
            isClosed = route.IsClosed,
            autoClosed = route.AutoClosed,
            closingGapMeters = route.ClosingGapMeters,
            points = route.Vertices.Select(vertex => new
            {
                lat = vertex.Point.Latitude,
                lon = vertex.Point.Longitude,
                alt = vertex.Point.Altitude,
                time = vertex.TimeSeconds is { } sec ? sec * 1000.0 : (double?)null,
            }).ToArray(),
        };
    }

    private object ControlPath(string body)
    {
        var payload = Deserialize<PathControlPayload>(body);
        if (payload is null)
        {
            return new { ok = false, error = "请求格式错误" };
        }

        bool? paused = null;
        bool? restart = null;
        double? seekProgress = null;

        switch (payload.Action?.Trim().ToLowerInvariant())
        {
            case "pause":
                paused = true;
                break;
            case "resume" or "play":
                paused = false;
                break;
            case "toggle":
                paused = !_manager.Target.Snapshot().PathPaused;
                break;
            case "restart" or "reset":
                restart = true;
                break;
            case "seek":
                seekProgress = payload.Progress;
                break;
        }

        if (payload.Progress is { } progress && seekProgress is null)
        {
            seekProgress = progress;
        }

        var update = new TargetUpdate
        {
            PathPaused = paused,
            PathRestart = restart,
            PathSeekProgress = seekProgress,
            PathSpeedMps = payload.PathSpeedKmh is { } kmh ? Math.Clamp(kmh, 0.36, 216) / 3.6 : null,
            PathLoopMode = ParseLoopMode(payload.PathLoopMode),
            PathAutoClose = payload.PathAutoClose,
            PathSpeedJitterPercent = payload.PathSpeedJitterPercent,
            PathUseGpxTime = payload.PathUseGpxTime,
            PathTimeScale = payload.PathTimeScale,
        };

        _manager.ApplyTarget(update, null);
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

    private object AddSavedRoute(string body)
    {
        var payload = Deserialize<SavedRoutePayload>(body);
        if (payload?.Points is null || payload.Points.Count < 2)
        {
            return new { ok = false, error = "路径至少需要两个点" };
        }

        var points = new List<SavedRoutePoint>(Math.Min(payload.Points.Count, 5000));
        foreach (var point in payload.Points)
        {
            var lat = point.Latitude ?? point.Lat;
            var lon = point.Longitude ?? point.Lon;
            if (lat is not { } latitude || lon is not { } longitude ||
                !double.IsFinite(latitude) || !double.IsFinite(longitude) ||
                Math.Abs(latitude) > 90 || Math.Abs(longitude) > 180)
            {
                continue;
            }

            var alt = point.Altitude ?? point.Alt;
            points.Add(new SavedRoutePoint(
                latitude,
                longitude,
                alt is { } a && double.IsFinite(a) ? a : null,
                point.Time is { } t && double.IsFinite(t) && t > 0 ? t : null));

            if (points.Count >= 5000)
            {
                break;
            }
        }

        if (points.Count < 2)
        {
            return new { ok = false, error = "路径有效点数不足" };
        }

        var autoClose = payload.AutoClose ?? true;
        var path = new RoutePath(
            points.Select(p => new RouteVertex(new GeoPoint(p.Latitude, p.Longitude, p.Altitude), p.Time is { } ms ? ms / 1000.0 : null)),
            autoClose);

        var saved = _favorites.AddRoute(
            payload.Name,
            path.LengthMeters,
            payload.SpeedKmh ?? 5.0,
            payload.LoopMode ?? "loop",
            autoClose,
            points);

        return new { ok = true, route = saved };
    }

    private object RemoveSavedRoute(string body)
    {
        var payload = Deserialize<IdPayload>(body);
        return payload?.Id is { } id ? new { ok = _favorites.RemoveRoute(id) } : new { ok = false };
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
        if (contentLength > 0 && contentLength <= 16 * 1024 * 1024)
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

    private static PathLoopMode? ParseLoopMode(string? text) => text?.Trim().ToLowerInvariant() switch
    {
        "once" or "none" or "single" => PathLoopMode.Once,
        "loop" or "circle" or "repeat" => PathLoopMode.Loop,
        "pingpong" or "ping-pong" or "ping_pong" or "roundtrip" or "bounce" => PathLoopMode.PingPong,
        _ => null,
    };

    private sealed class TargetPayload
    {
        public string? Mode { get; set; }
        public List<RoutePointPayload>? Route { get; set; }
        public double? PathSpeedKmh { get; set; }
        public bool? PathLoop { get; set; }
        public string? PathLoopMode { get; set; }
        public bool? PathAutoClose { get; set; }
        public bool? PathPaused { get; set; }
        public bool? PathRestart { get; set; }
        public double? PathSeekProgress { get; set; }
        public double? PathSpeedJitterPercent { get; set; }
        public bool? PathUseGpxTime { get; set; }
        public double? PathTimeScale { get; set; }
        public double? Latitude { get; set; }
        public double? Longitude { get; set; }
        public double? Altitude { get; set; }
        public double? Accuracy { get; set; }
        public double? Speed { get; set; }
        public double? Bearing { get; set; }
        public double? JitterMeters { get; set; }
        public bool? JitterAccuracy { get; set; }
        public double? AccuracyMin { get; set; }
        public double? AccuracyMax { get; set; }
        public int? Quality { get; set; }
        public int? Satellites { get; set; }
        public bool? JitterAltitude { get; set; }
        public double? AltitudeMin { get; set; }
        public double? AltitudeMax { get; set; }
        public int? Rate { get; set; }

        public TargetUpdate ToUpdate() => new()
        {
            Mode = ParseMode(Mode),
            Route = ParseRoute(Route),
            PathSpeedMps = PathSpeedKmh is { } kmh ? Math.Clamp(kmh, 0.36, 216) / 3.6 : null,
            PathLoop = PathLoop,
            PathLoopMode = ParseLoopMode(PathLoopMode),
            PathAutoClose = PathAutoClose,
            PathPaused = PathPaused,
            PathRestart = PathRestart,
            PathSeekProgress = PathSeekProgress,
            PathSpeedJitterPercent = PathSpeedJitterPercent,
            PathUseGpxTime = PathUseGpxTime,
            PathTimeScale = PathTimeScale,
            Latitude = Latitude,
            Longitude = Longitude,
            Altitude = Altitude,
            Accuracy = Accuracy,
            Speed = Speed,
            Bearing = Bearing,
            JitterMeters = JitterMeters,
            JitterAccuracy = JitterAccuracy,
            AccuracyMin = AccuracyMin,
            AccuracyMax = AccuracyMax,
            Quality = Quality,
            Satellites = Satellites,
            JitterAltitude = JitterAltitude,
            AltitudeMin = AltitudeMin,
            AltitudeMax = AltitudeMax,
        };

        private static TargetMode? ParseMode(string? text) => text?.Trim().ToLowerInvariant() switch
        {
            "path" or "route" => TargetMode.Path,
            "point" or "single" => TargetMode.Point,
            _ => null,
        };

        /// <summary>null keeps the current route; an empty array clears it.</summary>
        private static IReadOnlyList<RouteVertex>? ParseRoute(List<RoutePointPayload>? points)
        {
            if (points is null)
            {
                return null;
            }

            var parsed = new List<RouteVertex>(Math.Min(points.Count, LocationTarget.MaxRoutePoints));
            foreach (var point in points)
            {
                var lat = point.Latitude ?? point.Lat;
                var lon = point.Longitude ?? point.Lon;
                if (lat is not { } latitude || lon is not { } longitude)
                {
                    continue;
                }

                if (double.IsNaN(latitude) || double.IsNaN(longitude) ||
                    Math.Abs(latitude) > 90 || Math.Abs(longitude) > 180)
                {
                    continue;
                }

                var altRaw = point.Altitude ?? point.Alt;
                var altitude = altRaw is { } value && !double.IsNaN(value) ? value : (double?)null;
                var time = point.Time is { } milliseconds && !double.IsNaN(milliseconds) && milliseconds > 0
                    ? milliseconds / 1000.0
                    : (double?)null;
                parsed.Add(new RouteVertex(new GeoPoint(latitude, longitude, altitude), time));

                if (parsed.Count >= LocationTarget.MaxRoutePoints)
                {
                    break;
                }
            }

            return parsed;
        }
    }

    private sealed class PathControlPayload
    {
        public string? Action { get; set; }
        public double? Progress { get; set; }
        public double? PathSpeedKmh { get; set; }
        public string? PathLoopMode { get; set; }
        public bool? PathAutoClose { get; set; }
        public double? PathSpeedJitterPercent { get; set; }
        public bool? PathUseGpxTime { get; set; }
        public double? PathTimeScale { get; set; }
    }

    private sealed class SavedRoutePayload
    {
        public string? Name { get; set; }
        public double? SpeedKmh { get; set; }
        public string? LoopMode { get; set; }
        public bool? AutoClose { get; set; }
        public List<RoutePointPayload>? Points { get; set; }
    }

    private sealed class RoutePointPayload
    {
        public double? Latitude { get; set; }
        public double? Longitude { get; set; }
        public double? Altitude { get; set; }
        public double? Lat { get; set; }
        public double? Lon { get; set; }
        public double? Alt { get; set; }

        /// <summary>GPX timestamp in epoch milliseconds.</summary>
        public double? Time { get; set; }
    }

    private sealed class StartSessionPayload
    {
        public string? Transport { get; set; }
        public string? Mac { get; set; }
        public string? Host { get; set; }
        public int? Port { get; set; }
        public string? Identity { get; set; }
        public int? Rate { get; set; }
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
