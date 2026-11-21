using System.Diagnostics;

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
    private double? _altitudeMeters = 5;
    private double? _accuracyMeters = 2.5;
    private double? _speedMetersPerSecond;
    private double? _bearingDegrees;
    private double _jitterMeters;
    private bool _jitterAccuracy;
    private double _accuracyMinMeters = 3;
    private double _accuracyMaxMeters = 12;
    private int _quality = 4;
    private int _satellites = 20;
    private bool _jitterAltitude;
    private double _altitudeMinMeters = 5;
    private double _altitudeMaxMeters = 20;

    private RoutePath? _route;
    private ulong _routeSignature;
    private double _pathSpeedMps = 5.0 / 3.6;
    private PathLoopMode _pathLoopMode = PathLoopMode.Loop;
    private bool _pathAutoClose = true;
    private bool _pathPaused;
    private bool _pathUseGpxTime = true;
    private double _pathTimeScale = 1.0;
    private double _pathSpeedJitterPercent;

    /// <summary>Smooth acceleration / deceleration bounds so sudden speed switches never create velocity shocks.</summary>
    private const double MaxAccelerationMps2 = 3.5;
    private const double MaxDecelerationMps2 = 4.5;

    private static double NowSeconds() => Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency;

    private double _pathLastSeconds = NowSeconds();
    private double _pathTotalTravelledMeters;
    private double _pathTotalElapsedSeconds;
    private double _pathCurrentSpeedMps;

    // Smooth speed fluctuation state (low-pass random walk around 1.0).
    private double _speedFactor = 1.0;
    private double _speedFactorTarget = 1.0;
    private double _speedRetargetSeconds;

    // Smooth GPS wander state for path mode (Ornstein-Uhlenbeck-like interpolation).
    private double _driftNorthMeters;
    private double _driftEastMeters;
    private double _driftTargetNorthMeters;
    private double _driftTargetEastMeters;
    private double _driftRetargetSeconds;
    private double _driftLastSeconds = NowSeconds();
    private double _dynamicAccuracyCurrent = 5.0;
    private double _dynamicAccuracyTarget = 5.0;
    private double _dynamicAltitudeCurrent = 10.0;
    private double _dynamicAltitudeTarget = 10.0;
    private double _sensorRetargetSeconds;
    private double _sensorLastSeconds = NowSeconds();

    // Rolled once per target change in point mode, then reused by every fix.
    private double _resolvedLatitude;
    private double _resolvedLongitude;
    private double? _resolvedAccuracyMeters;
    private double? _resolvedAltitudeMeters;

    public LocationTarget(double latitude, double longitude)
    {
        var initial = Geo.Normalize(new GeoPoint(latitude, longitude));
        _latitude = initial.Latitude;
        _longitude = initial.Longitude;
        _resolvedLatitude = initial.Latitude;
        _resolvedLongitude = initial.Longitude;
        Resolve();
    }

    public void Apply(TargetUpdate update)
    {
        lock (_gate)
        {
            var now = NowSeconds();
            AdvancePathLocked(now);

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

            if (update.PathAutoClose is { } nextAutoClose && nextAutoClose != _pathAutoClose)
            {
                _pathAutoClose = nextAutoClose;
                if (update.Route is null && _route is not null)
                {
                    var sampleBefore = CurrentSampleLocked();
                    _route = new RoutePath(_route.InputVertices, _pathAutoClose);
                    _routeSignature = _route.Signature;
                    ReanchorToSampleLocked(sampleBefore, _pathLoopMode);
                }
            }

            if (update.Mode is { } mode && mode != _mode)
            {
                _mode = mode;
                restartPath = true;
                if (mode == TargetMode.Path && update.PathPaused is null)
                {
                    _pathPaused = false;
                }
            }

            if (update.Route is { } vertices)
            {
                var route = vertices.Count == 0
                    ? null
                    : new RoutePath(vertices.Take(MaxRoutePoints), _pathAutoClose);

                var signature = route?.Signature ?? 0;
                if (signature != _routeSignature || (route?.InputCount ?? 0) != (_route?.InputCount ?? 0))
                {
                    var sameWaypoints = route is not null && _route is not null &&
                                        route.InputSignature == _route.InputSignature;
                    var sampleBefore = sameWaypoints && _route is { IsUsable: true }
                        ? CurrentSampleLocked()
                        : default;

                    _route = route;
                    _routeSignature = signature;

                    if (sameWaypoints && _route is { IsUsable: true })
                    {
                        ReanchorToSampleLocked(sampleBefore, _pathLoopMode);
                    }
                    else
                    {
                        restartPath = true;
                    }
                }
            }

            var requestedLoopMode = update.PathLoopMode ?? (update.PathLoop is { } legacyLoop
                ? (legacyLoop ? PathLoopMode.Loop : PathLoopMode.Once)
                : (PathLoopMode?)null);

            if (requestedLoopMode is { } nextLoopMode && nextLoopMode != _pathLoopMode)
            {
                if (!restartPath && _route is { IsUsable: true })
                {
                    var currentSample = CurrentSampleLocked();
                    _pathLoopMode = nextLoopMode;
                    ReanchorToSampleLocked(currentSample, nextLoopMode);
                }
                else
                {
                    _pathLoopMode = nextLoopMode;
                }
            }

            if (update.PathSpeedMps is { } speed && double.IsFinite(speed))
            {
                _pathSpeedMps = Math.Clamp(speed, 0.1, 60.0);
            }

            if (update.PathSpeedJitterPercent is { } speedJitter && double.IsFinite(speedJitter))
            {
                _pathSpeedJitterPercent = Math.Clamp(speedJitter, 0, 40);
                if (_pathSpeedJitterPercent <= 0)
                {
                    _speedFactor = 1.0;
                    _speedFactorTarget = 1.0;
                }
            }

            if (update.PathTimeScale is { } timeScale && double.IsFinite(timeScale))
            {
                _pathTimeScale = Math.Clamp(timeScale, 0.1, 10.0);
            }

            if (update.PathUseGpxTime is { } useGpxTime && useGpxTime != _pathUseGpxTime)
            {
                if (!restartPath && _route is { IsUsable: true })
                {
                    var currentSample = CurrentSampleLocked();
                    _pathUseGpxTime = useGpxTime;
                    ReanchorToSampleLocked(currentSample, _pathLoopMode);
                }
                else
                {
                    _pathUseGpxTime = useGpxTime;
                }
            }

            if (update.Latitude is { } latitude && double.IsFinite(latitude))
            {
                _latitude = Geo.NormalizeLatitude(latitude);
            }

            if (update.Longitude is { } longitude && double.IsFinite(longitude))
            {
                _longitude = Geo.NormalizeLongitude(longitude);
            }

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

            if (restartPath || update.PathRestart == true)
            {
                _pathTotalTravelledMeters = 0;
                _pathTotalElapsedSeconds = 0;
                _pathCurrentSpeedMps = 0;
                _pathLastSeconds = now;
                _driftNorthMeters = 0;
                _driftEastMeters = 0;
                _driftTargetNorthMeters = 0;
                _driftTargetEastMeters = 0;
                _driftRetargetSeconds = 0;
                _speedFactor = 1.0;
                _speedFactorTarget = 1.0;
                _speedRetargetSeconds = 0;
                if (restartPath && update.PathPaused is null)
                {
                    _pathPaused = false;
                }
            }

            if (update.PathSeekProgress is { } seekProgress && double.IsFinite(seekProgress) && _route is { IsUsable: true } seekRoute)
            {
                var clamped = Math.Clamp(seekProgress, 0, 1);
                var targetDistance = clamped * seekRoute.LengthMeters;
                _pathTotalTravelledMeters = targetDistance;
                _pathTotalElapsedSeconds = seekRoute.HasTimeline
                    ? seekRoute.TimeAtDistance(targetDistance)
                    : 0;
                _pathLastSeconds = now;
            }

            if (update.PathPaused is { } nextPaused)
            {
                if (!nextPaused && _pathPaused && _route is { IsUsable: true })
                {
                    var currentSample = CurrentSampleLocked();
                    if (currentSample.ReachedEnd && _pathLoopMode == PathLoopMode.Once)
                    {
                        _pathTotalTravelledMeters = 0;
                        _pathTotalElapsedSeconds = 0;
                    }
                }

                _pathPaused = nextPaused;
                _pathLastSeconds = now;
                if (_pathPaused)
                {
                    _pathCurrentSpeedMps = 0;
                }
            }

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

            if (_jitterMeters <= 0)
            {
                _driftNorthMeters = 0;
                _driftEastMeters = 0;
                _driftTargetNorthMeters = 0;
                _driftTargetEastMeters = 0;
            }
        }
    }

    public RouteSnapshot GetRouteSnapshot()
    {
        lock (_gate)
        {
            if (_route is null)
            {
                return new RouteSnapshot("0", "0", _pathAutoClose, false, false, 0, Array.Empty<RouteVertex>());
            }

            return new RouteSnapshot(
                _route.Signature.ToString("x16"),
                _route.InputSignature.ToString("x16"),
                _pathAutoClose,
                _route.IsClosed,
                _route.AutoClosed,
                _route.ClosingGapMeters,
                _route.InputVertices.ToArray());
        }
    }

    public TargetState Snapshot()
    {
        lock (_gate)
        {
            var now = NowSeconds();
            AdvancePathLocked(now);
            UpdatePathSensorsLocked(now);

            var hasPath = _mode == TargetMode.Path && _route is { IsUsable: true };
            var sample = _route is { IsUsable: true } ? CurrentSampleLocked() : default;
            var livePoint = hasPath ? ApplyPathDriftLocked(sample.Point, now) : new GeoPoint(_resolvedLatitude, _resolvedLongitude, _resolvedAltitudeMeters);
            var currentSpeed = hasPath && !_pathPaused && !sample.ReachedEnd ? _pathCurrentSpeedMps : 0.0;
            var currentBearing = hasPath ? (double?)sample.Bearing : _bearingDegrees;
            var currentAltitude = hasPath ? (sample.Point.Altitude ?? _resolvedAltitudeMeters) : _resolvedAltitudeMeters;

            return new TargetState(
                _mode,
                _latitude, _longitude, _altitudeMeters, _accuracyMeters, _speedMetersPerSecond, _bearingDegrees,
                _jitterMeters, _jitterAccuracy, _accuracyMinMeters, _accuracyMaxMeters,
                _jitterAltitude, _altitudeMinMeters, _altitudeMaxMeters,
                _resolvedLatitude, _resolvedLongitude, _resolvedAccuracyMeters, _resolvedAltitudeMeters,
                _route?.InputCount ?? 0, _route?.LengthMeters ?? 0,
                _route is null ? 0 : sample.DistanceMeters,
                _pathSpeedMps, _pathLoopMode != PathLoopMode.Once,
                _route?.DurationSeconds ?? 0, _route?.HasTimeline ?? false,
                _quality, _satellites,
                _pathLoopMode,
                _pathAutoClose,
                _route?.IsClosed ?? false,
                _route?.AutoClosed ?? false,
                _route?.ClosingGapMeters ?? 0,
                _route is null ? "0" : _route.Signature.ToString("x16"),
                _route is null ? "0" : _route.InputSignature.ToString("x16"),
                _pathPaused,
                hasPath && sample.ReachedEnd,
                _route is null ? 1 : sample.Lap,
                _route is not null && sample.Reversed,
                currentSpeed,
                _pathSpeedJitterPercent,
                _pathUseGpxTime,
                _pathTimeScale,
                _route is { HasTimeline: true } ? CurrentTimelineSecondsLocked() : 0,
                livePoint.Latitude,
                livePoint.Longitude,
                currentBearing,
                currentAltitude,
                _resolvedAccuracyMeters);
        }
    }

    /// <summary>The fix for right now. Smoothly integrates path motion and optional GPS drift.</summary>
    public LocationFix Next()
    {
        lock (_gate)
        {
            var now = NowSeconds();
            AdvancePathLocked(now);
            UpdatePathSensorsLocked(now);

            if (_mode == TargetMode.Path && _route is { IsUsable: true })
            {
                var sample = CurrentSampleLocked();
                var shifted = ApplyPathDriftLocked(sample.Point, now);
                var reportedSpeed = _pathPaused || sample.ReachedEnd ? 0.0 : _pathCurrentSpeedMps;

                return new LocationFix(
                    shifted.Latitude,
                    shifted.Longitude,
                    shifted.Altitude ?? _resolvedAltitudeMeters,
                    reportedSpeed,
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

    private bool UsingGpxTimelineLocked() =>
        _route is { IsUsable: true, HasTimeline: true } && _pathUseGpxTime;

    private void AdvancePathLocked(double nowSeconds)
    {
        var dt = Math.Max(0, nowSeconds - _pathLastSeconds);
        _pathLastSeconds = nowSeconds;

        if (_mode != TargetMode.Path || _route is not { IsUsable: true } route)
        {
            _pathCurrentSpeedMps = 0;
            return;
        }

        if (_pathPaused)
        {
            _pathCurrentSpeedMps = 0;
            return;
        }

        if (UsingGpxTimelineLocked())
        {
            _pathTotalElapsedSeconds += dt * _pathTimeScale;
            if (_pathLoopMode == PathLoopMode.Once && _pathTotalElapsedSeconds >= route.DurationSeconds)
            {
                _pathTotalElapsedSeconds = route.DurationSeconds;
            }

            var timedSample = route.SampleByTime(_pathTotalElapsedSeconds, _pathLoopMode, _pathTimeScale);
            _pathCurrentSpeedMps = timedSample.ReachedEnd ? 0 : timedSample.SpeedMetersPerSecond;
            return;
        }

        // Smooth speed fluctuation around the configured speed.
        var targetSpeed = _pathSpeedMps;
        if (_pathSpeedJitterPercent > 0)
        {
            if (nowSeconds >= _speedRetargetSeconds)
            {
                var spread = _pathSpeedJitterPercent / 100.0;
                _speedFactorTarget = 1.0 + (Random.Shared.NextDouble() * 2.0 - 1.0) * spread;
                _speedRetargetSeconds = nowSeconds + 2.0 + Random.Shared.NextDouble() * 3.0;
            }

            var alpha = 1.0 - Math.Exp(-Math.Min(dt, 2.0) / 2.2);
            _speedFactor += (_speedFactorTarget - _speedFactor) * alpha;
            targetSpeed = Math.Max(0.1, _pathSpeedMps * _speedFactor);
        }
        else
        {
            _speedFactor = 1.0;
            _speedFactorTarget = 1.0;
        }

        // Exact piecewise-linear ramp integration so sudden speed switches accelerate/decelerate
        // smoothly and coordinate displacement always matches reported GPRMC speed.
        if (dt > 0)
        {
            var v0 = Math.Max(0, _pathCurrentSpeedMps);
            var delta = targetSpeed - v0;
            var rate = delta >= 0 ? MaxAccelerationMps2 : MaxDecelerationMps2;
            var maxStep = rate * dt;

            double distanceStep;
            double v1;
            if (Math.Abs(delta) <= maxStep)
            {
                var tRamp = rate > 0 ? Math.Abs(delta) / rate : 0;
                var tConst = dt - tRamp;
                distanceStep = 0.5 * (v0 + targetSpeed) * tRamp + targetSpeed * tConst;
                v1 = targetSpeed;
            }
            else
            {
                v1 = v0 + Math.Sign(delta) * maxStep;
                distanceStep = 0.5 * (v0 + v1) * dt;
            }

            _pathTotalTravelledMeters += distanceStep;
            _pathCurrentSpeedMps = Math.Max(0, v1);
        }

        if (_pathLoopMode == PathLoopMode.Once && _pathTotalTravelledMeters >= route.LengthMeters)
        {
            _pathTotalTravelledMeters = route.LengthMeters;
            _pathCurrentSpeedMps = 0;
        }
    }

    private RouteSample CurrentSampleLocked()
    {
        var route = _route!;
        if (UsingGpxTimelineLocked())
        {
            return route.SampleByTime(_pathTotalElapsedSeconds, _pathLoopMode, _pathTimeScale);
        }

        var speed = _pathPaused ? 0 : _pathCurrentSpeedMps;
        return route.SampleByDistance(_pathTotalTravelledMeters, _pathLoopMode, speed);
    }

    private double CurrentTimelineSecondsLocked()
    {
        if (_route is not { HasTimeline: true } route || route.DurationSeconds <= 0)
        {
            return 0;
        }

        return _pathLoopMode switch
        {
            PathLoopMode.Once => Math.Clamp(_pathTotalElapsedSeconds, 0, route.DurationSeconds),
            PathLoopMode.PingPong => ((int)Math.Floor(_pathTotalElapsedSeconds / route.DurationSeconds) % 2) == 1
                ? route.DurationSeconds - (_pathTotalElapsedSeconds % route.DurationSeconds)
                : _pathTotalElapsedSeconds % route.DurationSeconds,
            _ => _pathTotalElapsedSeconds % route.DurationSeconds,
        };
    }

    private void ReanchorToSampleLocked(RouteSample sample, PathLoopMode targetLoopMode)
    {
        if (_route is not { IsUsable: true } route)
        {
            _pathTotalTravelledMeters = 0;
            _pathTotalElapsedSeconds = 0;
            return;
        }

        var clampedDist = Math.Clamp(sample.DistanceMeters, 0, route.LengthMeters);
        if (targetLoopMode == PathLoopMode.PingPong && sample.Reversed)
        {
            _pathTotalTravelledMeters = route.LengthMeters + (route.LengthMeters - clampedDist);
        }
        else
        {
            _pathTotalTravelledMeters = clampedDist;
        }

        if (route.HasTimeline)
        {
            var t = route.TimeAtDistance(clampedDist);
            _pathTotalElapsedSeconds = targetLoopMode == PathLoopMode.PingPong && sample.Reversed
                ? route.DurationSeconds + (route.DurationSeconds - t)
                : t;
        }
        else
        {
            _pathTotalElapsedSeconds = 0;
        }
    }

    private GeoPoint ApplyPathDriftLocked(GeoPoint origin, double nowSeconds)
    {
        if (_jitterMeters <= 0)
        {
            return origin;
        }

        var dt = Math.Clamp(nowSeconds - _driftLastSeconds, 0, 2.0);
        _driftLastSeconds = nowSeconds;

        if (!_pathPaused)
        {
            if (nowSeconds >= _driftRetargetSeconds)
            {
                var angle = Random.Shared.NextDouble() * Math.PI * 2;
                var radius = Math.Sqrt(Random.Shared.NextDouble()) * _jitterMeters;
                _driftTargetNorthMeters = radius * Math.Cos(angle);
                _driftTargetEastMeters = radius * Math.Sin(angle);
                _driftRetargetSeconds = nowSeconds + 2.5 + Random.Shared.NextDouble() * 3.0;
            }

            var alpha = 1.0 - Math.Exp(-dt / 3.0);
            _driftNorthMeters += (_driftTargetNorthMeters - _driftNorthMeters) * alpha;
            _driftEastMeters += (_driftTargetEastMeters - _driftEastMeters) * alpha;
        }

        var distance = Math.Sqrt(_driftNorthMeters * _driftNorthMeters + _driftEastMeters * _driftEastMeters);
        if (distance <= 1e-4)
        {
            return origin;
        }

        var bearingRad = Math.Atan2(_driftEastMeters, _driftNorthMeters);
        return Geo.Normalize(Geo.Offset(origin, distance, bearingRad));
    }

    private void UpdatePathSensorsLocked(double nowSeconds)
    {
        var dt = Math.Clamp(nowSeconds - _sensorLastSeconds, 0, 2.0);
        _sensorLastSeconds = nowSeconds;

        if (_mode != TargetMode.Path || _pathPaused)
        {
            return;
        }

        if (!_jitterAccuracy && !_jitterAltitude)
        {
            return;
        }

        if (nowSeconds >= _sensorRetargetSeconds)
        {
            if (_jitterAccuracy)
            {
                var span = Math.Max(0, _accuracyMaxMeters - _accuracyMinMeters);
                _dynamicAccuracyTarget = _accuracyMinMeters + Random.Shared.NextDouble() * span;
            }

            if (_jitterAltitude)
            {
                var span = Math.Max(0, _altitudeMaxMeters - _altitudeMinMeters);
                _dynamicAltitudeTarget = _altitudeMinMeters + Random.Shared.NextDouble() * span;
            }

            _sensorRetargetSeconds = nowSeconds + 3.0 + Random.Shared.NextDouble() * 3.0;
        }

        var alpha = 1.0 - Math.Exp(-dt / 3.0);

        if (_jitterAccuracy)
        {
            _dynamicAccuracyCurrent += (_dynamicAccuracyTarget - _dynamicAccuracyCurrent) * alpha;
            _resolvedAccuracyMeters = Math.Round(Math.Clamp(_dynamicAccuracyCurrent, _accuracyMinMeters, _accuracyMaxMeters), 1);
        }

        if (_jitterAltitude)
        {
            _dynamicAltitudeCurrent += (_dynamicAltitudeTarget - _dynamicAltitudeCurrent) * alpha;
            _resolvedAltitudeMeters = Math.Round(Math.Clamp(_dynamicAltitudeCurrent, _altitudeMinMeters, _altitudeMaxMeters), 1);
        }
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
            var shifted = Geo.Normalize(Geo.Offset(new GeoPoint(_latitude, _longitude), distance, angle));
            _resolvedLatitude = shifted.Latitude;
            _resolvedLongitude = shifted.Longitude;
        }

        if (_jitterAccuracy)
        {
            var span = Math.Max(0, _accuracyMaxMeters - _accuracyMinMeters);
            _dynamicAccuracyCurrent = _accuracyMinMeters + Random.Shared.NextDouble() * span;
            _dynamicAccuracyTarget = _dynamicAccuracyCurrent;
            _resolvedAccuracyMeters = Math.Round(_dynamicAccuracyCurrent, 1);
        }

        if (_jitterAltitude)
        {
            var span = Math.Max(0, _altitudeMaxMeters - _altitudeMinMeters);
            _dynamicAltitudeCurrent = _altitudeMinMeters + Random.Shared.NextDouble() * span;
            _dynamicAltitudeTarget = _dynamicAltitudeCurrent;
            _resolvedAltitudeMeters = Math.Round(_dynamicAltitudeCurrent, 1);
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
    public PathLoopMode? PathLoopMode { get; init; }
    public bool? PathAutoClose { get; init; }
    public bool? PathPaused { get; init; }
    public bool? PathRestart { get; init; }
    public double? PathSeekProgress { get; init; }
    public double? PathSpeedJitterPercent { get; init; }
    public bool? PathUseGpxTime { get; init; }
    public double? PathTimeScale { get; init; }

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

internal sealed record RouteSnapshot(
    string Signature,
    string InputSignature,
    bool AutoClose,
    bool IsClosed,
    bool AutoClosed,
    double ClosingGapMeters,
    IReadOnlyList<RouteVertex> Vertices);

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
    int Satellites,
    PathLoopMode PathLoopMode,
    bool PathAutoClose,
    bool RouteClosed,
    bool RouteAutoClosed,
    double RouteClosingGapMeters,
    string RouteSignature,
    string RouteInputSignature,
    bool PathPaused,
    bool PathReachedEnd,
    int PathLap,
    bool PathReversed,
    double PathCurrentSpeedMps,
    double PathSpeedJitterPercent,
    bool PathUseGpxTime,
    double PathTimeScale,
    double PathElapsedSeconds,
    double CurrentLatitude,
    double CurrentLongitude,
    double? CurrentBearing,
    double? CurrentAltitude,
    double? CurrentAccuracy);
