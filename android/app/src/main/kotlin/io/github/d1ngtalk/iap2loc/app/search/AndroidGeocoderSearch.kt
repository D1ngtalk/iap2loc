package io.github.d1ngtalk.iap2loc.app.search

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.d1ngtalk.iap2loc.geocode.Gcj02
import io.github.d1ngtalk.iap2loc.geocode.Place
import io.github.d1ngtalk.iap2loc.geocode.PlaceSearch
import io.github.d1ngtalk.iap2loc.geocode.PlaceSearchException
import io.github.d1ngtalk.iap2loc.geocode.SEARCH_LIMIT
import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import java.io.IOException
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone's own Geocoder, usually backed by Google Play services; many Chinese ROMs ship none.
 * Google answers in GCJ-02 inside mainland China, so [chinaIsGcj] converts those hits back to WGS-84.
 */
class AndroidGeocoderSearch(private val context: Context, private val chinaIsGcj: Boolean) : PlaceSearch {
    override fun search(query: String, near: GeoPoint?): List<Place> {
        if (!Geocoder.isPresent()) throw PlaceSearchException("这台手机没有系统地理编码服务，请在地点搜索设置里换一个服务商。")

        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lookup(geocoder, query) else legacyLookup(geocoder, query)
        } catch (ex: IOException) {
            throw PlaceSearchException("系统地理编码服务不可用：${ex.message ?: "网络错误"}", ex)
        }

        return addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { address ->
            // Geocoders answer with addresses, not POIs; featureName is a street, a district or "501号".
            val line = address.getAddressLine(0).orEmpty().trim()
            val name = line.substringBefore(',').trim().ifEmpty { address.featureName ?: query }
            val point = GeoPoint(address.latitude, address.longitude).let { if (chinaIsGcj) Gcj02.toWgs(it) else it }
            Place(name, line.substringAfter(',', "").trim(), point.latitude, point.longitude)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyLookup(geocoder: Geocoder, query: String): List<Address> = geocoder.getFromLocationName(query, SEARCH_LIMIT).orEmpty()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lookup(geocoder: Geocoder, query: String): List<Address> {
        val answer = LinkedBlockingQueue<Result<List<Address>>>()
        geocoder.getFromLocationName(query, SEARCH_LIMIT, object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                answer.put(Result.success(addresses))
            }

            override fun onError(errorMessage: String?) {
                answer.put(Result.failure(IOException(errorMessage ?: "未知错误")))
            }
        })
        return answer.poll(30, TimeUnit.SECONDS)?.getOrThrow() ?: throw IOException("30 秒内没有响应")
    }
}
