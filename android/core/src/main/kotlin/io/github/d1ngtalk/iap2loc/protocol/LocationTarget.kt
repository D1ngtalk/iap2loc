package io.github.d1ngtalk.iap2loc.protocol

import io.github.d1ngtalk.iap2loc.monotonicMillis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val accuracyMeters: Double?,
    val quality: Int,
    val satellites: Int,
)

/** How the target moves. Adding a mode means adding a branch in [LocationTarget.next]. */
enum class TargetMode(val wire: String) {
    POINT("point"),
    PATH("path"),
}

/**
 * The live target shared by every running session. Changing it takes effect on the next tick, so
 * the map can be clicked without reconnecting anything.
 */
class LocationTarget(
    latitude: Double,
    longitude: Double,
    private val clock: () -> Long = ::monotonicMillis,
    private val random: Random = Random.Default,
) {
    companion object {
        /** Refuse absurd payloads; a GPX with more vertices than this is downsampled by the UI. */
        const val MAX_ROUTE_POINTS = 20_000
    }

    private val gate = Any()

    private var mode = TargetMode.POINT
    private var latitude: Double
    private var longitude: Double
    private var altitudeMeters: Double? = null
    private var accuracyMeters: Double? = null
    private var speedMetersPerSecond: Double? = null
    private var bearingDegrees: Double? = null
    private var jitterMeters = 0.0
    private var jitterAccuracy = false
    private var accuracyMinMeters = 3.0
    private var accuracyMaxMeters = 12.0
    private var quality = 1
    private var satellites = 8
    private var jitterAltitude = false
    private var altitudeMinMeters = 5.0
    private var altitudeMaxMeters = 20.0

    private var route: RoutePath? = null
    private var routeSignature = 0L
    private var pathSpeedMps = 1.4
    private var pathLoop = true
    private var pathAnchorTicks = clock()
    private var pathAnchorMeters = 0.0
    private var pathAnchorSeconds = 0.0

    // Rolled once per target change, then reused by every fix. A point that keeps re-rolling
    // every second looks like a broken GPS, not like a real one.
    private var resolvedLatitude: Double
    private var resolvedLongitude: Double
    private var resolvedAccuracyMeters: Double? = null
    private var resolvedAltitudeMeters: Double? = null

    init {
        val initial = Geo.normalize(GeoPoint(latitude, longitude))
        this.latitude = initial.latitude
        this.longitude = initial.longitude
        resolvedLatitude = initial.latitude
        resolvedLongitude = initial.longitude
        resolve()
    }

    fun apply(update: TargetUpdate) = synchronized(gate) {
        val oldLatitude = latitude
        val oldLongitude = longitude
        val oldAccuracy = accuracyMeters
        val oldAltitude = altitudeMeters
        val oldJitterMeters = jitterMeters
        val oldJitterAccuracy = jitterAccuracy
        val oldAccuracyMin = accuracyMinMeters
        val oldAccuracyMax = accuracyMaxMeters
        val oldJitterAltitude = jitterAltitude
        val oldAltitudeMin = altitudeMinMeters
        val oldAltitudeMax = altitudeMaxMeters

        var restartPath = false

        update.mode?.let {
            if (it != mode) {
                mode = it
                restartPath = true
            }
        }

        update.route?.let { vertices ->
            val next = if (vertices.isEmpty()) null else RoutePath(vertices.take(MAX_ROUTE_POINTS))
            val signature = next?.signature ?: 0L
            if (signature != routeSignature || (next?.count ?: 0) != (route?.count ?: 0)) {
                route = next
                routeSignature = signature
                restartPath = true
            }
        }

        update.pathLoop?.let {
            if (it != pathLoop) {
                rebasePath()
                pathLoop = it
            }
        }

        update.pathSpeedMps?.let { speed ->
            val next = speed.coerceIn(0.1, 50.0)
            if (abs(next - pathSpeedMps) > 1e-9) {
                rebasePath()
                pathSpeedMps = next
            }
        }

        update.latitude?.let { if (it.isFinite()) latitude = Geo.normalizeLatitude(it) }
        update.longitude?.let { if (it.isFinite()) longitude = Geo.normalizeLongitude(it) }
        update.altitude?.let { altitudeMeters = it }
        update.accuracy?.let { accuracyMeters = it }
        update.speed?.let { speedMetersPerSecond = it }
        update.bearing?.let { bearingDegrees = it }
        update.jitterMeters?.let { jitterMeters = max(0.0, it) }
        update.jitterAccuracy?.let { jitterAccuracy = it }
        update.accuracyMin?.let { accuracyMinMeters = max(0.1, it) }
        update.accuracyMax?.let { accuracyMaxMeters = max(accuracyMinMeters, it) }
        update.quality?.let { quality = it.coerceIn(0, 8) }
        update.satellites?.let { satellites = it.coerceIn(0, 99) }
        update.jitterAltitude?.let { jitterAltitude = it }
        update.altitudeMin?.let { altitudeMinMeters = it }
        update.altitudeMax?.let { altitudeMaxMeters = max(altitudeMinMeters, it) }

        if (restartPath) {
            pathAnchorMeters = 0.0
            pathAnchorSeconds = 0.0
            pathAnchorTicks = clock()
        }

        // The jitter is rolled once per real change. The UI re-sends every field on each
        // keystroke, so identical values must not re-roll behind the user's back.
        val positionChanged = latitude != oldLatitude || longitude != oldLongitude
        val jitterChanged = abs(jitterMeters - oldJitterMeters) > 1e-9 ||
            jitterAccuracy != oldJitterAccuracy ||
            abs(accuracyMinMeters - oldAccuracyMin) > 1e-9 ||
            abs(accuracyMaxMeters - oldAccuracyMax) > 1e-9 ||
            jitterAltitude != oldJitterAltitude ||
            abs(altitudeMinMeters - oldAltitudeMin) > 1e-9 ||
            abs(altitudeMaxMeters - oldAltitudeMax) > 1e-9

        if (positionChanged || jitterChanged || restartPath) {
            resolve()
        } else {
            if (!jitterAccuracy && accuracyMeters != oldAccuracy) resolvedAccuracyMeters = accuracyMeters
            if (!jitterAltitude && altitudeMeters != oldAltitude) resolvedAltitudeMeters = altitudeMeters
        }
    }

    fun snapshot(): TargetState = synchronized(gate) {
        TargetState(
            mode,
            latitude, longitude, altitudeMeters, accuracyMeters, speedMetersPerSecond, bearingDegrees,
            jitterMeters, jitterAccuracy, accuracyMinMeters, accuracyMaxMeters,
            jitterAltitude, altitudeMinMeters, altitudeMaxMeters,
            resolvedLatitude, resolvedLongitude, resolvedAccuracyMeters, resolvedAltitudeMeters,
            route?.count ?: 0, route?.lengthMeters ?: 0.0,
            if (route == null) 0.0 else currentSample().distanceMeters,
            pathSpeedMps, pathLoop,
            route?.durationSeconds ?: 0.0, route?.hasTimeline ?: false,
            quality, satellites,
        )
    }

    /** The fix for right now. Jitter was already rolled when the target was set. */
    fun next(): LocationFix = synchronized(gate) {
        val current = route
        if (mode == TargetMode.PATH && current != null && current.isUsable) {
            val sample = currentSample()
            return LocationFix(
                sample.point.latitude,
                sample.point.longitude,
                sample.point.altitude ?: resolvedAltitudeMeters,
                if (current.hasTimeline) sample.speedMetersPerSecond else pathSpeedMps,
                sample.bearing,
                resolvedAccuracyMeters,
                quality,
                satellites,
            )
        }

        LocationFix(
            resolvedLatitude, resolvedLongitude, resolvedAltitudeMeters,
            speedMetersPerSecond, bearingDegrees, resolvedAccuracyMeters, quality, satellites,
        )
    }

    /** Where the path is right now: GPX timing when available, constant speed otherwise. */
    private fun currentSample(): RouteSample {
        val current = route!!
        val elapsed = max(0L, clock() - pathAnchorTicks) / 1000.0

        if (current.hasTimeline) return current.atTime(pathAnchorSeconds + elapsed, pathLoop)

        val distance = pathAnchorMeters + pathSpeedMps * elapsed
        val length = current.lengthMeters
        if (length <= 0) return current.at(0.0)

        return current.at(if (pathLoop) distance % length else minOf(distance, length))
    }

    /** Keeps the current position when speed or looping changes mid-path. */
    private fun rebasePath() {
        val elapsed = max(0L, clock() - pathAnchorTicks) / 1000.0
        val current = route

        if (current != null && current.hasTimeline) {
            pathAnchorSeconds += elapsed
        } else if (current != null && current.isUsable) {
            pathAnchorMeters = currentSample().distanceMeters
        }

        pathAnchorTicks = clock()
    }

    /** One roll per target change: a fixed offset, accuracy and altitude for the whole stop. */
    private fun resolve() {
        resolvedLatitude = latitude
        resolvedLongitude = longitude
        resolvedAccuracyMeters = accuracyMeters
        resolvedAltitudeMeters = altitudeMeters

        if (jitterMeters > 0) {
            // Uniform inside a disc, so the offset is not biased towards the centre.
            val angle = random.nextDouble() * PI * 2
            val distance = sqrt(random.nextDouble()) * jitterMeters
            val shifted = Geo.normalize(Geo.offset(GeoPoint(latitude, longitude), distance, angle))
            resolvedLatitude = shifted.latitude
            resolvedLongitude = shifted.longitude
        }

        if (jitterAccuracy) {
            val span = max(0.0, accuracyMaxMeters - accuracyMinMeters)
            resolvedAccuracyMeters = round1(accuracyMinMeters + random.nextDouble() * span)
        }

        if (jitterAltitude) {
            val span = max(0.0, altitudeMaxMeters - altitudeMinMeters)
            resolvedAltitudeMeters = round1(altitudeMinMeters + random.nextDouble() * span)
        }
    }

    private fun round1(value: Double) = round(value * 10) / 10
}

/** Partial update coming from the UI; every field is optional. */
data class TargetUpdate(
    val mode: TargetMode? = null,
    /** null keeps the current route, an empty list clears it. */
    val route: List<RouteVertex>? = null,
    val pathSpeedMps: Double? = null,
    val pathLoop: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val jitterMeters: Double? = null,
    val jitterAccuracy: Boolean? = null,
    val accuracyMin: Double? = null,
    val accuracyMax: Double? = null,
    val quality: Int? = null,
    val satellites: Int? = null,
    val jitterAltitude: Boolean? = null,
    val altitudeMin: Double? = null,
    val altitudeMax: Double? = null,
)

data class TargetState(
    val mode: TargetMode,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val bearing: Double?,
    val jitterMeters: Double,
    val jitterAccuracy: Boolean,
    val accuracyMin: Double,
    val accuracyMax: Double,
    val jitterAltitude: Boolean,
    val altitudeMin: Double,
    val altitudeMax: Double,
    val resolvedLatitude: Double,
    val resolvedLongitude: Double,
    val resolvedAccuracy: Double?,
    val resolvedAltitude: Double?,
    val routePoints: Int,
    val routeLengthMeters: Double,
    val pathTravelledMeters: Double,
    val pathSpeedMps: Double,
    val pathLoop: Boolean,
    val pathDurationSeconds: Double,
    val pathTimed: Boolean,
    val quality: Int,
    val satellites: Int,
)
