package io.github.d1ngtalk.iap2loc.web

import io.github.d1ngtalk.iap2loc.SimulatedPhone
import io.github.d1ngtalk.iap2loc.engine.Engine
import io.github.d1ngtalk.iap2loc.newP256
import io.github.d1ngtalk.iap2loc.protocol.IdentityProvider
import io.github.d1ngtalk.iap2loc.protocol.IdentitySource
import io.github.d1ngtalk.iap2loc.protocol.MfiSigner
import io.github.d1ngtalk.iap2loc.protocol.ResolvedIdentity
import io.github.d1ngtalk.iap2loc.transport.TcpTransport
import io.github.d1ngtalk.iap2loc.waitUntil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JSON API Web/index.html talks to, exercised over real HTTP. */
class WebServerTest {
    private val directory = Files.createTempDirectory("iap2loc-web").toFile()
    private val engine = Engine(directory.resolve("iap2loc.json"), port = 0)
    private val base = "http://127.0.0.1:${engine.server.port}"

    @AfterTest
    fun tearDown() {
        engine.close()
        IdentitySource.provider = null
    }

    private fun request(path: String, body: String? = null): Pair<Int, String> {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }

        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream).use { it.readBytes().decodeToString() }
        return status to text
    }

    private fun get(path: String): JsonElement = Json.parseToJsonElement(request(path).second)

    private fun post(path: String, body: String): JsonObject = Json.parseToJsonElement(request(path, body).second).jsonObject

    @Test
    fun servesTheSharedConsolePage() {
        val (status, page) = request("/")
        assertEquals(200, status)
        assertTrue(page.contains("<html") && page.contains("/api/state"), "Web/index.html is embedded")
        assertEquals(404, request("/nope").first)
    }

    @Test
    fun stateTargetAndFavorites() {
        val state = get("/api/state").jsonObject
        val target = state.getValue("target").jsonObject
        assertEquals("point", target.getValue("mode").jsonPrimitive.content)
        assertEquals(31.2304, target.getValue("latitude").jsonPrimitive.double)
        assertEquals(0, state.getValue("sessions").jsonArray.size)
        assertTrue(state.getValue("identity").jsonObject.getValue("serial").jsonPrimitive.content.startsWith("LC"))

        // The body index.html sends from buildPayload().
        val applied = post(
            "/api/target",
            """{"mode":"path","latitude":40.1,"longitude":116.3,"altitude":10,"accuracy":5,"jitterMeters":0,
               "jitterAccuracy":false,"accuracyMin":3,"accuracyMax":12,"quality":1,"satellites":14,
               "jitterAltitude":false,"altitudeMin":5,"altitudeMax":20,"rate":250,"pathSpeedKmh":36,"pathLoop":true,
               "route":[{"latitude":40.1,"longitude":116.3,"altitude":null,"time":null},
                        {"latitude":40.2,"longitude":116.3,"altitude":null,"time":null}]}""",
        )
        assertTrue(applied.getValue("ok").jsonPrimitive.boolean)
        val now = applied.getValue("target").jsonObject
        assertEquals("path", now.getValue("mode").jsonPrimitive.content)
        assertEquals(2, now.getValue("routePoints").jsonPrimitive.int)
        assertEquals(10.0, now.getValue("pathSpeedMps").jsonPrimitive.double, 1e-9)
        assertEquals(14, now.getValue("satellites").jsonPrimitive.int)

        assertEquals(false, post("/api/target", "not json").getValue("ok").jsonPrimitive.boolean)

        val favorite = post("/api/favorites", """{"name":"天安门","latitude":39.9087,"longitude":116.3975,"altitude":44,"accuracy":5}""")
        val id = favorite.getValue("favorite").jsonObject.getValue("id").jsonPrimitive.content
        assertEquals("天安门", get("/api/favorites").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(post("/api/favorites/remove", """{"id":"$id"}""").getValue("ok").jsonPrimitive.boolean)
        assertEquals(0, get("/api/favorites").jsonArray.size)

        val rotated = post("/api/identity/rotate", "{}").getValue("identity").jsonObject
        assertEquals(rotated.getValue("serial"), get("/api/state").jsonObject.getValue("identity").jsonObject.getValue("serial"))

        assertEquals(0, get("/api/devices").jsonArray.size, "no Bluetooth platform on the JVM")
        val log = get("/api/log?since=0").jsonObject
        assertTrue(log.getValue("entries").jsonArray.isNotEmpty())
        assertEquals("请先选择设备", post("/api/sessions", """{"transport":"bt","mac":""}""").getValue("error").jsonPrimitive.content)
    }

    @Test
    fun tcpSessionRunsEndToEnd() {
        val keys = newP256()
        IdentitySource.provider = object : IdentityProvider {
            override val name = "test"
            override val warning: String? = null
            override fun resolve(directory: String?) = ResolvedIdentity(MfiSigner.fromMaterial(keys.private.encoded, ByteArray(8)), "test")
        }

        val listener = ServerSocket(0)
        var phone: SimulatedPhone? = null
        val accept = thread {
            phone = SimulatedPhone(TcpTransport.fromSocket(listener.accept(), "phone"), keys.public).start()
        }

        val started = post("/api/sessions", """{"transport":"tcp","host":"127.0.0.1","port":${listener.localPort},"rate":100,"label":"Sim"}""")
        assertTrue(started.getValue("ok").jsonPrimitive.boolean, started.toString())
        accept.join(5_000)

        waitUntil(message = "streaming session") {
            val session = get("/api/state").jsonObject.getValue("sessions").jsonArray.single().jsonObject
            session.getValue("subscribed").jsonPrimitive.boolean && session.getValue("sent").jsonPrimitive.int > 3
        }

        val session = get("/api/state").jsonObject.getValue("sessions").jsonArray.single().jsonObject
        assertEquals("Normal", session.getValue("link").jsonPrimitive.content)
        assertTrue(session.getValue("authenticated").jsonPrimitive.boolean)
        assertEquals("streaming", session.getValue("stage").jsonPrimitive.content)
        assertEquals("127.0.0.1:${listener.localPort}", session.getValue("address").jsonPrimitive.content)
        assertEquals(true, phone!!.signatureValid)

        val id = session.getValue("id").jsonPrimitive.content
        assertTrue(post("/api/sessions/remove", """{"id":"$id"}""").getValue("ok").jsonPrimitive.boolean)
        assertEquals(0, get("/api/state").jsonObject.getValue("sessions").jsonArray.size)
        phone!!.close()
        listener.close()
    }
}
