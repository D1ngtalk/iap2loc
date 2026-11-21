namespace Iap2Loc.Protocol;

/// <summary>A route vertex plus the GPX timestamp it was recorded at, in seconds.</summary>
internal readonly record struct RouteVertex(GeoPoint Point, double? TimeSeconds = null);

/// <summary>How playback behaves when reaching the end of a route.</summary>
internal enum PathLoopMode
{
    Once = 0,
    Loop = 1,
    PingPong = 2,
}

internal readonly record struct RouteSample(
    GeoPoint Point,
    double Bearing,
    double SpeedMetersPerSecond,
    double DistanceMeters,
    bool ReachedEnd = false,
    int Lap = 1,
    bool Reversed = false);

/// <summary>
/// A polyline followed either at a configured ground speed or along the timestamps of an imported GPX.
/// Supports automatic loop closure when end and start are within 50 metres, as well as once, loop,
/// and ping-pong playback modes.
/// </summary>
internal sealed class RoutePath
{
    /// <summary>When end and start are closer than this (and there are >= 3 points), close the loop.</summary>
    public const double AutoCloseThresholdMeters = 50.0;

    private const double AlreadyClosedEpsilonMeters = 0.5;

    private readonly RouteVertex[] _inputVertices;
    private readonly GeoPoint[] _points;
    private readonly double[] _cumulative;
    private readonly double[]? _times;
    private readonly double _length;
    private readonly double _duration;

    public RoutePath(IEnumerable<RouteVertex> vertices, bool autoClose = true)
    {
        _inputVertices = vertices.ToArray();
        var effective = BuildEffectiveVertices(
            _inputVertices,
            autoClose,
            out var isClosed,
            out var autoClosed,
            out var closingGapMeters);

        IsClosed = isClosed;
        AutoClosed = autoClosed;
        ClosingGapMeters = closingGapMeters;

        _points = effective.Select(vertex => vertex.Point).ToArray();
        _cumulative = new double[_points.Length];

        for (var index = 1; index < _points.Length; index++)
        {
            _cumulative[index] = _cumulative[index - 1] + Geo.Distance(_points[index - 1], _points[index]);
        }

        _length = _cumulative.Length == 0 ? 0 : _cumulative[^1];
        _times = BuildTimeline(effective, out _duration);
        InputSignature = ComputeSignature(_inputVertices, includeAutoClose: false, autoClose: false);
        Signature = ComputeSignature(_inputVertices, includeAutoClose: true, autoClose: autoClose);
    }

    /// <summary>Number of user-provided vertices (excluding any synthetic auto-close vertex).</summary>
    public int InputCount => _inputVertices.Length;

    /// <summary>Number of effective polyline vertices (including closing segment if auto-closed).</summary>
    public int Count => _points.Length;

    public double LengthMeters => _length;

    /// <summary>True when every vertex carries a usable timestamp, so playback can follow GPX timing.</summary>
    public bool HasTimeline => _times is not null;

    public double DurationSeconds => _duration;

    /// <summary>True when the polyline can actually be followed; a single point cannot.</summary>
    public bool IsUsable => _points.Length >= 2 && _length > 0.5;

    /// <summary>True when start and end meet (either originally or via auto-close).</summary>
    public bool IsClosed { get; }

    /// <summary>True when a synthetic closing segment (end -> start) was added because gap &lt; 50m.</summary>
    public bool AutoClosed { get; }

    /// <summary>Great-circle distance between the original last and first vertices.</summary>
    public double ClosingGapMeters { get; }

    /// <summary>Identity of the effective path (includes auto-close setting).</summary>
    public ulong Signature { get; }

    /// <summary>Identity of the raw user vertices only (independent of auto-close setting).</summary>
    public ulong InputSignature { get; }

    public IReadOnlyList<RouteVertex> InputVertices => _inputVertices;

    public IReadOnlyList<GeoPoint> Points => _points;

    /// <summary>Samples the polyline at a distance from the start, clamped to the ends.</summary>
    public RouteSample At(double travelledMeters, bool reverse = false)
    {
        if (_points.Length == 0)
        {
            return new RouteSample(default, 0, 0, 0);
        }

        if (_points.Length == 1 || _length <= 0)
        {
            return new RouteSample(_points[^1], 0, 0, 0);
        }

        var distance = Math.Clamp(travelledMeters, 0, _length);
        var index = Array.BinarySearch(_cumulative, distance);
        if (index < 0)
        {
            index = ~index - 1;
        }

        index = Math.Clamp(index, 0, _points.Length - 1);

        if (index >= _points.Length - 1)
        {
            var last = _points[^1];
            return new RouteSample(last, SegmentBearing(_points.Length - 2, reverse), 0, _length, Reversed: reverse);
        }

        var start = _points[index];
        var end = _points[index + 1];
        var span = _cumulative[index + 1] - _cumulative[index];
        var ratio = span <= 0 ? 0 : (distance - _cumulative[index]) / span;

        var latitude = start.Latitude + (end.Latitude - start.Latitude) * ratio;
        var longitude = start.Longitude + (end.Longitude - start.Longitude) * ratio;
        double? altitude = start.Altitude is { } startAltitude && end.Altitude is { } endAltitude
            ? startAltitude + (endAltitude - startAltitude) * ratio
            : start.Altitude ?? end.Altitude;

        // When travelling in reverse right at a vertex (ratio == 0 and index > 0), orient along the segment we're entering.
        var bearingIndex = reverse && ratio <= 1e-9 && index > 0 ? index - 1 : index;
        return new RouteSample(
            new GeoPoint(latitude, longitude, altitude),
            SegmentBearing(bearingIndex, reverse),
            0,
            distance,
            Reversed: reverse);
    }

    /// <summary>Samples along total cumulative distance using the selected loop mode.</summary>
    public RouteSample SampleByDistance(double totalTravelledMeters, PathLoopMode loopMode, double speedMps)
    {
        if (!IsUsable)
        {
            return At(0) with { SpeedMetersPerSecond = 0, ReachedEnd = true, Lap = 1, Reversed = false };
        }

        var total = Math.Max(0, totalTravelledMeters);

        switch (loopMode)
        {
            case PathLoopMode.Once:
                if (total >= _length)
                {
                    return At(_length, reverse: false) with
                    {
                        SpeedMetersPerSecond = 0,
                        ReachedEnd = true,
                        Lap = 1,
                        Reversed = false,
                    };
                }

                return At(total, reverse: false) with
                {
                    SpeedMetersPerSecond = speedMps,
                    ReachedEnd = false,
                    Lap = 1,
                    Reversed = false,
                };

            case PathLoopMode.PingPong:
            {
                var legIndex = (int)Math.Floor(total / _length);
                var reversed = (legIndex % 2) == 1;
                var lap = (legIndex / 2) + 1;
                var offset = total - legIndex * _length;
                var dist = reversed ? _length - offset : offset;
                return At(dist, reverse: reversed) with
                {
                    SpeedMetersPerSecond = speedMps,
                    ReachedEnd = false,
                    Lap = lap,
                    Reversed = reversed,
                };
            }

            case PathLoopMode.Loop:
            default:
            {
                var lap = (int)Math.Floor(total / _length) + 1;
                var dist = total - (lap - 1) * _length;
                return At(dist, reverse: false) with
                {
                    SpeedMetersPerSecond = speedMps,
                    ReachedEnd = false,
                    Lap = lap,
                    Reversed = false,
                };
            }
        }
    }

    /// <summary>
    /// Samples the polyline at a scaled GPX elapsed time using the GPX timestamps, supporting Once,
    /// Loop, and PingPong modes plus playback speed multiplier.
    /// </summary>
    public RouteSample SampleByTime(double scaledElapsedSeconds, PathLoopMode loopMode, double timeScale = 1.0)
    {
        if (_times is null || !IsUsable)
        {
            return At(0) with { SpeedMetersPerSecond = 0, ReachedEnd = true, Lap = 1, Reversed = false };
        }

        var total = Math.Max(0, scaledElapsedSeconds);
        var scale = Math.Max(0.05, timeScale);

        switch (loopMode)
        {
            case PathLoopMode.Once:
                if (total >= _duration)
                {
                    return At(_length, reverse: false) with
                    {
                        SpeedMetersPerSecond = 0,
                        ReachedEnd = true,
                        Lap = 1,
                        Reversed = false,
                    };
                }

                return SampleAtTimelineSeconds(total, reverse: false, lap: 1, scale);

            case PathLoopMode.PingPong:
            {
                var legIndex = (int)Math.Floor(total / _duration);
                var reversed = (legIndex % 2) == 1;
                var lap = (legIndex / 2) + 1;
                var offset = total - legIndex * _duration;
                var seconds = reversed ? _duration - offset : offset;
                return SampleAtTimelineSeconds(seconds, reverse: reversed, lap: lap, scale);
            }

            case PathLoopMode.Loop:
            default:
            {
                var lap = (int)Math.Floor(total / _duration) + 1;
                var seconds = total - (lap - 1) * _duration;
                return SampleAtTimelineSeconds(seconds, reverse: false, lap: lap, scale);
            }
        }
    }

    /// <summary>Maps a distance along the polyline back to its corresponding GPX timeline second.</summary>
    public double TimeAtDistance(double distanceMeters)
    {
        if (_times is null || !IsUsable)
        {
            return 0;
        }

        var distance = Math.Clamp(distanceMeters, 0, _length);
        var index = Array.BinarySearch(_cumulative, distance);
        if (index < 0)
        {
            index = ~index - 1;
        }

        index = Math.Clamp(index, 0, _points.Length - 1);
        if (index >= _points.Length - 1)
        {
            return _duration;
        }

        var span = _cumulative[index + 1] - _cumulative[index];
        var ratio = span <= 0 ? 0 : (distance - _cumulative[index]) / span;
        return _times[index] + (_times[index + 1] - _times[index]) * ratio;
    }

    private RouteSample SampleAtTimelineSeconds(double seconds, bool reverse, int lap, double timeScale)
    {
        var clamped = Math.Clamp(seconds, 0, _duration);
        var index = Array.BinarySearch(_times!, clamped);
        if (index < 0)
        {
            index = ~index - 1;
        }

        index = Math.Clamp(index, 0, _points.Length - 1);

        if (index >= _points.Length - 1)
        {
            var last = _points[^1];
            return new RouteSample(
                last,
                SegmentBearing(_points.Length - 2, reverse),
                SpeedAt(_points.Length - 2) * timeScale,
                _length,
                ReachedEnd: false,
                Lap: lap,
                Reversed: reverse);
        }

        var span = _times![index + 1] - _times[index];
        var ratio = span <= 0 ? 0 : (clamped - _times[index]) / span;
        var distance = _cumulative[index] + (_cumulative[index + 1] - _cumulative[index]) * ratio;

        var speedIndex = reverse && ratio <= 1e-9 && index > 0 ? index - 1 : index;
        return At(distance, reverse) with
        {
            SpeedMetersPerSecond = SpeedAt(speedIndex) * timeScale,
            ReachedEnd = false,
            Lap = lap,
            Reversed = reverse,
        };
    }

    private double SegmentBearing(int segmentIndex, bool reverse)
    {
        if (_points.Length < 2)
        {
            return 0;
        }

        var clamped = Math.Clamp(segmentIndex, 0, _points.Length - 2);
        for (var step = 0; step < _points.Length - 1; step++)
        {
            var candidate = clamped - step;
            if (candidate >= 0 && Geo.Distance(_points[candidate], _points[candidate + 1]) > 1e-6)
            {
                return reverse
                    ? Geo.Bearing(_points[candidate + 1], _points[candidate])
                    : Geo.Bearing(_points[candidate], _points[candidate + 1]);
            }

            candidate = clamped + step;
            if (candidate < _points.Length - 1 && Geo.Distance(_points[candidate], _points[candidate + 1]) > 1e-6)
            {
                return reverse
                    ? Geo.Bearing(_points[candidate + 1], _points[candidate])
                    : Geo.Bearing(_points[candidate], _points[candidate + 1]);
            }
        }

        return 0;
    }

    private double SpeedAt(int index)
    {
        if (_times is not null && index >= 0 && index < _points.Length - 1)
        {
            var span = _times[index + 1] - _times[index];
            if (span > 0)
            {
                return (_cumulative[index + 1] - _cumulative[index]) / span;
            }
        }

        return _duration > 0 ? _length / _duration : 0;
    }

    private static RouteVertex[] BuildEffectiveVertices(
        RouteVertex[] raw,
        bool autoClose,
        out bool isClosed,
        out bool autoClosed,
        out double closingGapMeters)
    {
        isClosed = false;
        autoClosed = false;
        closingGapMeters = 0;

        if (raw.Length < 2)
        {
            return raw;
        }

        closingGapMeters = Geo.Distance(raw[^1].Point, raw[0].Point);

        if (raw.Length < 3)
        {
            return raw;
        }

        if (closingGapMeters <= AlreadyClosedEpsilonMeters)
        {
            isClosed = true;
            return raw;
        }

        if (!autoClose || closingGapMeters >= AutoCloseThresholdMeters)
        {
            return raw;
        }

        // Smoothly close the loop by appending a final segment returning to the start point.
        double? closingTime = null;
        if (BuildTimeline(raw, out var rawDuration) is not null && raw[^1].TimeSeconds is { } lastTime)
        {
            var rawLength = 0.0;
            for (var index = 1; index < raw.Length; index++)
            {
                rawLength += Geo.Distance(raw[index - 1].Point, raw[index].Point);
            }

            var averageSpeed = rawDuration > 0.5 && rawLength > 0.5 ? rawLength / rawDuration : 1.4;
            var closeSeconds = Math.Clamp(closingGapMeters / Math.Max(0.3, averageSpeed), 0.5, 300.0);
            closingTime = lastTime + closeSeconds;
        }

        var extended = new RouteVertex[raw.Length + 1];
        Array.Copy(raw, extended, raw.Length);
        extended[^1] = new RouteVertex(raw[0].Point, closingTime);
        isClosed = true;
        autoClosed = true;
        return extended;
    }

    private static double[]? BuildTimeline(RouteVertex[] vertices, out double duration)
    {
        duration = 0;
        if (vertices.Length < 2 || vertices[0].TimeSeconds is not { } start)
        {
            return null;
        }

        var times = new double[vertices.Length];
        var previous = 0.0;

        for (var index = 0; index < vertices.Length; index++)
        {
            if (vertices[index].TimeSeconds is not { } time)
            {
                return null;
            }

            var offset = time - start;
            if (double.IsNaN(offset) || offset < previous)
            {
                return null;
            }

            times[index] = offset;
            previous = offset;
        }

        duration = times[^1];
        return duration > 0.5 ? times : null;
    }

    private static ulong ComputeSignature(RouteVertex[] vertices, bool includeAutoClose, bool autoClose)
    {
        // FNV-1a over the rounded coordinates and timestamps: stable across identical payloads,
        // different for edits.
        var hash = 14695981039346656037UL;
        hash = Mix(hash, (ulong)vertices.Length);
        if (includeAutoClose)
        {
            hash = Mix(hash, autoClose ? 1UL : 0UL);
        }

        foreach (var vertex in vertices)
        {
            hash = Mix(hash, (ulong)BitConverter.DoubleToInt64Bits(Math.Round(vertex.Point.Latitude, 7)));
            hash = Mix(hash, (ulong)BitConverter.DoubleToInt64Bits(Math.Round(vertex.Point.Longitude, 7)));
            hash = Mix(hash, vertex.Point.Altitude is { } altitude
                ? (ulong)BitConverter.DoubleToInt64Bits(Math.Round(altitude, 1))
                : ulong.MaxValue);
            hash = Mix(hash, vertex.TimeSeconds is { } time
                ? (ulong)BitConverter.DoubleToInt64Bits(Math.Round(time, 1))
                : ulong.MaxValue);
        }

        return hash;
    }

    private static ulong Mix(ulong hash, ulong value)
    {
        for (var byteIndex = 0; byteIndex < 8; byteIndex++)
        {
            hash ^= (value >> (byteIndex * 8)) & 0xFF;
            hash *= 1099511628211UL;
        }

        return hash;
    }
}
