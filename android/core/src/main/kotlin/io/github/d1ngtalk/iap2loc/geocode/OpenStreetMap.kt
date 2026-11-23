package io.github.d1ngtalk.iap2loc.geocode

import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import io.github.d1ngtalk.iap2loc.web.JsonCodec
import io.github.d1ngtalk.iap2loc.web.child
import io.github.d1ngtalk.iap2loc.web.list
import io.github.d1ngtalk.iap2loc.web.parseObject
import io.github.d1ngtalk.iap2loc.web.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** komoot's Photon on OpenStreetMap data: no key, worldwide, names in the local language. */
class PhotonSearch(baseUrl: String, private val http: HttpGet) : PlaceSearch {
    companion object {
        const val DEFAULT_URL = "https://photon.komoot.io"
    }

    private val endpoint = baseUrl(baseUrl) + "/api"

    override fun search(query: String, near: GeoPoint?): List<Place> {
        val params = mutableListOf("q" to query, "limit" to SEARCH_LIMIT.toString())
        if (near != null) params += listOf("lat" to coordinate(near.latitude), "lon" to coordinate(near.longitude))

        val response = http.get("$endpoint?${queryString(params)}", emptyMap())
        val root = parseObject(response.body)
        val features = root?.list("features")
        if (response.status != 200 || features == null) {
            throw PlaceSearchException("Photon 搜索失败：${root?.text("message") ?: "服务器返回 HTTP ${response.status}"}")
        }

        return features.mapNotNull { (it as? JsonObject)?.let(::toPlace) }
    }

    private fun toPlace(feature: JsonObject): Place? {
        val coordinates = feature.child("geometry")?.list("coordinates")
        val properties = feature.child("properties") ?: JsonObject(emptyMap())
        val street = joinParts(" ", listOf(properties.text("street"), properties.text("housenumber")))
        val name = properties.text("name") ?: street.ifEmpty { null } ?: properties.text("city")
        val address = joinParts(
            ", ",
            listOf(street, properties.text("district"), properties.text("city"), properties.text("state"), properties.text("country")),
            skip = name,
        )
        // GeoJSON order: longitude first.
        return place(name, address, coordinates?.number(1), coordinates?.number(0), gcj = false)
    }

    private fun JsonArray.number(index: Int): Double? = (getOrNull(index) as? JsonPrimitive)?.doubleOrNull
}

/** The OpenStreetMap Foundation's geocoder: no key, good at full addresses, at most one request a second. */
class NominatimSearch(baseUrl: String, private val language: String, private val http: HttpGet) : PlaceSearch {
    companion object {
        const val DEFAULT_URL = "https://nominatim.openstreetmap.org"
    }

    private val endpoint = baseUrl(baseUrl) + "/search"

    override fun search(query: String, near: GeoPoint?): List<Place> {
        val params = mutableListOf("q" to query, "format" to "jsonv2", "limit" to SEARCH_LIMIT.toString())
        if (language.isNotBlank()) params += "accept-language" to language

        val response = http.get("$endpoint?${queryString(params)}", emptyMap())
        val hits = runCatching { JsonCodec.parseToJsonElement(response.body) }.getOrNull()
        if (response.status != 200 || hits !is JsonArray) {
            val error = (hits as? JsonObject)?.let { it.child("error")?.text("message") ?: it.text("error") }
            throw PlaceSearchException("Nominatim 搜索失败：${error ?: "服务器返回 HTTP ${response.status}"}")
        }

        return hits.mapNotNull { hit ->
            if (hit !is JsonObject) return@mapNotNull null
            val display = hit.text("display_name").orEmpty()
            val name = hit.text("name")?.takeIf(String::isNotBlank) ?: display.substringBefore(',').trim()
            val address = display.removePrefix(name).removePrefix(",").trim()
            place(name, address, hit.text("lat")?.toDoubleOrNull(), hit.text("lon")?.toDoubleOrNull(), gcj = false)
        }
    }
}
