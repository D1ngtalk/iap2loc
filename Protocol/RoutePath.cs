namespace Iap2Loc.Protocol;

internal readonly record struct RouteSample(GeoPoint Point, double Bearing);

/// <summary>
/// A polyline walked at a constant ground speed. Positions are a pure function of time, so several
/// sessions sharing one target stay in sync no matter when each of them asks for the next fix.
/// </summary>
internal sealed class RoutePath
{
    private readonly GeoPoint[] _points;
    private readonly double[] _cumulative;
    private readonly double _length;

    public RoutePath(IEnumerable<GeoPoint> points)
    {
        _points = points.ToArray();
        _cumulative = new double[_points.Length];

        for (var index = 1; index < _points.Length; index++)
        {
            _cumulative[index] = _cumulative[index - 1] + Geo.Distance(_points[index - 1], _points[index]);
        }

        _length = _cumulative.Length == 0 ? 0 : _cumulative[^1];
        Signature = ComputeSignature();
    }

    public int Count => _points.Length;

    public double LengthMeters => _length;

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
            return new RouteSample(default, 0);
        }

        if (_points.Length == 1 || _length <= 0)
        {
            return new RouteSample(_points[^1], 0);
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
            return new RouteSample(last, Geo.Bearing(_points[^2], last));
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

        return new RouteSample(new GeoPoint(latitude, longitude, altitude), Geo.Bearing(start, end));
    }

    private ulong ComputeSignature()
    {
        // FNV-1a over the rounded coordinates: stable across identical payloads, different for edits.
        var hash = 14695981039346656037UL;
        hash = Mix(hash, (ulong)_points.Length);

        foreach (var point in _points)
        {
            hash = Mix(hash, (ulong)BitConverter.DoubleToInt64Bits(Math.Round(point.Latitude, 7)));
            hash = Mix(hash, (ulong)BitConverter.DoubleToInt64Bits(Math.Round(point.Longitude, 7)));
            hash = Mix(hash, point.Altitude is { } altitude
                ? (ulong)BitConverter.DoubleToInt64Bits(Math.Round(altitude, 1))
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
