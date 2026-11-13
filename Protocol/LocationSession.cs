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
    private volatile Subscription? _subscription;
    private readonly Dictionary<int, long> _lastSent = new();
    private readonly object _pushGate = new();
    private double _lastFixLatitude;
    private double _lastFixLongitude;
    private bool _hasLastFix;
    private int _intervalMs;
    private NmeaSentences _sentences;
    private int _pushed;
    private bool _disposed;

    /// <summary>A displacement beyond this is a new target, not jitter or walking.</summary>
    private const double JumpThresholdMeters = 100;

    /// <summary>Reported on the jump frame: about 900 km/h, an airliner rather than a teleport.</summary>
    private const double JumpSpeedMetersPerSecond = 250;

    /// <summary>What the phone asked for in 0xFFFA: sentence ids 1/2 and optional periods.</summary>
    private sealed record Subscription(HashSet<int> Sentences, Dictionary<int, int> RequestedMs);

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
        _intervalMs = Math.Max(10, intervalMs);
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
        var next = Math.Max(10, intervalMs);
        lock (_timerGate)
        {
            if (next == _intervalMs)
            {
                return;
            }

            _intervalMs = next;
            if (_timer is not null)
            {
                try { _timer.Change(0, Timeout.Infinite); } catch (ObjectDisposedException) { }
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
                    _subscription = ParseSubscription(parameters);
                    _lastSent.Clear();
                    _subscribed = true;
                    Log.Ok($"rx 0xFFFA StartLocationInformation, {Describe(_subscription)}");
                    StartTimer();
                    RestartTimer();
                    break;

                case 0xFFFC:
                    _subscribed = false;
                    _subscription = null;
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
            Log.Info($"streaming {PlannedInterval()} ms NMEA for {state.Latitude:F6},{state.Longitude:F6}");
            _timer = new Timer(_ => Push(), null, 0, Timeout.Infinite);
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
        try
        {
            if (_disposed || !_subscribed || _link.State != LinkState.Normal)
            {
                return;
            }

            lock (_pushGate)
            {
                var now = DateTime.UtcNow;
                var fix = _target.Next();
                var (speed, bearing) = MotionFor(fix);
                var subscription = _subscription;

                var nmea = Nmea.Encode(
                    fix.Latitude,
                    fix.Longitude,
                    fix.AltitudeMeters,
                    bearing,
                    speed,
                    fix.AccuracyMeters,
                    now,
                    _sentences);

                var parameters = new List<byte[]>();
                var sent = new List<int>();
                var tick = Environment.TickCount64;

                foreach (var sentence in nmea.Split("\r\n", StringSplitOptions.RemoveEmptyEntries))
                {
                    var id = SentenceId(sentence);
                    if (id != 0 && subscription is { Sentences.Count: > 0 } && !subscription.Sentences.Contains(id))
                    {
                        continue;
                    }

                    if (!Due(subscription, id, tick))
                    {
                        continue;
                    }

                    parameters.Add(Csm.Param(0, Encoding.UTF8.GetBytes(sentence + "\r\n\0")));
                    sent.Add(id);
                }

                if (parameters.Count == 0)
                {
                    return;
                }

                _link.Send(0xFFFB, parameters.ToArray());

                // Only RMC carries speed and course, so the jump marker is consumed when RMC goes out.
                if (sent.Contains(2))
                {
                    _lastFixLatitude = fix.Latitude;
                    _lastFixLongitude = fix.Longitude;
                    _hasLastFix = true;
                }

                foreach (var id in sent)
                {
                    _lastSent[id] = tick;
                }

                var count = Interlocked.Increment(ref _pushed);
                if (count == 1 || count % 30 == 0)
                {
                    Log.Trace($"tx 0xFFFB #{count} {parameters.Count} sentence(s), first: {nmea.Split("\r\n")[0]}");
                }
            }
        }
        catch (Exception ex)
        {
            Log.Warn($"sending 0xFFFB failed: {ex.Message}");
        }
        finally
        {
            Rearm();
        }
    }

    /// <summary>
    /// A 1000 km jump with GPRMC speed 0 is a contradiction, and a receiver is likely to throw the fix
    /// away. On the frame that jumps, report a cruise speed and the bearing towards the new point; the
    /// next frame falls back to the target's own speed (usually 0), which is what a parked car looks like.
    /// </summary>
    private (double? Speed, double? Bearing) MotionFor(LocationFix fix)
    {
        if (!_hasLastFix)
        {
            return (fix.SpeedMetersPerSecond, fix.BearingDegrees);
        }

        var from = new GeoPoint(_lastFixLatitude, _lastFixLongitude);
        var to = new GeoPoint(fix.Latitude, fix.Longitude);
        var distance = Geo.Distance(from, to);
        if (distance < JumpThresholdMeters)
        {
            return (fix.SpeedMetersPerSecond, fix.BearingDegrees);
        }

        var bearing = Geo.Bearing(from, to);
        Log.Info($"位置跳变 {distance / 1000:F1} km，该帧按 {JumpSpeedMetersPerSecond:F0} m/s、航向 {bearing:F0}° 上报");
        return (JumpSpeedMetersPerSecond, bearing);
    }

    /// <summary>A sentence is due when its own period elapsed; the device period wins when it asked for one.</summary>
    private bool Due(Subscription? subscription, int sentenceId, long now)
    {
        if (!_lastSent.TryGetValue(sentenceId, out var last))
        {
            return true;
        }

        return now - last >= EffectiveInterval(subscription, sentenceId);
    }

    private int EffectiveInterval(Subscription? subscription, int sentenceId) =>
        subscription is not null &&
        subscription.RequestedMs.TryGetValue(sentenceId, out var requested) &&
        requested > 0
            ? requested
            : _intervalMs;

    /// <summary>Logging only: the cadence the device asked for, or our own interval.</summary>
    private int PlannedInterval()
    {
        var subscription = _subscription;
        if (subscription is null)
        {
            return _intervalMs;
        }

        var fastest = int.MaxValue;
        foreach (var id in TrackedSentences(subscription))
        {
            fastest = Math.Min(fastest, EffectiveInterval(subscription, id));
        }

        return fastest == int.MaxValue ? _intervalMs : fastest;
    }

    /// <summary>One-shot timer: wake up exactly when the next sentence is due, then rearm.</summary>
    private int NextDelay()
    {
        var subscription = _subscription;
        if (subscription is null)
        {
            return _intervalMs;
        }

        var now = Environment.TickCount64;
        var soonest = int.MaxValue;
        foreach (var id in TrackedSentences(subscription))
        {
            var interval = EffectiveInterval(subscription, id);
            var elapsed = _lastSent.TryGetValue(id, out var last) ? now - last : interval;
            soonest = Math.Min(soonest, interval - (int)elapsed);
        }

        return soonest == int.MaxValue ? _intervalMs : Math.Max(10, soonest);
    }

    private static int[] TrackedSentences(Subscription subscription) =>
        subscription.Sentences.Count > 0 ? subscription.Sentences.ToArray() : new[] { 1, 2 };

    private void RestartTimer()
    {
        lock (_timerGate)
        {
            if (_timer is null)
            {
                return;
            }

            try { _timer.Change(0, Timeout.Infinite); } catch (ObjectDisposedException) { }
        }
    }

    private void Rearm()
    {
        lock (_timerGate)
        {
            if (_timer is null || _disposed)
            {
                return;
            }

            try { _timer.Change(NextDelay(), Timeout.Infinite); } catch (ObjectDisposedException) { }
        }
    }

    private static string Describe(IReadOnlyDictionary<int, byte[]> parameters) =>
        string.Join(", ", parameters.Select(pair => $"{pair.Key}:{pair.Value.Length}B"));

    /// <summary>
    /// 0xFFFA carries the sentence ids it wants as empty parameters 1 (GGA) and 2 (RMC);
    /// 0x8001 / 0x8002 are the matching periods in milliseconds. A period the device asks for
    /// overrides the configured interval; the configured value only applies when it asks for none.
    /// </summary>
    private static Subscription ParseSubscription(IReadOnlyDictionary<int, byte[]> parameters)
    {
        var sentences = new HashSet<int>();
        var periods = new Dictionary<int, int>();

        foreach (var (id, data) in parameters)
        {
            if (id is 1 or 2 && data.Length == 0)
            {
                sentences.Add(id);
            }
            else if (id is 0x8001 or 0x8002 && data.Length == 4)
            {
                var milliseconds = (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3];
                if (milliseconds > 0)
                {
                    periods[id - 0x8000] = milliseconds;
                }
            }
        }

        return new Subscription(sentences, periods);
    }

    private string Describe(Subscription subscription)
    {
        var known = new[] { (Id: 1, Name: "gga"), (Id: 2, Name: "rmc") };
        var sentences = subscription.Sentences.Count == 0
            ? "all"
            : string.Join(",", known.Where(item => subscription.Sentences.Contains(item.Id)).Select(item => item.Name));
        var periods = subscription.RequestedMs.Count == 0
            ? "none"
            : string.Join(",", known.Where(item => subscription.RequestedMs.ContainsKey(item.Id))
                .Select(item => $"{item.Name} {subscription.RequestedMs[item.Id]}ms"));

        return $"sentences=[{sentences}], requested=[{periods}], sending every {PlannedInterval()} ms";
    }

    private static int SentenceId(string sentence) =>
        sentence.StartsWith("$GPGGA", StringComparison.Ordinal) ||
        sentence.StartsWith("$GNGGA", StringComparison.Ordinal)
            ? 1
            : sentence.StartsWith("$GPRMC", StringComparison.Ordinal) ||
              sentence.StartsWith("$GNRMC", StringComparison.Ordinal)
                ? 2
                : 0;

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
