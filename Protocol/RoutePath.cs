namespace Iap2Loc.Protocol;

/// <summary>A route vertex plus the GPX timestamp it was recorded at, in seconds.</summary>
internal readonly record struct RouteVertex(GeoPoint Point, double? TimeSeconds = null);

internal readonly record struct RouteSample(
    GeoPoint Point,
    double Bearing,
    double SpeedMetersPerSecond,
    double DistanceMeters);

/// <summary>
/// A polyline walked either at a constant ground speed or along the timestamps of an imported GPX.
/// Positions are a pure function of time, so several sessions sharing one target stay in sync no
/// matter when each of them asks for the next fix.
/// </summary>
internal sealed class RoutePath
{
    private readonly GeoPoint[] _points;
    private readonly double[] _cumulative;
    private readonly double[]? _times;
    private readonly double _length;
    private readonly double _duration;

    public RoutePath(IEnumerable<RouteVertex> vertices)
    {
        var list = vertices.ToArray();
        _points = list.Select(vertex => vertex.Point).ToArray();
        _cumulative = new double[_points.Length];

        for (var index = 1; index < _points.Length; index++)
        {
            _cumulative[index] = _cumulative[index - 1] + Geo.Distance(_points[index - 1], _points[index]);
        }

        _length = _cumulative.Length == 0 ? 0 : _cumulative[^1];
        _times = BuildTimeline(list, out _duration);
        Signature = ComputeSignature(list);
    }

    public int Count => _points.Length;

    public double LengthMeters => _length;

    /// <summary>True when every vertex carries a usable timestamp, so playback follows the GPX timing.</summary>
    public bool HasTimeline => _times is not null;

    public double DurationSeconds => _duration;

    /// <summary>True when the polyline can actually be walked; a single point cannot.</summary>
    public bool IsUsable => _points.Length >= 2 && _length > 0.5;

    /// <summary>Cheap identity used to tell "the same path again" from "a new path".</summary>
    public ulong Signature { get; }

    public IReadOnlyList<GeoPoint> Points => _points;

    /// <summary>Samples the polyline at a distance from the start, clamped to the ends.</summary>
    public RouteSample At(double travelledMeters)
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
            return new RouteSample(last, Geo.Bearing(_points[^2], last), 0, _length);
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

        return new RouteSample(new GeoPoint(latitude, longitude, altitude), Geo.Bearing(start, end), 0, distance);
    }

    /// <summary>
    /// Samples the polyline at an elapsed time using the GPX timestamps, so fast and slow sections keep
    /// their own pace. Wraps when looping, stops at the end otherwise.
    /// </summary>
    public RouteSample AtTime(double elapsedSeconds, bool loop)
    {
        if (_times is null || !IsUsable)
        {
            return At(0);
        }

        var seconds = loop
            ? ((elapsedSeconds % _duration) + _duration) % _duration
            : Math.Clamp(elapsedSeconds, 0, _duration);

        var index = Array.BinarySearch(_times, seconds);
        if (index < 0)
        {
            index = ~index - 1;
        }

        index = Math.Clamp(index, 0, _points.Length - 1);

        if (index >= _points.Length - 1)
        {
            var last = _points[^1];
            return new RouteSample(last, Geo.Bearing(_points[^2], last), SpeedAt(_points.Length - 2), _length);
        }

        var span = _times[index + 1] - _times[index];
        var ratio = span <= 0 ? 0 : (seconds - _times[index]) / span;
        var distance = _cumulative[index] + (_cumulative[index + 1] - _cumulative[index]) * ratio;

        return At(distance) with { SpeedMetersPerSecond = SpeedAt(index) };
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

    private ulong ComputeSignature(RouteVertex[] vertices)
    {
        // FNV-1a over the rounded coordinates and timestamps: stable across identical payloads,
        // different for edits.
        var hash = 14695981039346656037UL;
        hash = Mix(hash, (ulong)vertices.Length);

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
