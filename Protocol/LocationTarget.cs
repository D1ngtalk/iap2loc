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

/// <summary>
/// The live target shared by every running session. Changing it takes effect on the next
/// 1 Hz tick, so the map can be clicked without reconnecting anything.
/// </summary>
internal sealed class LocationTarget
{
    private readonly object _gate = new();

    private double _latitude;
    private double _longitude;
    private double? _altitudeMeters;
    private double? _accuracyMeters;
    private double? _speedMetersPerSecond;
    private double? _bearingDegrees;
    private bool _jitterPosition;
    private double _jitterMeters;
    private bool _jitterAccuracy;
    private double _accuracyMinMeters = 3;
    private double _accuracyMaxMeters = 12;
    private int _quality = 1;
    private int _satellites = 8;

    // Rolled once per target change, then reused by every fix. A point that keeps re-rolling
    // every second looks like a broken GPS, not like a real one.
    private double _resolvedLatitude;
    private double _resolvedLongitude;
    private double? _resolvedAccuracyMeters;

    public LocationTarget(double latitude, double longitude)
    {
        _latitude = latitude;
        _longitude = longitude;
        Resolve();
    }

    public void Apply(TargetUpdate update)
    {
        lock (_gate)
        {
            var oldLatitude = _latitude;
            var oldLongitude = _longitude;
            var oldAccuracy = _accuracyMeters;
            var oldJitterPosition = _jitterPosition;
            var oldJitterMeters = _jitterMeters;
            var oldJitterAccuracy = _jitterAccuracy;
            var oldAccuracyMin = _accuracyMinMeters;
            var oldAccuracyMax = _accuracyMaxMeters;

            if (update.Latitude is { } latitude) _latitude = latitude;
            if (update.Longitude is { } longitude) _longitude = longitude;
            if (update.Altitude is { } altitude) _altitudeMeters = altitude;
            if (update.Accuracy is { } accuracy) _accuracyMeters = accuracy;
            if (update.Speed is { } speed) _speedMetersPerSecond = speed;
            if (update.Bearing is { } bearing) _bearingDegrees = bearing;
            if (update.JitterPosition is { } jitterPosition) _jitterPosition = jitterPosition;
            if (update.JitterMeters is { } jitterMeters) _jitterMeters = Math.Max(0, jitterMeters);
            if (update.JitterAccuracy is { } jitterAccuracy) _jitterAccuracy = jitterAccuracy;
            if (update.AccuracyMin is { } minimum) _accuracyMinMeters = Math.Max(0.1, minimum);
            if (update.AccuracyMax is { } maximum) _accuracyMaxMeters = Math.Max(_accuracyMinMeters, maximum);
            if (update.Quality is { } quality) _quality = Math.Clamp(quality, 0, 8);
            if (update.Satellites is { } satellites) _satellites = Math.Clamp(satellites, 0, 99);

            // The jitter is rolled once per real change. The UI re-sends every field on each
            // keystroke, so identical values must not re-roll behind the user's back.
            var positionChanged = _latitude != oldLatitude || _longitude != oldLongitude;
            var jitterChanged = _jitterPosition != oldJitterPosition ||
                                Math.Abs(_jitterMeters - oldJitterMeters) > 1e-9 ||
                                _jitterAccuracy != oldJitterAccuracy ||
                                Math.Abs(_accuracyMinMeters - oldAccuracyMin) > 1e-9 ||
                                Math.Abs(_accuracyMaxMeters - oldAccuracyMax) > 1e-9;

            if (positionChanged || jitterChanged)
            {
                Resolve();
            }
            else if (!_jitterAccuracy && _accuracyMeters != oldAccuracy)
            {
                _resolvedAccuracyMeters = _accuracyMeters;
            }
        }
    }

    public TargetState Snapshot()
    {
        lock (_gate)
        {
            return new TargetState(
                _latitude, _longitude, _altitudeMeters, _accuracyMeters, _speedMetersPerSecond, _bearingDegrees,
                _jitterPosition, _jitterMeters, _jitterAccuracy, _accuracyMinMeters, _accuracyMaxMeters,
                _resolvedLatitude, _resolvedLongitude, _resolvedAccuracyMeters, _quality, _satellites);
        }
    }

    /// <summary>Next fix. The jitter was already rolled once when the target was set.</summary>
    public LocationFix Next()
    {
        lock (_gate)
        {
            return new LocationFix(
                _resolvedLatitude, _resolvedLongitude, _altitudeMeters,
                _speedMetersPerSecond, _bearingDegrees, _resolvedAccuracyMeters, _quality, _satellites);
        }
    }

    /// <summary>One roll per target change: a fixed offset and a fixed accuracy for the whole stop.</summary>
    private void Resolve()
    {
        _resolvedLatitude = _latitude;
        _resolvedLongitude = _longitude;
        _resolvedAccuracyMeters = _accuracyMeters;

        if (_jitterPosition && _jitterMeters > 0)
        {
            // Uniform inside a disc, so the offset is not biased towards the centre.
            var angle = Random.Shared.NextDouble() * Math.PI * 2;
            var distance = Math.Sqrt(Random.Shared.NextDouble()) * _jitterMeters;
            (_resolvedLatitude, _resolvedLongitude) = OffsetMeters(_latitude, _longitude, distance, angle);
        }

        if (_jitterAccuracy)
        {
            var span = Math.Max(0, _accuracyMaxMeters - _accuracyMinMeters);
            _resolvedAccuracyMeters = Math.Round(_accuracyMinMeters + Random.Shared.NextDouble() * span, 1);
        }
    }

    private static (double Latitude, double Longitude) OffsetMeters(double latitude, double longitude, double distance, double angle)
    {
        const double metresPerDegree = 111_320.0;
        var deltaLatitude = distance * Math.Cos(angle) / metresPerDegree;
        var deltaLongitude = distance * Math.Sin(angle) /
                             (metresPerDegree * Math.Max(0.01, Math.Cos(latitude * Math.PI / 180)));
        return (latitude + deltaLatitude, longitude + deltaLongitude);
    }
}

/// <summary>Partial update coming from the UI; every field is optional.</summary>
internal sealed record TargetUpdate
{
    public double? Latitude { get; init; }
    public double? Longitude { get; init; }
    public double? Altitude { get; init; }
    public double? Accuracy { get; init; }
    public double? Speed { get; init; }
    public double? Bearing { get; init; }
    public bool? JitterPosition { get; init; }
    public double? JitterMeters { get; init; }
    public bool? JitterAccuracy { get; init; }
    public double? AccuracyMin { get; init; }
    public double? AccuracyMax { get; init; }
    public int? Quality { get; init; }
    public int? Satellites { get; init; }
}

internal sealed record TargetState(
    double Latitude,
    double Longitude,
    double? Altitude,
    double? Accuracy,
    double? Speed,
    double? Bearing,
    bool JitterPosition,
    double JitterMeters,
    bool JitterAccuracy,
    double AccuracyMin,
    double AccuracyMax,
    double ResolvedLatitude,
    double ResolvedLongitude,
    double? ResolvedAccuracy,
    int Quality,
    int Satellites);
