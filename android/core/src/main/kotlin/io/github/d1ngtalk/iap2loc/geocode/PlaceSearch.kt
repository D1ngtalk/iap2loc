package io.github.d1ngtalk.iap2loc.geocode

import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import io.github.d1ngtalk.iap2loc.web.parseObject
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** A search hit. Coordinates are WGS-84, like everything the accessory sends; [address] may be empty. */
data class Place(val name: String, val address: String, val latitude: Double, val longitude: Double)

/**
 * Place name to candidate places. Blocking network I/O, so call it off the main thread. [near] is the
 * current target: services with a soft location bias rank hits around it first, the others ignore it.
 */
fun interface PlaceSearch {
    fun search(query: String, near: GeoPoint?): List<Place>
}

/** A failure the user can act on (wrong key, quota, service down); the message is shown as is. */
class PlaceSearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** How many candidates every service is asked for. */
const val SEARCH_LIMIT = 10

data class HttpResponse(val status: Int, val body: String)

/** The only network seam, so the clients are tested with canned responses. Any HTTP status is returned. */
fun interface HttpGet {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** HttpURLConnection with short timeouts and an identifying User-Agent, which Nominatim requires. */
class UrlConnectionHttp(private val userAgent: String, private val timeoutMs: Int = 15_000) : HttpGet {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            return HttpResponse(status, stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

/** The body as a JSON object, or a failure naming the service and the HTTP status. */
internal fun HttpResponse.jsonObject(service: String): JsonObject =
    parseObject(body) ?: throw PlaceSearchException("$service 搜索失败：服务器返回 HTTP $status")

/** Percent-encodes a query value; %20 for spaces, which every service accepts. */
internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun queryString(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (name, value) -> "$name=${urlEncode(value)}" }

/** name=value pairs sorted by name with raw values: what AMap and Tencent sign. */
internal fun sortedRawQuery(params: List<Pair<String, String>>): String =
    params.sortedBy { it.first }.joinToString("&") { (name, value) -> "$name=$value" }

internal fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Fixed-point, never 1.0E-5: not every service parses scientific notation. */
internal fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

/** A server root typed by the user, without the trailing slash. */
internal fun baseUrl(url: String): String = url.trim().trimEnd('/')

/** Joins address parts, skipping blanks, repeats and the place's own name. */
internal fun joinParts(separator: String, parts: List<String?>, skip: String? = null): String =
    parts.mapNotNull { it?.trim()?.takeIf { part -> part.isNotEmpty() && part != skip } }.distinct().joinToString(separator)

/**
 * A Chinese address from the largest part to the smallest. Municipalities repeat the city
 * (北京市北京市) and some services return a detail that already starts with the province.
 */
internal fun chineseAddress(vararg parts: String?): String {
    var result = ""
    for (raw in parts) {
        val part = raw?.trim().orEmpty()
        result = when {
            part.isEmpty() || result.contains(part) -> result
            part.startsWith(result) -> part
            else -> result + part
        }
    }
    return result
}

/** A validated hit; [gcj] converts a GCJ-02 point from a Chinese service back to WGS-84. */
internal fun place(name: String?, address: String, latitude: Double?, longitude: Double?, gcj: Boolean): Place? {
    if (name.isNullOrBlank() || latitude == null || longitude == null) return null
    if (!latitude.isFinite() || !longitude.isFinite() || abs(latitude) > 90 || abs(longitude) > 180) return null
    val point = if (gcj) Gcj02.toWgs(GeoPoint(latitude, longitude)) else GeoPoint(latitude, longitude)
    return Place(name.trim(), address, point.latitude, point.longitude)
}
