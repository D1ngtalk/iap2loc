using System.Globalization;
using System.Text;

namespace Iap2Loc.Protocol;

/// <summary>
/// Encodes a fix as the NMEA stream the iPhone expects inside a 0xFFFB LocationInformation
/// message. Only GGA and RMC are ever sent: 0xFFFA can only subscribe to those two, and iOS
/// never asks for GSA or GSV.
/// </summary>
internal static class Nmea
{
    private const double KnotsPerMeterPerSecond = 1.94384449;

    public static string Encode(
        double latitude,
        double longitude,
        double? altitudeMeters,
        double? bearingDegrees,
        double? speedMetersPerSecond,
        double? accuracyMeters,
        int quality,
        int satellites,
        DateTime utc)
    {
        if (!double.IsFinite(latitude) || latitude is < -90 or > 90)
        {
            throw new ArgumentOutOfRangeException(nameof(latitude), "latitude must be within -90..90");
        }

        if (!double.IsFinite(longitude) || longitude is < -180 or > 180)
        {
            throw new ArgumentOutOfRangeException(nameof(longitude), "longitude must be within -180..180");
        }

        var time = utc.ToString("HHmmss", CultureInfo.InvariantCulture) + ".00";
        var date = utc.ToString("ddMMyy", CultureInfo.InvariantCulture);
        var (latitudeText, latitudeHemisphere) = Coordinate(latitude, latitude: true);
        var (longitudeText, longitudeHemisphere) = Coordinate(longitude, latitude: false);

        var hdop = accuracyMeters is { } accuracy && double.IsFinite(accuracy) && accuracy > 0
            ? Math.Clamp(accuracy / 5.0, 0.5, 50.0)
            : 1.0;
        var altitude = altitudeMeters is { } alt && double.IsFinite(alt)
            ? alt.ToString("0.0", CultureInfo.InvariantCulture)
            : "0.0";

        var payload = new StringBuilder();

        // Field 6 is the fix quality (0..8) and field 7 the satellites used (0..99).
        var gga = "GPGGA," + time + "," + latitudeText + "," + latitudeHemisphere + "," +
                  longitudeText + "," + longitudeHemisphere + "," +
                  Math.Clamp(quality, 0, 8).ToString(CultureInfo.InvariantCulture) + "," +
                  Math.Clamp(satellites, 0, 99).ToString("00", CultureInfo.InvariantCulture) + "," +
                  hdop.ToString("0.0", CultureInfo.InvariantCulture) + "," + altitude + ",M,0.0,M,,";
        Append(payload, gga);

        var speed = speedMetersPerSecond is { } metersPerSecond && double.IsFinite(metersPerSecond) && metersPerSecond >= 0
            ? (metersPerSecond * KnotsPerMeterPerSecond).ToString("0.00", CultureInfo.InvariantCulture)
            : "0.00";

        // An unknown course stays empty: 0.00 would claim the vehicle heads north.
        var course = bearingDegrees is { } bearing && double.IsFinite(bearing)
            ? bearing.ToString("0.00", CultureInfo.InvariantCulture)
            : string.Empty;

        var rmc = "GPRMC," + time + ",A," + latitudeText + "," + latitudeHemisphere + "," +
                  longitudeText + "," + longitudeHemisphere + "," + speed + "," + course + "," + date + ",,";
        Append(payload, rmc);

        return payload.ToString();
    }

    private static void Append(StringBuilder payload, string body)
    {
        payload.Append('$').Append(body).Append('*').Append(Checksum(body)).Append("\r\n");
    }

    private static (string Value, string Hemisphere) Coordinate(double value, bool latitude)
    {
        var absolute = Math.Abs(value);
        var degrees = (int)absolute;
        var minutes = (absolute - degrees) * 60.0;
        if (minutes >= 60.0 - 0.00005)
        {
            degrees += 1;
            minutes = 0.0;
        }

        var degreeText = degrees.ToString(latitude ? "D2" : "D3", CultureInfo.InvariantCulture);
        var minuteText = minutes.ToString("00.0000", CultureInfo.InvariantCulture);
        var hemisphere = latitude
            ? value >= 0 ? "N" : "S"
            : value >= 0 ? "E" : "W";

        return (degreeText + minuteText, hemisphere);
    }

    private static string Checksum(string body)
    {
        var value = 0;
        foreach (var character in body)
        {
            value ^= character;
        }

        return value.ToString("X2", CultureInfo.InvariantCulture);
    }
}
