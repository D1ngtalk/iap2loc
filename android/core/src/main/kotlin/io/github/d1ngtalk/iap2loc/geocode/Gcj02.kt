package io.github.d1ngtalk.iap2loc.geocode

import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02, the shifted datum Chinese map services publish (AMap, Tencent, Baidu with gcj02ll, Google
 * inside China). The same formulas and China box as wgs2gcj / gcj2wgs in Web/index.html, so a searched
 * place lands exactly under the console's AMap marker.
 */
object Gcj02 {
    private const val AXIS = 6378245.0
    private const val EE = 0.00669342162296594323

    fun outOfChina(latitude: Double, longitude: Double): Boolean =
        !(longitude > 73.66 && longitude < 135.05 && latitude > 3.86 && latitude < 53.55)

    fun fromWgs(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) return point
        var deltaLatitude = transformLatitude(point.longitude - 105, point.latitude - 35)
        var deltaLongitude = transformLongitude(point.longitude - 105, point.latitude - 35)
        val radians = point.latitude / 180 * PI
        var magic = sin(radians)
        magic = 1 - EE * magic * magic
        val root = sqrt(magic)
        deltaLatitude = (deltaLatitude * 180) / ((AXIS * (1 - EE)) / (magic * root) * PI)
        deltaLongitude = (deltaLongitude * 180) / (AXIS / root * cos(radians) * PI)
        return GeoPoint(point.latitude + deltaLatitude, point.longitude + deltaLongitude, point.altitude)
    }

    /** There is no closed form: eight fixed-point steps, like the page, land well under a centimetre. */
    fun toWgs(point: GeoPoint): GeoPoint {
        var latitude = point.latitude
        var longitude = point.longitude
        repeat(8) {
            val shifted = fromWgs(GeoPoint(latitude, longitude))
            latitude += point.latitude - shifted.latitude
            longitude += point.longitude - shifted.longitude
        }
        return GeoPoint(latitude, longitude, point.altitude)
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        result += (20 * sin(y * PI) + 40 * sin(y / 3 * PI)) * 2 / 3
        result += (160 * sin(y / 12 * PI) + 320 * sin(y * PI / 30)) * 2 / 3
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        result += (20 * sin(x * PI) + 40 * sin(x / 3 * PI)) * 2 / 3
        result += (150 * sin(x / 12 * PI) + 300 * sin(x / 30 * PI)) * 2 / 3
        return result
    }
}
