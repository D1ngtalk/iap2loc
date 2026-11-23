package io.github.d1ngtalk.iap2loc.protocol

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Encodes a fix as the NMEA stream the iPhone expects inside a 0xFFFB LocationInformation
 * message. Only GGA and RMC are ever sent: 0xFFFA can only subscribe to those two, and iOS
 * never asks for GSA or GSV.
 */
object Nmea {
    private const val KNOTS_PER_METER_PER_SECOND = 1.94384449
    private val timeFormat = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT)
    private val dateFormat = DateTimeFormatter.ofPattern("ddMMyy", Locale.ROOT)

    fun encode(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        bearingDegrees: Double?,
        speedMetersPerSecond: Double?,
        accuracyMeters: Double?,
        quality: Int,
        satellites: Int,
        utc: ZonedDateTime,
    ): String {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude must be within -90..90" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude must be within -180..180" }

        val instant = utc.withZoneSameInstant(ZoneOffset.UTC)
        val time = instant.format(timeFormat) + ".00"
        val date = instant.format(dateFormat)
        val (latitudeText, latitudeHemisphere) = coordinate(latitude, latitude = true)
        val (longitudeText, longitudeHemisphere) = coordinate(longitude, latitude = false)

        val hdop = if (accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters > 0) {
            (accuracyMeters / 5.0).coerceIn(0.5, 50.0)
        } else {
            1.0
        }
        val altitude = if (altitudeMeters != null && altitudeMeters.isFinite()) fixed(altitudeMeters, 1) else "0.0"

        val payload = StringBuilder()

        // Field 6 is the fix quality (0..8) and field 7 the satellites used (0..99).
        val gga = "GPGGA," + time + "," + latitudeText + "," + latitudeHemisphere + "," +
            longitudeText + "," + longitudeHemisphere + "," +
            quality.coerceIn(0, 8) + "," +
            "%02d".format(Locale.ROOT, satellites.coerceIn(0, 99)) + "," +
            fixed(hdop, 1) + "," + altitude + ",M,0.0,M,,"
        append(payload, gga)

        val speed = if (speedMetersPerSecond != null && speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0) {
            fixed(speedMetersPerSecond * KNOTS_PER_METER_PER_SECOND, 2)
        } else {
            "0.00"
        }

        // An unknown course stays empty: 0.00 would claim the vehicle heads north.
        val course = if (bearingDegrees != null && bearingDegrees.isFinite()) fixed(bearingDegrees, 2) else ""

        val rmc = "GPRMC," + time + ",A," + latitudeText + "," + latitudeHemisphere + "," +
            longitudeText + "," + longitudeHemisphere + "," + speed + "," + course + "," + date + ",,"
        append(payload, rmc)

        return payload.toString()
    }

    private fun append(payload: StringBuilder, body: String) {
        payload.append('$').append(body).append('*').append(checksum(body)).append("\r\n")
    }

    private fun coordinate(value: Double, latitude: Boolean): Pair<String, String> {
        val absolute = abs(value)
        var degrees = absolute.toInt()
        var minutes = (absolute - degrees) * 60.0
        if (minutes >= 60.0 - 0.00005) {
            degrees += 1
            minutes = 0.0
        }

        val degreeText = (if (latitude) "%02d" else "%03d").format(Locale.ROOT, degrees)
        val minuteText = "%07.4f".format(Locale.ROOT, minutes)
        val hemisphere = if (latitude) {
            if (value >= 0) "N" else "S"
        } else {
            if (value >= 0) "E" else "W"
        }

        return (degreeText + minuteText) to hemisphere
    }

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return "%02X".format(Locale.ROOT, value)
    }

    internal fun fixed(value: Double, decimals: Int): String = "%.${decimals}f".format(Locale.ROOT, value)
}
