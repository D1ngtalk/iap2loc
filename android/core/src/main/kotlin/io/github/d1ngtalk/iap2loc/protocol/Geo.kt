package io.github.d1ngtalk.iap2loc.protocol

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 point. Altitude is optional because most GPX waypoints carry none. */
data class GeoPoint(val latitude: Double, val longitude: Double, val altitude: Double? = null)

object Geo {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val METRES_PER_DEGREE = 111_320.0

    /** Great-circle distance in metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude * PI / 180
        val lat2 = b.latitude * PI / 180
        val deltaLat = (b.latitude - a.latitude) * PI / 180
        val deltaLon = (b.longitude - a.longitude) * PI / 180

        val sinLat = sin(deltaLat / 2)
        val sinLon = sin(deltaLon / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing in degrees, 0 = north, clockwise. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude * PI / 180
        val lat2 = b.latitude * PI / 180
        val deltaLon = (b.longitude - a.longitude) * PI / 180

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        val degrees = atan2(y, x) * 180 / PI
        return (degrees + 360) % 360
    }

    /** Moves a point by a distance and an angle, good enough for the tens of metres jitter uses. */
    fun offset(origin: GeoPoint, distance: Double, angle: Double): GeoPoint {
        val deltaLatitude = distance * cos(angle) / METRES_PER_DEGREE
        val deltaLongitude = distance * sin(angle) /
            (METRES_PER_DEGREE * max(0.01, cos(origin.latitude * PI / 180)))
        return GeoPoint(origin.latitude + deltaLatitude, origin.longitude + deltaLongitude, origin.altitude)
    }

    /** Clamps latitude to the poles; a non-finite value falls back to the equator. */
    fun normalizeLatitude(latitude: Double): Double = if (latitude.isFinite()) latitude.coerceIn(-90.0, 90.0) else 0.0

    /**
     * Wraps longitude into -180..180. Map clicks on a wrapped copy of the world land outside that
     * range, and NMEA rejects anything beyond it.
     */
    fun normalizeLongitude(longitude: Double): Double {
        if (!longitude.isFinite()) return 0.0

        var wrapped = longitude % 360
        if (wrapped > 180) wrapped -= 360
        if (wrapped < -180) wrapped += 360
        return wrapped
    }

    fun normalize(point: GeoPoint): GeoPoint =
        GeoPoint(normalizeLatitude(point.latitude), normalizeLongitude(point.longitude), point.altitude)
}
