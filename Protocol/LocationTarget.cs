namespace Iap2Loc.Protocol;

internal readonly record struct LocationFix(
    double Latitude,
    double Longitude,
    double? AltitudeMeters,
    double? SpeedMetersPerSecond,
    double? BearingDegrees,
    double? AccuracyMeters,
    int Quality,
    int Satellites);

/// <summary>How the target moves. Adding a mode means adding a branch in <see cref="LocationTarget.Next"/>.</summary>
internal enum TargetMode
{
    Point = 0,
    Path = 1,
}

/// <summary>
/// The live target shared by every running session. Changing it takes effect on the next tick, so
/// the map can be clicked without reconnecting anything.
/// </summary>
internal sealed class LocationTarget
{
    /// <summary>Refuse absurd payloads; a GPX with more vertices than this is downsampled by the UI.</summary>
    public const int MaxRoutePoints = 20_000;

    private readonly object _gate = new();

    private TargetMode _mode = TargetMode.Point;
    private double _latitude;
    private double _longitude;
    private double? _altitudeMeters;
    private double? _accuracyMeters;
    private double? _speedMetersPerSecond;
    private double? _bearingDegrees;
    private double _jitterMeters;
    private bool _jitterAccuracy;
    private double _accuracyMinMeters = 3;
    private double _accuracyMaxMeters = 12;
    private int _quality = 1;
    private int _satellites = 8;
    private bool _jitterAltitude;
    private double _altitudeMinMeters = 5;
    private double _altitudeMaxMeters = 20;

    private RoutePath? _route;
    private ulong _routeSignature;
    private double _pathSpeedMps = 1.4;
    private bool _pathLoop = true;
    private long _pathAnchorTicks = Environment.TickCount64;
    private double _pathAnchorMeters;
    private double _pathAnchorSeconds;

    // Rolled once per target change, then reused by every fix. A point that keeps re-rolling
    // every second looks like a broken GPS, not like a real one.
    private double _resolvedLatitude;
    private double _resolvedLongitude;
    private double? _resolvedAccuracyMeters;
    private double? _resolvedAltitudeMeters;

    public LocationTarget(double latitude, double longitude)
    {
        _latitude = latitude;
        _longitude = longitude;
        _resolvedLatitude = latitude;
        _resolvedLongitude = longitude;
        Resolve();
    }

    public void Apply(TargetUpdate update)
    {
        lock (_gate)
        {
            var oldLatitude = _latitude;
            var oldLongitude = _longitude;
            var oldAccuracy = _accuracyMeters;
            var oldAltitude = _altitudeMeters;
            var oldJitterMeters = _jitterMeters;
            var oldJitterAccuracy = _jitterAccuracy;
            var oldAccuracyMin = _accuracyMinMeters;
            var oldAccuracyMax = _accuracyMaxMeters;
            var oldJitterAltitude = _jitterAltitude;
            var oldAltitudeMin = _altitudeMinMeters;
            var oldAltitudeMax = _altitudeMaxMeters;

            var restartPath = false;

            if (update.Mode is { } mode && mode != _mode)
            {
                _mode = mode;
                restartPath = true;
            }

            if (update.Route is { } vertices)
            {
                var route = vertices.Count == 0
                    ? null
                    : new RoutePath(vertices.Take(MaxRoutePoints));

                var signature = route?.Signature ?? 0;
                if (signature != _routeSignature || (route?.Count ?? 0) != (_route?.Count ?? 0))
                {
                    _route = route;
                    _routeSignature = signature;
                    restartPath = true;
                }
            }

            if (update.PathLoop is { } loop && loop != _pathLoop)
            {
                RebasePath();
                _pathLoop = loop;
            }

            if (update.PathSpeedMps is { } speed)
            {
                var next = Math.Clamp(speed, 0.1, 50);
                if (Math.Abs(next - _pathSpeedMps) > 1e-9)
                {
                    RebasePath();
                    _pathSpeedMps = next;
                }
            }

            if (update.Latitude is { } latitude) _latitude = latitude;
            if (update.Longitude is { } longitude) _longitude = longitude;
            if (update.Altitude is { } altitude) _altitudeMeters = altitude;
            if (update.Accuracy is { } accuracy) _accuracyMeters = accuracy;
            if (update.Speed is { } pointSpeed) _speedMetersPerSecond = pointSpeed;
            if (update.Bearing is { } bearing) _bearingDegrees = bearing;
            if (update.JitterMeters is { } jitterMeters) _jitterMeters = Math.Max(0, jitterMeters);
            if (update.JitterAccuracy is { } jitterAccuracy) _jitterAccuracy = jitterAccuracy;
            if (update.AccuracyMin is { } minimum) _accuracyMinMeters = Math.Max(0.1, minimum);
            if (update.AccuracyMax is { } maximum) _accuracyMaxMeters = Math.Max(_accuracyMinMeters, maximum);
            if (update.Quality is { } quality) _quality = Math.Clamp(quality, 0, 8);
            if (update.Satellites is { } satellites) _satellites = Math.Clamp(satellites, 0, 99);
            if (update.JitterAltitude is { } jitterAltitude) _jitterAltitude = jitterAltitude;
            if (update.AltitudeMin is { } altitudeMin) _altitudeMinMeters = altitudeMin;
            if (update.AltitudeMax is { } altitudeMax) _altitudeMaxMeters = Math.Max(_altitudeMinMeters, altitudeMax);

            if (restartPath)
            {
                _pathAnchorMeters = 0;
                _pathAnchorSeconds = 0;
                _pathAnchorTicks = Environment.TickCount64;
            }

            // The jitter is rolled once per real change. The UI re-sends every field on each
            // keystroke, so identical values must not re-roll behind the user's back.
            var positionChanged = _latitude != oldLatitude || _longitude != oldLongitude;
            var jitterChanged = Math.Abs(_jitterMeters - oldJitterMeters) > 1e-9 ||
                                _jitterAccuracy != oldJitterAccuracy ||
                                Math.Abs(_accuracyMinMeters - oldAccuracyMin) > 1e-9 ||
                                Math.Abs(_accuracyMaxMeters - oldAccuracyMax) > 1e-9 ||
                                _jitterAltitude != oldJitterAltitude ||
                                Math.Abs(_altitudeMinMeters - oldAltitudeMin) > 1e-9 ||
                                Math.Abs(_altitudeMaxMeters - oldAltitudeMax) > 1e-9;

            if (positionChanged || jitterChanged || restartPath)
            {
                Resolve();
            }
            else
            {
                if (!_jitterAccuracy && _accuracyMeters != oldAccuracy)
                {
                    _resolvedAccuracyMeters = _accuracyMeters;
                }

                if (!_jitterAltitude && _altitudeMeters != oldAltitude)
                {
                    _resolvedAltitudeMeters = _altitudeMeters;
                }
            }
        }
    }

    public TargetState Snapshot()
    {
        lock (_gate)
        {
            return new TargetState(
                _mode,
                _latitude, _longitude, _altitudeMeters, _accuracyMeters, _speedMetersPerSecond, _bearingDegrees,
                _jitterMeters, _jitterAccuracy, _accuracyMinMeters, _accuracyMaxMeters,
                _jitterAltitude, _altitudeMinMeters, _altitudeMaxMeters,
                _resolvedLatitude, _resolvedLongitude, _resolvedAccuracyMeters, _resolvedAltitudeMeters,
                _route?.Count ?? 0, _route?.LengthMeters ?? 0,
                _route is null ? 0 : CurrentSample().DistanceMeters,
                _pathSpeedMps, _pathLoop,
                _route?.DurationSeconds ?? 0, _route?.HasTimeline ?? false,
                _quality, _satellites);
        }
    }

    /// <summary>The fix for right now. Jitter was already rolled when the target was set.</summary>
    public LocationFix Next()
    {
        lock (_gate)
        {
            if (_mode == TargetMode.Path && _route is { IsUsable: true } route)
            {
                var sample = CurrentSample();
                return new LocationFix(
                    sample.Point.Latitude,
                    sample.Point.Longitude,
                    sample.Point.Altitude ?? _resolvedAltitudeMeters,
                    route.HasTimeline ? sample.SpeedMetersPerSecond : _pathSpeedMps,
                    sample.Bearing,
                    _resolvedAccuracyMeters,
                    _quality,
                    _satellites);
            }

            return new LocationFix(
                _resolvedLatitude, _resolvedLongitude, _resolvedAltitudeMeters,
                _speedMetersPerSecond, _bearingDegrees, _resolvedAccuracyMeters, _quality, _satellites);
        }
    }

    /// <summary>Where the path is right now: GPX timing when available, constant speed otherwise.</summary>
    private RouteSample CurrentSample()
    {
        var route = _route!;
        var elapsed = Math.Max(0, Environment.TickCount64 - _pathAnchorTicks) / 1000.0;

        if (route.HasTimeline)
        {
            return route.AtTime(_pathAnchorSeconds + elapsed, _pathLoop);
        }

        var distance = _pathAnchorMeters + _pathSpeedMps * elapsed;
        var length = route.LengthMeters;
        if (length <= 0)
        {
            return route.At(0);
        }

        return route.At(_pathLoop ? distance % length : Math.Min(distance, length));
    }

    /// <summary>Keeps the current position when speed or looping changes mid-path.</summary>
    private void RebasePath()
    {
        var elapsed = Math.Max(0, Environment.TickCount64 - _pathAnchorTicks) / 1000.0;

        if (_route is { HasTimeline: true })
        {
            _pathAnchorSeconds += elapsed;
        }
        else if (_route is { IsUsable: true })
        {
            _pathAnchorMeters = CurrentSample().DistanceMeters;
        }

        _pathAnchorTicks = Environment.TickCount64;
    }

    /// <summary>One roll per target change: a fixed offset, accuracy and altitude for the whole stop.</summary>
    private void Resolve()
    {
        _resolvedLatitude = _latitude;
        _resolvedLongitude = _longitude;
        _resolvedAccuracyMeters = _accuracyMeters;
        _resolvedAltitudeMeters = _altitudeMeters;

        if (_jitterMeters > 0)
        {
            // Uniform inside a disc, so the offset is not biased towards the centre.
            var angle = Random.Shared.NextDouble() * Math.PI * 2;
            var distance = Math.Sqrt(Random.Shared.NextDouble()) * _jitterMeters;
            var shifted = Geo.Offset(new GeoPoint(_latitude, _longitude), distance, angle);
            _resolvedLatitude = shifted.Latitude;
            _resolvedLongitude = shifted.Longitude;
        }

        if (_jitterAccuracy)
        {
            var span = Math.Max(0, _accuracyMaxMeters - _accuracyMinMeters);
            _resolvedAccuracyMeters = Math.Round(_accuracyMinMeters + Random.Shared.NextDouble() * span, 1);
        }

        if (_jitterAltitude)
        {
            var span = Math.Max(0, _altitudeMaxMeters - _altitudeMinMeters);
            _resolvedAltitudeMeters = Math.Round(_altitudeMinMeters + Random.Shared.NextDouble() * span, 1);
        }
    }
}

/// <summary>Partial update coming from the UI; every field is optional.</summary>
internal sealed record TargetUpdate
{
    public TargetMode? Mode { get; init; }

    /// <summary>null keeps the current route, an empty list clears it.</summary>
    public IReadOnlyList<RouteVertex>? Route { get; init; }

    public double? PathSpeedMps { get; init; }
    public bool? PathLoop { get; init; }
    public double? Latitude { get; init; }
    public double? Longitude { get; init; }
    public double? Altitude { get; init; }
    public double? Accuracy { get; init; }
    public double? Speed { get; init; }
    public double? Bearing { get; init; }
    public double? JitterMeters { get; init; }
    public bool? JitterAccuracy { get; init; }
    public double? AccuracyMin { get; init; }
    public double? AccuracyMax { get; init; }
    public int? Quality { get; init; }
    public int? Satellites { get; init; }
    public bool? JitterAltitude { get; init; }
    public double? AltitudeMin { get; init; }
    public double? AltitudeMax { get; init; }
}

internal sealed record TargetState(
    TargetMode Mode,
    double Latitude,
    double Longitude,
    double? Altitude,
    double? Accuracy,
    double? Speed,
    double? Bearing,
    double JitterMeters,
    bool JitterAccuracy,
    double AccuracyMin,
    double AccuracyMax,
    bool JitterAltitude,
    double AltitudeMin,
    double AltitudeMax,
    double ResolvedLatitude,
    double ResolvedLongitude,
    double? ResolvedAccuracy,
    double? ResolvedAltitude,
    int RoutePoints,
    double RouteLengthMeters,
    double PathTravelledMeters,
    double PathSpeedMps,
    bool PathLoop,
    double PathDurationSeconds,
    bool PathTimed,
    int Quality,
    int Satellites);
