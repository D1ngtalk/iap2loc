using Iap2Loc.Protocol;
using Iap2Loc.Transport;

namespace Iap2Loc.Engine;

internal sealed record RunRequest
{
    public string Transport { get; init; } = "bt";
    public string? Mac { get; init; }
    public string? Host { get; init; }
    public int Port { get; init; }
    public string? IdentityDirectory { get; init; }
    public int Rate { get; init; } = 200;
    public string? Sentences { get; init; }
    public string Name { get; init; } = "iAP2 Loc";
    public string Serial { get; init; } = "LOC000000001";
    public string Label { get; init; } = "iPhone";
}

/// <summary>One accessory session. Several can run at once; all of them follow the shared target.</summary>
internal sealed class RunSession : IDisposable
{
    private readonly RunRequest _request;
    private readonly LocationTarget _target;
    private readonly object _gate = new();

    private ITransport? _transport;
    private Iap2Link? _link;
    private LocationSession? _location;
    private MfiSigner? _signer;
    private string? _error;
    private bool _running;

    public RunSession(string id, RunRequest request, LocationTarget target)
    {
        Id = id;
        _request = request;
        _target = target;
    }

    public string Id { get; }

    public RunRequest Request => _request;

    public string Label => string.IsNullOrWhiteSpace(_request.Label) ? "iPhone" : _request.Label;

    public bool Running
    {
        get { lock (_gate) { return _running; } }
    }

    public string LinkState
    {
        get { lock (_gate) { return _link?.State.ToString() ?? "Idle"; } }
    }

    public bool Authenticated
    {
        get { lock (_gate) { return _location?.Authenticated ?? false; } }
    }

    public bool Subscribed
    {
        get { lock (_gate) { return _location?.Subscribed ?? false; } }
    }

    public int Sent
    {
        get { lock (_gate) { return _location?.MessagesPushed ?? 0; } }
    }

    public string? Error
    {
        get { lock (_gate) { return _error; } }
    }

    public void Start()
    {
        lock (_gate)
        {
            if (_running)
            {
                return;
            }

            _error = null;
            try
            {
                _signer = IdentitySource.Resolve(_request.IdentityDirectory, out var identitySource);

                if (_signer is null)
                {
                    Log.Warn($"[{Label}] 未配置身份：链路与识别可用，认证不会通过");
                }
                else
                {
                    Log.Info($"[{Label}] 身份：{identitySource}，证书 {_signer.Certificate.Length} 字节");
                }

                _transport = _request.Transport.ToLowerInvariant() switch
                {
                    "bt" or "bluetooth" => BtRfcommTransport.Connect(
                        _request.Mac ?? throw new ArgumentException("缺少蓝牙地址")),
                    "tcp" or "wifi" => TcpTransport.Connect(
                        _request.Host ?? "127.0.0.1",
                        _request.Port > 0 ? _request.Port : throw new ArgumentException("缺少 TCP 端口")),
                    var other => throw new ArgumentException($"未知传输 '{other}'"),
                };

                var adapter = _request.Transport.StartsWith("bt", StringComparison.OrdinalIgnoreCase)
                    ? BluetoothDevices.TryGetLocalAdapterAddress()
                    : null;

                var identity = new AccessoryIdentity(
                    _request.Name,
                    "Loc1,1",
                    "carplay-loc",
                    _request.Serial,
                    "0.1.0",
                    "1.0");

                _link = new Iap2Link(_transport, initiate: true, $"accessory-{Id}");
                _location = new LocationSession(
                    _link,
                    _target,
                    identity,
                    _signer,
                    _request.Rate,
                    Nmea.ParseSentences(_request.Sentences),
                    bluetoothAdapter: adapter);

                _link.Failed += reason =>
                {
                    lock (_gate)
                    {
                        _error = reason;
                        _running = false;
                    }

                    Log.Error($"[{Label}] 链路中断：{reason}");
                };

                _link.Start();
                _running = true;
                Log.Ok($"[{Label}] 已启动：{_transport.Describe}");
            }
            catch (Exception ex)
            {
                _error = ex.Message;
                Log.Error($"[{Label}] 启动失败：{ex.Message}");
                Dispose();
            }
        }
    }

    public void SetInterval(int intervalMs)
    {
        lock (_gate)
        {
            _location?.SetInterval(intervalMs);
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            if (!_running && _link is null)
            {
                return;
            }

            _running = false;
        }

        Dispose();
        Log.Info($"[{Label}] 已停止");
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _running = false;
            _location?.Dispose();
            _location = null;
            _link?.Dispose();
            _link = null;
            _transport = null;
            _signer?.Dispose();
            _signer = null;
        }
    }
}
