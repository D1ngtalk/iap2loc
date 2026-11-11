using System.Globalization;
using System.Text;

namespace Iap2Loc.Protocol;

/// <summary>Which NMEA sentences go into the 0xFFFB payload.</summary>
[Flags]
internal enum NmeaSentences
{
    None = 0,
    Gga = 1,
    Rmc = 2,
    Gsa = 4,
    Gsv = 8,
    Default = Gga | Rmc,
}

/// <summary>
/// Encodes a fix as the NMEA stream the iPhone expects inside a 0xFFFB LocationInformation
/// message. GGA carries the fix itself, RMC the recommended minimum, GSA the 2D/3D mode and DOP,
/// GSV the satellites in view. Field layout follows the proven DiPlay/LIVI encoder.
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
        DateTime utc,
        NmeaSentences sentences = NmeaSentences.Default)
    {
        if (!double.IsFinite(latitude) || latitude is < -90 or > 90)
        {
            throw new ArgumentOutOfRangeException(nameof(latitude), "latitude must be within -90..90");
        }

        if (!double.IsFinite(longitude) || longitude is < -180 or > 180)
        {
            throw new ArgumentOutOfRangeException(nameof(longitude), "longitude must be within -180..180");
        }

        if ((sentences & (NmeaSentences.Gga | NmeaSentences.Rmc)) == 0)
        {
            sentences |= NmeaSentences.Default;
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

        if (sentences.HasFlag(NmeaSentences.Gga))
        {
            var gga = "GPGGA," + time + "," + latitudeText + "," + latitudeHemisphere + "," +
                      longitudeText + "," + longitudeHemisphere + ",1,08," +
                      hdop.ToString("0.0", CultureInfo.InvariantCulture) + "," + altitude + ",M,0.0,M,,";
            Append(payload, gga);
        }

        if (sentences.HasFlag(NmeaSentences.Gsa))
        {
            // mode A (auto 2D/3D), fix type 3 (3D), eight satellites in the solution, then PDOP/HDOP/VDOP.
            var gsa = "GPGSA,A,3,01,02,03,04,05,06,07,08,,,,," +
                      (hdop * 1.6).ToString("0.0", CultureInfo.InvariantCulture) + "," +
                      hdop.ToString("0.0", CultureInfo.InvariantCulture) + "," +
                      (hdop * 1.2).ToString("0.0", CultureInfo.InvariantCulture);
            Append(payload, gsa);
        }

        if (sentences.HasFlag(NmeaSentences.Gsv))
        {
            // Two sentences for eight satellites in view, plausible elevations/azimuths/SNRs.
            Append(payload, "GPGSV,2,1,08,01,40,083,46,02,17,308,41,03,45,120,45,04,30,200,44");
            Append(payload, "GPGSV,2,2,08,05,25,150,43,06,60,300,42,07,15,090,40,08,70,240,41");
        }

        if (sentences.HasFlag(NmeaSentences.Rmc))
        {
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
        }

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

    public static NmeaSentences ParseSentences(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return NmeaSentences.Default;
        }

        var result = NmeaSentences.None;
        foreach (var token in text.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            result |= token.ToLowerInvariant() switch
            {
                "gga" => NmeaSentences.Gga,
                "rmc" => NmeaSentences.Rmc,
                "gsa" => NmeaSentences.Gsa,
                "gsv" => NmeaSentences.Gsv,
                _ => throw new ArgumentException($"unknown NMEA sentence '{token}', use gga,rmc,gsa,gsv"),
            };
        }

        return result == NmeaSentences.None ? NmeaSentences.Default : result;
    }
}
