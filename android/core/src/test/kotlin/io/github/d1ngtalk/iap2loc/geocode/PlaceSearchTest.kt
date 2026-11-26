package io.github.d1ngtalk.iap2loc.geocode

import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Request building, signatures and response parsing of every web client, against canned responses. */
class PlaceSearchTest {
    private class FakeHttp(private val body: String, private val status: Int = 200) : HttpGet {
        val urls = ArrayList<String>()
        val headers = ArrayList<Map<String, String>>()

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url
            this.headers += headers
            return HttpResponse(status, body)
        }

        val params: Map<String, String>
            get() = urls.single().substringAfter('?').split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
    }

    private fun assertNear(expected: GeoPoint, place: Place, tolerance: Double = 1e-6) {
        assertEquals(expected.latitude, place.latitude, tolerance)
        assertEquals(expected.longitude, place.longitude, tolerance)
    }

    private val tiananmenWgs = GeoPoint(39.907359, 116.391263)

    @Test
    fun gcj02MatchesTheConsolePage() {
        // Reference values from wgs2gcj / gcj2wgs in Web/index.html, run in node.
        val shifted = mapOf(
            tiananmenWgs to GeoPoint(39.90876025227442, 116.39750414422875),
            GeoPoint(31.2304, 121.4737) to GeoPoint(31.22845773757727, 121.47822305927693),
            GeoPoint(22.543096, 114.057865) to GeoPoint(22.54037875229225, 114.06297895739921),
        )
        for ((wgs, gcj) in shifted) {
            val forward = Gcj02.fromWgs(wgs)
            assertEquals(gcj.latitude, forward.latitude, 1e-9)
            assertEquals(gcj.longitude, forward.longitude, 1e-9)
            val back = Gcj02.toWgs(forward)
            assertEquals(wgs.latitude, back.latitude, 1e-8)
            assertEquals(wgs.longitude, back.longitude, 1e-8)
        }

        val reverse = Gcj02.toWgs(GeoPoint(39.908720, 116.397500))
        assertEquals(39.90731875146729, reverse.latitude, 1e-9)
        assertEquals(116.3912588630737, reverse.longitude, 1e-9)

        val paris = GeoPoint(48.8584, 2.2945)
        assertEquals(paris, Gcj02.fromWgs(paris))
        assertEquals(paris, Gcj02.toWgs(paris))
    }

    @Test
    fun photonSendsTheBiasAndReadsGeoJson() {
        val http = FakeHttp(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"天安门","street":"西长安街","housenumber":"1","district":"首都功能核心区",
                "city":"北京市","state":"北京市","country":"中国"},"geometry":{"type":"Point","coordinates":[116.391263,39.907359]}},
              {"type":"Feature","properties":{"street":"Unter den Linden","housenumber":"77","city":"Berlin","country":"Deutschland"},
                "geometry":{"type":"Point","coordinates":[13.3807,52.5163]}},
              {"type":"Feature","properties":{"name":"no geometry"}}]}""",
        )

        val places = PhotonSearch("https://photon.example.org/", http).search("天安门", GeoPoint(31.2304, 121.4737))

        assertTrue(http.urls.single().startsWith("https://photon.example.org/api?"), http.urls.single())
        assertEquals(mapOf("q" to "天安门", "limit" to "10", "lat" to "31.230400", "lon" to "121.473700"), http.params)
        assertEquals(2, places.size)
        assertEquals(Place("天安门", "西长安街 1, 首都功能核心区, 北京市, 中国", 39.907359, 116.391263), places[0])
        assertEquals(Place("Unter den Linden 77", "Berlin, Deutschland", 52.5163, 13.3807), places[1])

        PhotonSearch(PhotonSearch.DEFAULT_URL, http).search("berlin", null)
        assertFalse(http.urls.last().contains("lat="))
    }

    @Test
    fun photonReportsServerErrors() {
        val rejected = assertFailsWith<PlaceSearchException> {
            PhotonSearch(PhotonSearch.DEFAULT_URL, FakeHttp("""{"message":"missing search term 'q'"}""", 400)).search("", null)
        }
        assertEquals("Photon 搜索失败：missing search term 'q'", rejected.message)

        val down = assertFailsWith<PlaceSearchException> {
            PhotonSearch(PhotonSearch.DEFAULT_URL, FakeHttp("<html>Bad Gateway</html>", 502)).search("x", null)
        }
        assertEquals("Photon 搜索失败：服务器返回 HTTP 502", down.message)
    }

    @Test
    fun nominatimReadsTheDisplayName() {
        val http = FakeHttp(
            """[{"lat":"39.9073","lon":"116.3912","name":"天安门","display_name":"天安门, 西长安街, 东城区, 北京市, 100010, 中国"},
                {"lat":"52.5163","lon":"13.3807","name":"","display_name":"77, Unter den Linden, Berlin, Deutschland"}]""",
        )

        val places = NominatimSearch(NominatimSearch.DEFAULT_URL, "zh-CN", http).search("天安门", tiananmenWgs)

        assertTrue(http.urls.single().startsWith("https://nominatim.openstreetmap.org/search?"))
        assertEquals(mapOf("q" to "天安门", "format" to "jsonv2", "limit" to "10", "accept-language" to "zh-CN"), http.params)
        assertEquals(Place("天安门", "西长安街, 东城区, 北京市, 100010, 中国", 39.9073, 116.3912), places[0])
        assertEquals(Place("77", "Unter den Linden, Berlin, Deutschland", 52.5163, 13.3807), places[1])

        val error = assertFailsWith<PlaceSearchException> {
            NominatimSearch(NominatimSearch.DEFAULT_URL, "", FakeHttp("""{"error":{"code":400,"message":"Nothing to search for."}}""", 400))
                .search("", null)
        }
        assertEquals("Nominatim 搜索失败：Nothing to search for.", error.message)
        assertFailsWith<PlaceSearchException> { NominatimSearch(NominatimSearch.DEFAULT_URL, "", FakeHttp("blocked", 403)).search("x", null) }
    }

    @Test
    fun amapSignsAndConvertsFromGcj() {
        // md5("key=abc&keywords=天安门&page_size=10s3cr3t"), computed independently.
        val params = listOf("key" to "abc", "keywords" to "天安门", "page_size" to "10")
        assertEquals("72faac2db760104e8f64d4f948df93b4", amapSignature(params, "s3cr3t"))

        val http = FakeHttp(
            """{"status":"1","info":"OK","infocode":"10000","count":"2","pois":[
              {"name":"天安门","location":"116.397504,39.908760","pname":"北京市","cityname":"北京市","adname":"东城区","address":"东长安街"},
              {"name":"无坐标","location":[],"address":[]}]}""",
        )
        AmapSearch("abc", "s3cr3t", http).search("天安门", tiananmenWgs).also { places ->
            assertEquals(1, places.size)
            assertEquals("天安门", places[0].name)
            assertEquals("北京市东城区东长安街", places[0].address)
            assertNear(tiananmenWgs, places[0])
        }
        assertEquals(params.toMap() + ("sig" to "72faac2db760104e8f64d4f948df93b4"), http.params)

        val unsigned = FakeHttp("""{"status":"1","pois":[]}""")
        AmapSearch("abc", "", unsigned).search("天安门", null)
        assertFalse("sig" in unsigned.params)

        val wrongKey = assertFailsWith<PlaceSearchException> {
            AmapSearch("abc", "", FakeHttp("""{"status":"0","info":"USERKEY_PLAT_NOMATCH","infocode":"10009"}""")).search("x", null)
        }
        assertEquals("高德搜索失败：USERKEY_PLAT_NOMATCH（10009），请改用「Web服务」类型的 Key", wrongKey.message)
    }

    @Test
    fun baiduSignsTheQueryAsSent() {
        // The example from Baidu's sn documentation.
        val example = baiduQuery(listOf("address" to "百度大厦", "output" to "json", "ak" to "yourak"))
        assertEquals("address=%E7%99%BE%E5%BA%A6%E5%A4%A7%E5%8E%A6&output=json&ak=yourak", example)
        assertEquals("7de5a22212ffaa9e326444c75a58f9a0", baiduSn("/geocoder/v2/", example, "yoursk"))

        val http = FakeHttp(
            """{"status":0,"message":"ok","result":[
              {"name":"天安门","location":{"lat":39.90876,"lng":116.397504},"province":"北京市","city":"北京市","district":"东城区",
               "address":"北京市东城区东长安街"},
              {"name":"天安门(公交站)","city":"北京市"}]}""",
        )
        val places = BaiduSearch("AK", "SK", http, clock = { 1_700_000_000_123 }).search("天安门", tiananmenWgs)

        assertEquals(
            "https://api.map.baidu.com/place/v2/suggestion?query=%E5%A4%A9%E5%AE%89%E9%97%A8&region=%E5%85%A8%E5%9B%BD" +
                "&output=json&ret_coordtype=gcj02ll&ak=AK&timestamp=1700000000&sn=a20f62f0345e5fda5a0759dd329dacd1",
            http.urls.single(),
        )
        assertEquals(1, places.size)
        assertEquals("北京市东城区东长安街", places[0].address)
        assertNear(tiananmenWgs, places[0], 1e-5)

        val unsigned = FakeHttp("""{"status":0,"result":[]}""")
        BaiduSearch("AK", "", unsigned).search("天安门", null)
        assertFalse("sn" in unsigned.params || "timestamp" in unsigned.params)

        val disabled = assertFailsWith<PlaceSearchException> {
            BaiduSearch("AK", "", FakeHttp("""{"status":240,"message":"APP 服务被禁用"}""")).search("x", null)
        }
        assertTrue(disabled.message!!.startsWith("百度搜索失败：APP 服务被禁用（240）。请确认使用「服务端」AK"), disabled.message)
    }

    @Test
    fun tencentSignsAndBiasesInGcj() {
        val http = FakeHttp(
            """{"status":0,"message":"query ok","count":1,"data":[{"title":"天安门","address":"北京市东城区东长安街",
              "province":"北京市","city":"北京市","district":"东城区","location":{"lat":39.90876,"lng":116.397504}}]}""",
        )
        val places = TencentSearch("KEY", "SK", http).search("天安门", tiananmenWgs)

        // md5("/ws/place/v1/suggestion?key=KEY&keyword=天安门&location=39.908760,116.397504&page_size=10SK"), computed independently.
        assertEquals(
            mapOf(
                "keyword" to "天安门", "key" to "KEY", "page_size" to "10", "location" to "39.908760,116.397504",
                "sig" to "3487d5d5b3759e17a178a942310e9a26",
            ),
            http.params,
        )
        assertEquals("no", http.headers.single()["x-legacy-url-decode"])
        assertEquals("北京市东城区东长安街", places.single().address)
        assertNear(tiananmenWgs, places.single(), 1e-5)

        val bad = assertFailsWith<PlaceSearchException> {
            TencentSearch("KEY", "", FakeHttp("""{"status":311,"message":"key格式错误"}""")).search("x", null)
        }
        assertEquals("腾讯位置服务搜索失败：key格式错误（311）", bad.message)
    }

    @Test
    fun chineseAddressDropsRepeatedParts() {
        assertEquals("北京市东城区东长安街", chineseAddress("北京市", "北京市", "东城区", "东长安街"))
        assertEquals("北京市东城区东长安街", chineseAddress("北京市", "北京市", "东城区", "北京市东城区东长安街"))
        assertEquals("上海市", chineseAddress(null, "", "上海市", null))
    }
}
