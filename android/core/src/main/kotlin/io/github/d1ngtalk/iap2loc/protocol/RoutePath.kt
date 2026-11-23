package io.github.d1ngtalk.iap2loc.protocol

import kotlin.math.round

/** A route vertex plus the GPX timestamp it was recorded at, in seconds. */
data class RouteVertex(val point: GeoPoint, val timeSeconds: Double? = null)

data class RouteSample(
    val point: GeoPoint,
    val bearing: Double,
    val speedMetersPerSecond: Double,
    val distanceMeters: Double,
)

/**
 * A polyline followed either at a constant ground speed or along the timestamps of an imported GPX.
 * Positions are a pure function of time, so several sessions sharing one target stay in sync no
 * matter when each of them asks for the next fix.
 */
class RoutePath(vertices: List<RouteVertex>) {
    private val points: Array<GeoPoint> = vertices.map { it.point }.toTypedArray()
    private val cumulative = DoubleArray(points.size)
    private val times: DoubleArray?
    private val duration: Double

    val count: Int get() = points.size

    val lengthMeters: Double

    /** True when every vertex carries a usable timestamp, so playback follows the GPX timing. */
    val hasTimeline: Boolean get() = times != null

    val durationSeconds: Double get() = duration

    /** True when the polyline can actually be followed; a single point cannot. */
    val isUsable: Boolean get() = points.size >= 2 && lengthMeters > 0.5

    /** Cheap identity used to tell "the same path again" from "a new path". */
    val signature: Long

    init {
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + Geo.distance(points[index - 1], points[index])
        }

        lengthMeters = if (cumulative.isEmpty()) 0.0 else cumulative.last()
        val timeline = buildTimeline(vertices)
        times = timeline?.first
        duration = timeline?.second ?: 0.0
        signature = computeSignature(vertices)
    }

    /** Samples the polyline at a distance from the start, clamped to the ends. */
    fun at(travelledMeters: Double): RouteSample {
        if (points.isEmpty()) return RouteSample(GeoPoint(0.0, 0.0), 0.0, 0.0, 0.0)
        if (points.size == 1 || lengthMeters <= 0) return RouteSample(points.last(), 0.0, 0.0, 0.0)

        val distance = travelledMeters.coerceIn(0.0, lengthMeters)
        val index = floorIndex(cumulative, distance)

        if (index >= points.size - 1) {
            val last = points.last()
            return RouteSample(last, Geo.bearing(points[points.size - 2], last), 0.0, lengthMeters)
        }

        val start = points[index]
        val end = points[index + 1]
        val span = cumulative[index + 1] - cumulative[index]
        val ratio = if (span <= 0) 0.0 else (distance - cumulative[index]) / span

        val latitude = start.latitude + (end.latitude - start.latitude) * ratio
        val longitude = start.longitude + (end.longitude - start.longitude) * ratio
        val startAltitude = start.altitude
        val endAltitude = end.altitude
        val altitude = if (startAltitude != null && endAltitude != null) {
            startAltitude + (endAltitude - startAltitude) * ratio
        } else {
            startAltitude ?: endAltitude
        }

        return RouteSample(GeoPoint(latitude, longitude, altitude), Geo.bearing(start, end), 0.0, distance)
    }

    /**
     * Samples the polyline at an elapsed time using the GPX timestamps, so fast and slow sections keep
     * their own pace. Wraps when looping, stops at the end otherwise.
     */
    fun atTime(elapsedSeconds: Double, loop: Boolean): RouteSample {
        val timeline = times
        if (timeline == null || !isUsable) return at(0.0)

        val seconds = if (loop) {
            ((elapsedSeconds % duration) + duration) % duration
        } else {
            elapsedSeconds.coerceIn(0.0, duration)
        }

        val index = floorIndex(timeline, seconds)

        if (index >= points.size - 1) {
            val last = points.last()
            return RouteSample(last, Geo.bearing(points[points.size - 2], last), speedAt(points.size - 2), lengthMeters)
        }

        val span = timeline[index + 1] - timeline[index]
        val ratio = if (span <= 0) 0.0 else (seconds - timeline[index]) / span
        val distance = cumulative[index] + (cumulative[index + 1] - cumulative[index]) * ratio

        return at(distance).copy(speedMetersPerSecond = speedAt(index))
    }

    private fun speedAt(index: Int): Double {
        val timeline = times
        if (timeline != null && index >= 0 && index < points.size - 1) {
            val span = timeline[index + 1] - timeline[index]
            if (span > 0) return (cumulative[index + 1] - cumulative[index]) / span
        }

        return if (duration > 0) lengthMeters / duration else 0.0
    }

    /** Array.BinarySearch semantics: the exact index, or the element just below the insertion point. */
    private fun floorIndex(values: DoubleArray, key: Double): Int {
        var index = values.binarySearch(key)
        if (index < 0) index = index.inv() - 1
        return index.coerceIn(0, points.size - 1)
    }

    private companion object {
        fun buildTimeline(vertices: List<RouteVertex>): Pair<DoubleArray, Double>? {
            val start = vertices.firstOrNull()?.timeSeconds
            if (vertices.size < 2 || start == null) return null

            val times = DoubleArray(vertices.size)
            var previous = 0.0
            for ((index, vertex) in vertices.withIndex()) {
                val time = vertex.timeSeconds ?: return null
                val offset = time - start
                if (offset.isNaN() || offset < previous) return null
                times[index] = offset
                previous = offset
            }

            val duration = times.last()
            return if (duration > 0.5) times to duration else null
        }

        fun computeSignature(vertices: List<RouteVertex>): Long {
            // FNV-1a over the rounded coordinates and timestamps: stable across identical payloads,
            // different for edits.
            var hash = -0x340d631b7bdddcdbL // 14695981039346656037
            hash = mix(hash, vertices.size.toLong())

            for (vertex in vertices) {
                hash = mix(hash, rounded(vertex.point.latitude, 1e7))
                hash = mix(hash, rounded(vertex.point.longitude, 1e7))
                hash = mix(hash, vertex.point.altitude?.let { rounded(it, 10.0) } ?: -1L)
                hash = mix(hash, vertex.timeSeconds?.let { rounded(it, 10.0) } ?: -1L)
            }

            return hash
        }

        fun rounded(value: Double, scale: Double): Long = java.lang.Double.doubleToLongBits(round(value * scale) / scale)

        fun mix(start: Long, value: Long): Long {
            var hash = start
            for (byteIndex in 0 until 8) {
                hash = hash xor ((value ushr (byteIndex * 8)) and 0xFF)
                hash *= 1099511628211L
            }

            return hash
        }
    }
}
