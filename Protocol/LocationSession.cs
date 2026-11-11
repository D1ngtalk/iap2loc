using System.Text;

namespace Iap2Loc.Protocol;

/// <summary>
/// The accessory brain: answers identification and MFi authentication, then streams NMEA
/// LocationInformation at the requested rate whenever the phone has subscribed. The target is a
/// shared, mutable object so the map can be clicked while the session stays connected.
/// </summary>
internal sealed class LocationSession : IDisposable
{
    private readonly Iap2Link _link;
    private readonly LocationTarget _target;
    private readonly AccessoryIdentity _identity;
    private readonly MfiSigner? _signer;
    private readonly byte[]? _bluetoothAdapter;

    private readonly object _timerGate = new();
    private Timer? _timer;
    private volatile bool _subscribed;
    private int _intervalMs;
    private NmeaSentences _sentences;
    private int _pushed;
    private bool _disposed;

    public LocationSession(
        Iap2Link link,
        LocationTarget target,
        AccessoryIdentity identity,
        MfiSigner? signer,
        int intervalMs,
        NmeaSentences sentences,
        byte[]? bluetoothAdapter = null)
    {
        _link = link;
        _target = target;
        _identity = identity;
        _signer = signer;
        _intervalMs = Math.Max(200, intervalMs);
        _sentences = sentences;
        _bluetoothAdapter = bluetoothAdapter;
        _link.CsmReceived += OnCsm;
    }

    /// <summary>Raised on 0x1D02 (true) or 0x1D03 (false, with the offending parameters).</summary>
    public event Action<bool, string>? IdentificationFinished;

    public bool Authenticated { get; private set; }

    public bool Subscribed => _subscribed;

    public int MessagesPushed => _pushed;

    public void SetInterval(int intervalMs)
    {
        var next = Math.Max(200, intervalMs);
        lock (_timerGate)
        {
            if (next == _intervalMs)
            {
                return;
            }

            _intervalMs = next;
            if (_timer is not null)
            {
                _timer.Dispose();
                _timer = new Timer(_ => Push(), null, 0, _intervalMs);
            }
        }
    }

    private void OnCsm(ushort messageId, IReadOnlyDictionary<int, byte[]> parameters)
    {
        try
        {
            switch (messageId)
            {
                case 0x1D00:
                    Log.Step($"rx 0x1D00 StartIdentification → tx 0x1D01 " +
                             $"(transport 17 {(_bluetoothAdapter is null ? "absent" : "present")})");
                    _link.Send(0x1D01, Identification.Build(_identity, _bluetoothAdapter));
                    break;

                case 0x1D02:
                    Log.Ok("rx 0x1D02 IdentificationAccepted");
                    IdentificationFinished?.Invoke(true, string.Empty);
                    break;

                case 0x1D03:
                    var detail = Describe(parameters);
                    Log.Error($"rx 0x1D03 IdentificationRejected, offending parameters: {detail}{Hint(parameters)}");
                    IdentificationFinished?.Invoke(false, detail);
                    break;

                case 0xAA00:
                    if (_signer is null)
                    {
                        Log.Warn("rx 0xAA00 RequestAuthenticationCertificate, but no identity was given");
                        break;
                    }

                    Log.Step($"rx 0xAA00 RequestAuthenticationCertificate → tx 0xAA01 ({_signer.Certificate.Length} bytes)");
                    _link.Send(0xAA01, Csm.Param(0, _signer.Certificate));
                    break;

                case 0xAA02:
                    if (_signer is null)
                    {
                        Log.Warn("rx 0xAA02 challenge, but no identity was given; authentication cannot succeed");
                        break;
                    }

                    if (!parameters.TryGetValue(0, out var challenge) || challenge.Length == 0)
                    {
                        Log.Error("rx 0xAA02 without a challenge in parameter 0");
                        break;
                    }

                    var signature = _signer.SignChallenge(challenge);
                    Log.Step($"rx 0xAA02 challenge ({challenge.Length} B) → tx 0xAA03 signature ({signature.Length} B)");
                    _link.Send(0xAA03, Csm.Param(0, signature));
                    break;

                case 0xAA04:
                    Log.Error("rx 0xAA04 AuthenticationFailed: the phone rejected the certificate or the signature");
                    break;

                case 0xAA05:
                    Authenticated = true;
                    Log.Ok("rx 0xAA05 AuthenticationSucceeded");
                    break;

                case 0xFFFA:
                    _subscribed = true;
                    Log.Ok($"rx 0xFFFA StartLocationInformation, components=[{string.Join(", ", parameters.Keys.Order())}]");
                    StartTimer();
                    break;

                case 0xFFFC:
                    _subscribed = false;
                    Log.Info("rx 0xFFFC StopLocationInformation");
                    StopTimer();
                    break;

                case 0xEA00:
                case 0xEA01:
                    Log.Trace($"rx 0x{messageId:X4} external accessory protocol session (ignored)");
                    break;

                default:
                    Log.Trace($"rx 0x{messageId:X4} parameters=[{Describe(parameters)}]");
                    break;
            }
        }
        catch (Exception ex)
        {
            Log.Error($"handling 0x{messageId:X4} failed: {ex.Message}");
        }
    }

    private void StartTimer()
    {
        lock (_timerGate)
        {
            if (_timer is not null)
            {
                return;
            }

            var state = _target.Snapshot();
            Log.Info($"streaming {_intervalMs} ms NMEA for {state.Latitude:F6},{state.Longitude:F6}");
            _timer = new Timer(_ => Push(), null, 0, _intervalMs);
        }
    }

    private void StopTimer()
    {
        lock (_timerGate)
        {
            _timer?.Dispose();
            _timer = null;
        }
    }

    private void Push()
    {
        if (_disposed || !_subscribed || _link.State != LinkState.Normal)
        {
            return;
        }

        try
        {
            var now = DateTime.UtcNow;
            var fix = _target.Next();

            var nmea = Nmea.Encode(
                fix.Latitude,
                fix.Longitude,
                fix.AltitudeMeters,
                fix.BearingDegrees,
                fix.SpeedMetersPerSecond,
                fix.AccuracyMeters,
                now,
                _sentences);

            _link.Send(0xFFFB, Csm.Param(0, Encoding.UTF8.GetBytes(nmea + "\0")));

            var count = Interlocked.Increment(ref _pushed);
            if (count == 1 || count % 30 == 0)
            {
                Log.Trace($"tx 0xFFFB #{count} {nmea.Split("\r\n")[0]}");
            }
        }
        catch (Exception ex)
        {
            Log.Warn($"sending 0xFFFB failed: {ex.Message}");
        }
    }

    private static string Describe(IReadOnlyDictionary<int, byte[]> parameters) =>
        string.Join(", ", parameters.Select(pair => $"{pair.Key}:{pair.Value.Length}B"));

    /// <summary>Maps the parameter ids iOS quotes in 0x1D03 to the thing that is usually wrong.</summary>
    private static string Hint(IReadOnlyDictionary<int, byte[]> parameters)
    {
        var hints = new List<string>();
        if (parameters.ContainsKey(6)) hints.Add("6 = declared messages sent, iOS refuses one of them");
        if (parameters.ContainsKey(7)) hints.Add("7 = declared messages received, iOS refuses one of them");
        if (parameters.ContainsKey(16)) hints.Add("16 = USB transport component");
        if (parameters.ContainsKey(17)) hints.Add("17 = Bluetooth transport component was missing or malformed");
        if (parameters.ContainsKey(22)) hints.Add("22 = location component, check the nested parameters");
        if (parameters.ContainsKey(24)) hints.Add("24 = Wi-Fi transport component");
        return hints.Count == 0 ? string.Empty : " (" + string.Join("; ", hints) + ")";
    }

    public void Dispose()
    {
        _disposed = true;
        StopTimer();
        _link.CsmReceived -= OnCsm;
    }
}
