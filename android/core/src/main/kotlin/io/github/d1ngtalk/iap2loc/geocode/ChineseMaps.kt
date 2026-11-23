package io.github.d1ngtalk.iap2loc.geocode

import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import io.github.d1ngtalk.iap2loc.web.child
import io.github.d1ngtalk.iap2loc.web.double
import io.github.d1ngtalk.iap2loc.web.int
import io.github.d1ngtalk.iap2loc.web.list
import io.github.d1ngtalk.iap2loc.web.text
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

// The three Chinese services publish GCJ-02; every hit is converted back to WGS-84 before it leaves.

/** AMap's Web服务「搜索POI 2.0」keyword search, nationwide. Needs a「Web服务」key, optionally signed. */
class AmapSearch(private val key: String, private val secret: String, private val http: HttpGet) : PlaceSearch {
    companion object {
        const val ENDPOINT = "https://restapi.amap.com/v5/place/text"

        /** The infocodes a user can fix in the settings, see AMap's 错误码说明. */
        private val HINTS = mapOf(
            "10001" to "，Key 不正确或已失效",
            "10003" to "，今日调用量已超出配额",
            "10007" to "，数字签名不正确，请检查私钥",
            "10009" to "，请改用「Web服务」类型的 Key",
            "10044" to "，今日调用量已超出配额",
        )
    }

    override fun search(query: String, near: GeoPoint?): List<Place> {
        val params = listOf("key" to key, "keywords" to query, "page_size" to SEARCH_LIMIT.toString())
        val signed = if (secret.isBlank()) params else params + ("sig" to amapSignature(params, secret))

        val root = http.get("$ENDPOINT?${queryString(signed)}", emptyMap()).jsonObject("高德")
        if (root.text("status") != "1") {
            val code = root.text("infocode")
            throw PlaceSearchException("高德搜索失败：${root.text("info") ?: "未知错误"}（$code）${HINTS[code].orEmpty()}")
        }

        return root.list("pois").orEmpty().mapNotNull { element ->
            val poi = element as? JsonObject ?: return@mapNotNull null
            // "lng,lat"
            val location = poi.text("location")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            val address = chineseAddress(poi.text("pname"), poi.text("cityname"), poi.text("adname"), poi.text("address"))
            place(poi.text("name"), address, location?.getOrNull(1), location?.getOrNull(0), gcj = true)
        }
    }
}

/** AMap 数字签名: md5 of the raw parameters sorted by name, followed by the private key. */
internal fun amapSignature(params: List<Pair<String, String>>, secret: String): String = md5Hex(sortedRawQuery(params) + secret)

/** Baidu's Web服务「地点输入提示」, nationwide and asked for GCJ-02 instead of BD-09. Needs a「服务端」AK. */
class BaiduSearch(
    private val ak: String,
    private val sk: String,
    private val http: HttpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaceSearch {
    companion object {
        private const val HOST = "https://api.map.baidu.com"
        const val PATH = "/place/v2/suggestion"
    }

    override fun search(query: String, near: GeoPoint?): List<Place> {
        val params = mutableListOf("query" to query, "region" to "全国", "output" to "json", "ret_coordtype" to "gcj02ll", "ak" to ak)
        if (sk.isNotBlank()) params += "timestamp" to (clock() / 1000).toString()
        val encoded = baiduQuery(params)
        val signature = if (sk.isBlank()) "" else "&sn=" + baiduSn(PATH, encoded, sk)

        val root = http.get("$HOST$PATH?$encoded$signature", emptyMap()).jsonObject("百度")
        val status = root.int("status")
        if (status != 0) {
            val hint = if (status in 200..299) "。请确认使用「服务端」AK，并设置了 sn 校验（填写 SK）或 IP 白名单" else ""
            throw PlaceSearchException("百度搜索失败：${root.text("message") ?: "未知错误"}（$status）$hint")
        }

        return root.list("result").orEmpty().mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            val location = hit.child("location")
            val address = chineseAddress(hit.text("province"), hit.text("city"), hit.text("district"), hit.text("address"))
            place(hit.text("name"), address, location?.double("lat"), location?.double("lng"), gcj = true)
        }
    }
}

/** Baidu signs the query exactly as sent: request order, values encoded the way Java's URLEncoder does. */
internal fun baiduQuery(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }

/** Baidu sn: md5 of the URL-encoded "path?query" followed by the SK. */
internal fun baiduSn(path: String, encodedQuery: String, sk: String): String =
    md5Hex(URLEncoder.encode("$path?$encodedQuery$sk", "UTF-8"))

/** Tencent location service's WebService「关键词输入提示」, nationwide. Needs a key with WebServiceAPI enabled. */
class TencentSearch(private val key: String, private val sk: String, private val http: HttpGet) : PlaceSearch {
    companion object {
        private const val HOST = "https://apis.map.qq.com"
        const val PATH = "/ws/place/v1/suggestion"
    }

    override fun search(query: String, near: GeoPoint?): List<Place> {
        val params = mutableListOf("keyword" to query, "key" to key, "page_size" to SEARCH_LIMIT.toString())
        if (near != null) {
            // Only ranks category words such as 酒店 by distance; the service expects GCJ-02 "lat,lng".
            val shifted = Gcj02.fromWgs(near)
            params += "location" to "${coordinate(shifted.latitude)},${coordinate(shifted.longitude)}"
        }
        val signed = if (sk.isBlank()) params else params + ("sig" to tencentSignature(PATH, params, sk))

        // Tencent's documented switch for signed values that contain & or #.
        val response = http.get("$HOST$PATH?${queryString(signed)}", mapOf("x-legacy-url-decode" to "no"))
        val root = response.jsonObject("腾讯位置服务")
        val status = root.int("status")
        if (status != 0) throw PlaceSearchException("腾讯位置服务搜索失败：${root.text("message") ?: "未知错误"}（$status）")

        return root.list("data").orEmpty().mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            val location = hit.child("location")
            val address = chineseAddress(hit.text("province"), hit.text("city"), hit.text("district"), hit.text("address"))
            place(hit.text("title"), address, location?.double("lat"), location?.double("lng"), gcj = true)
        }
    }
}

/** Tencent sig: md5 of "path?" plus the raw parameters sorted by name, followed by the SK. */
internal fun tencentSignature(path: String, params: List<Pair<String, String>>, sk: String): String =
    md5Hex("$path?${sortedRawQuery(params)}$sk")
