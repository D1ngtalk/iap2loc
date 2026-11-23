package io.github.d1ngtalk.iap2loc.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocationTargetTest {
    private var now = 0L
    private val target = LocationTarget(31.2304, 121.4737, clock = { now }, random = Random(7))

    private val route = listOf(
        RouteVertex(GeoPoint(31.0, 121.0)),
        RouteVertex(GeoPoint(31.0, 121.01)),
    )

    @Test
    fun pathAdvancesAtConstantSpeedAndLoops() {
        target.apply(TargetUpdate(mode = TargetMode.PATH, route = route, pathSpeedMps = 10.0, pathLoop = true))
        val length = target.snapshot().routeLengthMeters
        assertTrue(length in 950.0..960.0, "length $length")

        now = 50_000
        assertEquals(500.0, target.snapshot().pathTravelledMeters, 1e-6)
        val fix = target.next()
        assertEquals(10.0, fix.speedMetersPerSecond)
        assertEquals(90.0, fix.bearingDegrees!!, 0.1)

        now = 150_000
        assertEquals(1500.0 % length, target.snapshot().pathTravelledMeters, 1e-6)
    }

    @Test
    fun speedChangeKeepsTheCurrentPosition() {
        target.apply(TargetUpdate(mode = TargetMode.PATH, route = route, pathSpeedMps = 10.0, pathLoop = false))
        now = 30_000
        target.apply(TargetUpdate(pathSpeedMps = 1.0))
        assertEquals(300.0, target.snapshot().pathTravelledMeters, 1e-6)
        now = 40_000
        assertEquals(310.0, target.snapshot().pathTravelledMeters, 1e-6)
        now = 10_000_000
        assertEquals(target.snapshot().routeLengthMeters, target.snapshot().pathTravelledMeters, 1e-6)
    }

    @Test
    fun jitterIsRolledOncePerChange() {
        target.apply(TargetUpdate(jitterMeters = 50.0, jitterAccuracy = true, accuracyMin = 3.0, accuracyMax = 12.0))
        val first = target.snapshot()
        assertNotEquals(first.latitude, first.resolvedLatitude)
        assertTrue(first.resolvedAccuracy!! in 3.0..12.0)
        repeat(5) { assertEquals(first.resolvedLatitude, target.next().latitude) }

        // The UI re-sends identical values on every keystroke: no re-roll.
        target.apply(TargetUpdate(jitterMeters = 50.0, latitude = 31.2304, longitude = 121.4737))
        assertEquals(first.resolvedLatitude, target.snapshot().resolvedLatitude)

        target.apply(TargetUpdate(latitude = 40.0))
        assertNotEquals(first.resolvedLatitude, target.snapshot().resolvedLatitude)
    }

    @Test
    fun valuesAreClampedAndWrapped() {
        target.apply(TargetUpdate(latitude = 95.0, longitude = 190.0, quality = 12, satellites = 150))
        val state = target.snapshot()
        assertEquals(90.0, state.latitude)
        assertEquals(-170.0, state.longitude, 1e-9)
        assertEquals(8, state.quality)
        assertEquals(99, state.satellites)
    }
}
