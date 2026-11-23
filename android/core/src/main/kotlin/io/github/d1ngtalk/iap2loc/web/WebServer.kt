package io.github.d1ngtalk.iap2loc.web

import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.engine.AppConfig
import io.github.d1ngtalk.iap2loc.engine.RunRequest
import io.github.d1ngtalk.iap2loc.engine.SessionManager
import io.github.d1ngtalk.iap2loc.engine.toJson
import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import io.github.d1ngtalk.iap2loc.protocol.LocationTarget
import io.github.d1ngtalk.iap2loc.protocol.RouteVertex
import io.github.d1ngtalk.iap2loc.protocol.TargetMode
import io.github.d1ngtalk.iap2loc.protocol.TargetUpdate
import io.github.d1ngtalk.iap2loc.transport.Bluetooth
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.time.LocalTime
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Minimal HTTP server for the control page: the same embedded page and JSON API as Web/WebServer.cs,
 * so Web/index.html runs unchanged on Android and can also be opened from another device on the LAN.
 */
class WebServer(
    port: Int,
    private val manager: SessionManager,
    private val config: AppConfig,
    /** False binds 127.0.0.1 only; true also accepts other devices on the network. */
    lanAccess: Boolean,
    private val index: ByteArray = loadIndex(),
) : Closeable {
    companion object {
        fun loadIndex(): ByteArray =
            WebServer::class.java.getResourceAsStream("/web/index.html")?.use { it.readBytes() }
                ?: throw IllegalStateException("embedded index.html not found")
    }

    private val listener = ServerSocket().apply {
        reuseAddress = true
        // IPv4 literals: Android's getLoopbackAddress() is ::1, which http://127.0.0.1 never reaches.
        val any = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        bind(InetSocketAddress(if (lanAccess) any else loopback, port))
    }
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "web").apply { isDaemon = true } }
    private val logGate = Any()
    private val log = ArrayList<LogEntry>()
    private var sequence = 0L
    private val onLog: (LocalTime, String, String) -> Unit = ::append
    @Volatile private var closed = false

    val port: Int = listener.localPort

    init {
        Log.addListener(onLog)
    }

    fun start() {
        Thread(::acceptLoop, "web-accept").apply { isDaemon = true }.start()
        Log.ok("控制页面：http://localhost:$port/")
    }

    /** Plain-text copy of the buffered console log, for "export logs". */
    fun logText(): String = synchronized(logGate) { log.joinToString("\n") { "${it.time} ${it.level.padEnd(5)} ${it.text}" } }

    private fun append(time: LocalTime, level: String, message: String) = synchronized(logGate) {
        log.add(LogEntry(++sequence, Log.format(time), level, message))
        if (log.size > 800) log.subList(0, log.size - 800).clear()
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                listener.accept()
            } catch (ex: Exception) {
                return
            }

            runCatching { workers.execute { handle(client) } }.onFailure { client.close() }
        }
    }

    private fun handle(client: Socket): Unit = client.use {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val output = client.getOutputStream()
            val request = readRequest(BufferedInputStream(client.getInputStream())) ?: return@use
            val (method, path, query, body) = request
            val post = method == "POST"
            when {
                path == "/" || path == "/index.html" -> respond(output, 200, "text/html; charset=utf-8", index)
                path == "/api/state" -> respondJson(output, manager.snapshot())
                path == "/api/devices" -> respondJson(output, json(Bluetooth.devices().map(::deviceJson)))
                path == "/api/target" && post -> respondJson(output, applyTarget(body))
                path == "/api/sessions" && post -> respondJson(output, startSession(body))
                path == "/api/sessions/remove" && post -> respondJson(output, removeSession(body))
                path == "/api/stop-all" && post -> {
                    manager.stopAll()
                    respondJson(output, obj("ok" to true))
                }

                path == "/api/identity/rotate" && post -> respondJson(output, rotateIdentity())
                path == "/api/favorites" && post -> respondJson(output, addFavorite(body))
                path == "/api/favorites" -> respondJson(output, json(config.favoritesSnapshot().map { it.toJson() }))
                path == "/api/favorites/remove" && post -> respondJson(output, removeFavorite(body))
                path == "/api/log" -> respondJson(output, logSince(query["since"]?.toLongOrNull() ?: 0))
                else -> respond(output, 404, "text/plain; charset=utf-8", "not found".toByteArray())
            }
        } catch (ex: Exception) {
            runCatching {
                respond(client.getOutputStream(), 500, "text/plain; charset=utf-8", (ex.message ?: "error").toByteArray())
            }
        }
    }

    private fun deviceJson(device: io.github.d1ngtalk.iap2loc.transport.BluetoothDeviceInfo) = obj(
        "address" to device.address,
        "name" to device.name,
        "connected" to device.connected,
        "remembered" to device.remembered,
        "paired" to device.paired,
        "likelyApple" to device.likelyApple,
    )

    private fun applyTarget(body: String): JsonObject {
        val payload = parseObject(body) ?: return obj("ok" to false, "error" to "请求格式错误")
        manager.applyTarget(targetUpdate(payload), payload.int("rate"))
        return obj("ok" to true, "target" to manager.target.snapshot().toJson())
    }

    private fun startSession(body: String): JsonObject {
        val payload = parseObject(body) ?: return obj("ok" to false, "error" to "请求格式错误")
        val transport = payload.text("transport")?.ifBlank { null } ?: "bt"
        val mac = payload.text("mac")
        if (transport.equals("bt", ignoreCase = true) && mac.isNullOrBlank()) {
            return obj("ok" to false, "error" to "请先选择设备")
        }

        val label = payload.text("label")
        val session = manager.add(
            RunRequest(
                transport = transport,
                mac = mac,
                host = payload.text("host"),
                port = payload.int("port") ?: 0,
                identityDirectory = payload.text("identity"),
                rate = payload.int("rate") ?: 250,
                label = if (label.isNullOrBlank()) mac ?: "iPhone" else label,
            ),
        )

        val error = session.lastError
        return obj("ok" to (error == null), "error" to error, "id" to session.id)
    }

    private fun rotateIdentity(): JsonObject {
        val profile = manager.rotateIdentity(Bluetooth.localAdapterAddress())
        return obj(
            "ok" to true,
            "identity" to obj(
                "name" to profile.name,
                "model" to profile.model,
                "serial" to profile.serial,
                "adapter" to profile.adapterAddress,
            ),
        )
    }

    private fun removeSession(body: String): JsonObject {
        val id = parseObject(body)?.text("id") ?: return obj("ok" to false, "error" to "缺少 id")
        return obj("ok" to manager.remove(id))
    }

    private fun addFavorite(body: String): JsonObject {
        val payload = parseObject(body)
        val latitude = payload?.double("latitude")
        val longitude = payload?.double("longitude")
        if (payload == null || latitude == null || longitude == null) return obj("ok" to false, "error" to "缺少坐标")

        val favorite = config.addFavorite(
            payload.text("name"), latitude, longitude, payload.double("altitude"), payload.double("accuracy"),
        )
        return obj("ok" to true, "favorite" to favorite.toJson())
    }

    private fun removeFavorite(body: String): JsonObject {
        val id = parseObject(body)?.text("id") ?: return obj("ok" to false)
        return obj("ok" to config.removeFavorite(id))
    }

    private fun logSince(since: Long): JsonObject = synchronized(logGate) {
        obj(
            "last" to sequence,
            "entries" to log.filter { it.id > since }.map {
                obj("id" to it.id, "time" to it.time, "level" to it.level, "text" to it.text)
            },
        )
    }

    private fun respondJson(output: OutputStream, payload: JsonElement) =
        respond(output, 200, "application/json; charset=utf-8", payload.toString().toByteArray(Charsets.UTF_8))

    private fun respond(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private data class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var tail = 0
        while (head.size() < 65536) {
            val value = input.read()
            if (value < 0) return null
            head.write(value)
            tail = (tail shl 8) or value
            if (tail == 0x0D0A0D0A) break
        }

        val lines = head.toString(Charsets.US_ASCII.name()).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null

        val target = parts[1]
        val question = target.indexOf('?')
        val path = if (question >= 0) target.substring(0, question) else target
        val query = HashMap<String, String>()
        if (question >= 0) {
            for (pair in target.substring(question + 1).split('&')) {
                val equals = pair.indexOf('=')
                if (equals > 0) query[decode(pair.substring(0, equals)).lowercase()] = decode(pair.substring(equals + 1))
            }
        }

        val contentLength = lines.drop(1).firstNotNullOfOrNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                line.substring(colon + 1).trim().toIntOrNull()
            } else {
                null
            }
        } ?: 0

        val body = ByteArray(contentLength.coerceIn(0, 32 * 1024 * 1024))
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read <= 0) break
            offset += read
        }

        return Request(parts[0], path, query, String(body, 0, offset, Charsets.UTF_8))
    }

    private fun decode(text: String) = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    override fun close() {
        closed = true
        Log.removeListener(onLog)
        runCatching { listener.close() }
        workers.shutdownNow()
    }

    private class LogEntry(val id: Long, val time: String, val level: String, val text: String)
}

/** The /api/target body: the same fields and clamping as TargetPayload in Web/WebServer.cs. */
internal fun targetUpdate(payload: JsonObject) = TargetUpdate(
    mode = when (payload.text("mode")?.trim()?.lowercase()) {
        "path", "route" -> TargetMode.PATH
        "point", "single" -> TargetMode.POINT
        else -> null
    },
    route = payload.list("route")?.let(::parseRoute),
    pathSpeedMps = payload.double("pathSpeedKmh")?.let { it.coerceIn(0.1, 180.0) / 3.6 },
    pathLoop = payload.bool("pathLoop"),
    latitude = payload.double("latitude"),
    longitude = payload.double("longitude"),
    altitude = payload.double("altitude"),
    accuracy = payload.double("accuracy"),
    speed = payload.double("speed"),
    bearing = payload.double("bearing"),
    jitterMeters = payload.double("jitterMeters"),
    jitterAccuracy = payload.bool("jitterAccuracy"),
    accuracyMin = payload.double("accuracyMin"),
    accuracyMax = payload.double("accuracyMax"),
    quality = payload.int("quality"),
    satellites = payload.int("satellites"),
    jitterAltitude = payload.bool("jitterAltitude"),
    altitudeMin = payload.double("altitudeMin"),
    altitudeMax = payload.double("altitudeMax"),
)

/** null keeps the current route; an empty array clears it. */
private fun parseRoute(points: List<JsonElement>): List<RouteVertex> {
    val parsed = ArrayList<RouteVertex>(minOf(points.size, LocationTarget.MAX_ROUTE_POINTS))
    for (element in points) {
        val point = element as? JsonObject ?: continue
        val latitude = point.double("latitude") ?: continue
        val longitude = point.double("longitude") ?: continue
        if (abs(latitude) > 90 || abs(longitude) > 180) continue

        val time = point.double("time")?.takeIf { it > 0 }?.let { it / 1000.0 }
        parsed.add(RouteVertex(GeoPoint(latitude, longitude, point.double("altitude")), time))
        if (parsed.size >= LocationTarget.MAX_ROUTE_POINTS) break
    }

    return parsed
}
