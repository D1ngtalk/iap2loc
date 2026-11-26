package io.github.d1ngtalk.iap2loc.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-for-byte and number-for-number parity with the desktop C# implementation. The reference
 * file comes from android/tools/golden-vectors (`dotnet run -- vectors`), run against the C# sources in Protocol/.
 * CI regenerates it from the current C# code and points IAP2LOC_GOLDEN_VECTORS at the fresh copy.
 */
class GoldenVectorsTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        System.getenv("IAP2LOC_GOLDEN_VECTORS")?.let { java.io.File(it).readText() }
            ?: javaClass.getResource("/golden/csharp-vectors.json")!!.readText(),
    ).jsonObject

    private val identity = AccessoryIdentity("iAP2 Loc 7F3A", "Loc1,1", "carplay-loc", "LC00A1B2C3D4E5", "0.1.0", "1.0")

    private fun hex(key: String) = vectors.getValue(key).jsonPrimitive.content

    private fun JsonObject.num(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

    private fun close(expected: Double?, actual: Double?, what: String) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, what)
            return
        }

        assertTrue(abs(expected - actual) <= 1e-9 * maxOf(1.0, abs(expected)), "$what: expected $expected, got $actual")
    }

    @Test
    fun csmEncodingMatches() {
        val encoded = Csm.encode(0x1D01, Csm.param(0, byteArrayOf(1, 2, 3)), Csm.param(5, ByteArray(0)))
        assertEquals(hex("csm"), encoded.toHex())
    }

    @Test
    fun identificationMatches() {
        val adapter = byteArrayOf(0x3C, 0x22, 0xFB.toByte(), 0x01, 0x02, 0x03)
        assertEquals(hex("identificationWithAdapter"), Identification.build(identity, adapter).toHex())
        assertEquals(hex("identificationWithoutAdapter"), Identification.build(identity, null).toHex())
    }

    @Test
    fun nmeaMatches() {
        for (case in vectors.getValue("nmea").jsonArray.map { it.jsonObject }) {
            val text = Nmea.encode(
                case.num("lat")!!, case.num("lon")!!, case.num("alt"), case.num("bearing"), case.num("speed"),
                case.num("accuracy"), case.getValue("quality").jsonPrimitive.int, case.getValue("satellites").jsonPrimitive.int,
                ZonedDateTime.parse(case.getValue("utc").jsonPrimitive.content),
            )
            assertEquals(case.getValue("text").jsonPrimitive.content, text, "NMEA for $case")
        }
    }

    @Test
    fun geoMatches() {
        for (case in vectors.getValue("geo").jsonArray.map { it.jsonObject }) {
            val a = case.getValue("a").jsonArray.map { it.jsonPrimitive.double }
            val b = case.getValue("b").jsonArray.map { it.jsonPrimitive.double }
            val from = GeoPoint(a[0], a[1])
            val to = GeoPoint(b[0], b[1])
            close(case.num("distance"), Geo.distance(from, to), "distance $a → $b")
            close(case.num("bearing"), Geo.bearing(from, to), "bearing $a → $b")
        }

        for (case in vectors.getValue("offset").jsonArray.map { it.jsonObject }) {
            val moved = Geo.offset(GeoPoint(31.2304, 121.4737), case.num("distance")!!, case.num("angle")!!)
            close(case.num("lat"), moved.latitude, "offset latitude")
            close(case.num("lon"), moved.longitude, "offset longitude")
        }

        for (case in vectors.getValue("longitudes").jsonArray.map { it.jsonObject }) {
            close(case.num("output"), Geo.normalizeLongitude(case.num("input")!!), "normalize ${case.num("input")}")
        }
    }

    @Test
    fun routeSamplingMatches() {
        val route = RoutePath(
            listOf(
                RouteVertex(GeoPoint(31.2304, 121.4737, 5.0), 1000.0),
                RouteVertex(GeoPoint(31.2314, 121.4747, 9.0), 1060.0),
                RouteVertex(GeoPoint(31.2334, 121.4747, null), 1200.0),
                RouteVertex(GeoPoint(31.2334, 121.4797, 20.0), 1500.0),
            ),
        )
        val expected = vectors.getValue("route").jsonObject
        close(expected.num("length"), route.lengthMeters, "route length")
        close(expected.num("duration"), route.durationSeconds, "route duration")

        fun check(name: String, sample: (Double) -> RouteSample) {
            for (case in (expected.getValue(name) as JsonArray).map { it.jsonObject }) {
                val input = case.num("input")!!
                val actual = sample(input)
                close(case.num("lat"), actual.point.latitude, "$name($input) latitude")
                close(case.num("lon"), actual.point.longitude, "$name($input) longitude")
                close(case.num("alt"), actual.point.altitude, "$name($input) altitude")
                close(case.num("bearing"), actual.bearing, "$name($input) bearing")
                close(case.num("speed"), actual.speedMetersPerSecond, "$name($input) speed")
                close(case.num("distance"), actual.distanceMeters, "$name($input) distance")
            }
        }

        check("at") { route.at(it) }
        check("atTime") { route.atTime(it, loop = true) }
        check("atTimeNoLoop") { route.atTime(it, loop = false) }
    }
}
