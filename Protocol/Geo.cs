namespace Iap2Loc.Protocol;

/// <summary>A WGS-84 point. Altitude is optional because most GPX waypoints carry none.</summary>
internal readonly record struct GeoPoint(double Latitude, double Longitude, double? Altitude = null);

internal static class Geo
{
    private const double EarthRadiusMeters = 6_371_000.0;
    private const double MetresPerDegree = 111_320.0;

    /// <summary>Great-circle distance in metres.</summary>
    public static double Distance(GeoPoint a, GeoPoint b)
    {
        var lat1 = a.Latitude * Math.PI / 180;
        var lat2 = b.Latitude * Math.PI / 180;
        var deltaLat = (b.Latitude - a.Latitude) * Math.PI / 180;
        var deltaLon = (b.Longitude - a.Longitude) * Math.PI / 180;

        var sinLat = Math.Sin(deltaLat / 2);
        var sinLon = Math.Sin(deltaLon / 2);
        var h = sinLat * sinLat + Math.Cos(lat1) * Math.Cos(lat2) * sinLon * sinLon;
        return 2 * EarthRadiusMeters * Math.Asin(Math.Min(1, Math.Sqrt(h)));
    }

    /// <summary>Initial bearing in degrees, 0 = north, clockwise.</summary>
    public static double Bearing(GeoPoint a, GeoPoint b)
    {
        var lat1 = a.Latitude * Math.PI / 180;
        var lat2 = b.Latitude * Math.PI / 180;
        var deltaLon = (b.Longitude - a.Longitude) * Math.PI / 180;

        var y = Math.Sin(deltaLon) * Math.Cos(lat2);
        var x = Math.Cos(lat1) * Math.Sin(lat2) - Math.Sin(lat1) * Math.Cos(lat2) * Math.Cos(deltaLon);
        var degrees = Math.Atan2(y, x) * 180 / Math.PI;
        return (degrees + 360) % 360;
    }

    /// <summary>Moves a point by a distance and an angle, good enough for the tens of metres jitter uses.</summary>
    public static GeoPoint Offset(GeoPoint origin, double distance, double angle)
    {
        var deltaLatitude = distance * Math.Cos(angle) / MetresPerDegree;
        var deltaLongitude = distance * Math.Sin(angle) /
                             (MetresPerDegree * Math.Max(0.01, Math.Cos(origin.Latitude * Math.PI / 180)));
        return new GeoPoint(origin.Latitude + deltaLatitude, origin.Longitude + deltaLongitude, origin.Altitude);
    }
}
