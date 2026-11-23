package io.github.d1ngtalk.iap2loc.app.search

import android.content.Context
import io.github.d1ngtalk.iap2loc.BuildConfig
import io.github.d1ngtalk.iap2loc.app.ui.SOURCE_URL
import io.github.d1ngtalk.iap2loc.geocode.AmapSearch
import io.github.d1ngtalk.iap2loc.geocode.BaiduSearch
import io.github.d1ngtalk.iap2loc.geocode.NominatimSearch
import io.github.d1ngtalk.iap2loc.geocode.PhotonSearch
import io.github.d1ngtalk.iap2loc.geocode.PlaceSearch
import io.github.d1ngtalk.iap2loc.geocode.TencentSearch
import io.github.d1ngtalk.iap2loc.geocode.UrlConnectionHttp
import java.util.Locale

/** The place search services the settings page offers, in display order. */
enum class SearchProvider(val id: String, val label: String, val summary: String) {
    PHOTON("photon", "OpenStreetMap · Photon", "默认。komoot 提供的免费服务，无需 Key，覆盖全球。"),
    NOMINATIM(
        "nominatim",
        "OpenStreetMap · Nominatim",
        "OpenStreetMap 基金会的官方服务，无需 Key，擅长完整地址；公共服务器每秒最多一次请求。",
    ),
    ANDROID(
        "android",
        "Android 系统 Geocoder",
        "手机系统自带的地理编码服务，通常由 Google Play 服务提供，无需 Key；很多国内手机没有这项服务。",
    ),
    AMAP("amap", "高德地图", "国内地点最全之一。需要高德开放平台「Web服务」类型的 Key。"),
    BAIDU("baidu", "百度地图", "需要百度地图开放平台「服务端」类型的 AK。"),
    TENCENT("tencent", "腾讯位置服务", "需要腾讯位置服务的 Key，并在 Key 设置里启用 WebServiceAPI。"),
    ;

    /** OpenStreetMap data has to be credited wherever it is shown (ODbL). */
    val openStreetMap: Boolean get() = this == PHOTON || this == NOMINATIM

    companion object {
        fun fromId(id: String?): SearchProvider = entries.firstOrNull { it.id == id } ?: PHOTON
    }
}

/** What 地点搜索设置 stores: the chosen service and the credentials of every service. */
data class SearchSettings(
    val provider: SearchProvider = SearchProvider.PHOTON,
    val photonUrl: String = PhotonSearch.DEFAULT_URL,
    val nominatimUrl: String = NominatimSearch.DEFAULT_URL,
    val geocoderGcj: Boolean = true,
    val amapKey: String = "",
    val amapSecret: String = "",
    val baiduAk: String = "",
    val baiduSk: String = "",
    val tencentKey: String = "",
    val tencentSk: String = "",
) {
    /** The required field [provider] still lacks, or null when it can search. */
    val missing: String?
        get() = when (provider) {
            SearchProvider.PHOTON -> "Photon 服务器地址".takeUnless { isServerUrl(photonUrl) }
            SearchProvider.NOMINATIM -> "Nominatim 服务器地址".takeUnless { isServerUrl(nominatimUrl) }
            SearchProvider.ANDROID -> null
            SearchProvider.AMAP -> "高德 Web服务 Key".takeIf { amapKey.isBlank() }
            SearchProvider.BAIDU -> "百度服务端 AK".takeIf { baiduAk.isBlank() }
            SearchProvider.TENCENT -> "腾讯位置服务 Key".takeIf { tencentKey.isBlank() }
        }

    fun client(context: Context): PlaceSearch {
        val http = UrlConnectionHttp(USER_AGENT)
        return when (provider) {
            SearchProvider.PHOTON -> PhotonSearch(photonUrl, http)
            SearchProvider.NOMINATIM -> NominatimSearch(nominatimUrl, Locale.getDefault().toLanguageTag(), http)
            SearchProvider.ANDROID -> AndroidGeocoderSearch(context.applicationContext, geocoderGcj)
            SearchProvider.AMAP -> AmapSearch(amapKey.trim(), amapSecret.trim(), http)
            SearchProvider.BAIDU -> BaiduSearch(baiduAk.trim(), baiduSk.trim(), http)
            SearchProvider.TENCENT -> TencentSearch(tencentKey.trim(), tencentSk.trim(), http)
        }
    }

    companion object {
        /** Nominatim's usage policy asks for a User-Agent that identifies the application. */
        private val USER_AGENT = "iap2loc-android/${BuildConfig.VERSION_NAME} (+$SOURCE_URL)"

        /** The app only allows cleartext to itself, so a self-hosted server needs https. */
        fun isServerUrl(url: String): Boolean = url.trim().let { it.startsWith("https://") && it.length > "https://".length }
    }
}
