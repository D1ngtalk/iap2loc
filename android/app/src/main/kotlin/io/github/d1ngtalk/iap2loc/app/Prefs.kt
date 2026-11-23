package io.github.d1ngtalk.iap2loc.app

import android.content.Context
import androidx.core.content.edit
import io.github.d1ngtalk.iap2loc.app.search.SearchProvider
import io.github.d1ngtalk.iap2loc.app.search.SearchSettings
import io.github.d1ngtalk.iap2loc.engine.Engine

/** App-only settings. The accessory identity and favorites stay in iap2loc.json, like on the desktop. */
class Prefs(context: Context) {
    private val store = context.getSharedPreferences("iap2loc", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = store.getBoolean("onboardingDone", false)
        set(value) = store.edit { putBoolean("onboardingDone", value) }

    var disclaimerAccepted: Boolean
        get() = store.getBoolean("disclaimerAccepted", false)
        set(value) = store.edit { putBoolean("disclaimerAccepted", value) }

    /** Off by default: on a phone the console would otherwise be open to everyone on the same Wi-Fi. */
    var lanAccess: Boolean
        get() = store.getBoolean("lanAccess", false)
        set(value) = store.edit { putBoolean("lanAccess", value) }

    var port: Int
        get() = store.getInt("port", Engine.DEFAULT_PORT)
        set(value) = store.edit { putInt("port", value) }

    var verboseLog: Boolean
        get() = store.getBoolean("verboseLog", false)
        set(value) = store.edit { putBoolean("verboseLog", value) }

    /** The iPhone chosen during onboarding, so the test step and the console know which one. */
    var deviceAddress: String?
        get() = store.getString("deviceAddress", null)
        set(value) = store.edit { putString("deviceAddress", value) }

    var deviceName: String?
        get() = store.getString("deviceName", null)
        set(value) = store.edit { putString("deviceName", value) }

    /** Place search: the chosen service and every service's credentials. */
    var search: SearchSettings
        get() {
            val defaults = SearchSettings()
            fun text(key: String, default: String) = store.getString("search.$key", null) ?: default
            return SearchSettings(
                provider = SearchProvider.fromId(store.getString("search.provider", null)),
                photonUrl = text("photonUrl", defaults.photonUrl),
                nominatimUrl = text("nominatimUrl", defaults.nominatimUrl),
                geocoderGcj = store.getBoolean("search.geocoderGcj", defaults.geocoderGcj),
                amapKey = text("amapKey", ""),
                amapSecret = text("amapSecret", ""),
                baiduAk = text("baiduAk", ""),
                baiduSk = text("baiduSk", ""),
                tencentKey = text("tencentKey", ""),
                tencentSk = text("tencentSk", ""),
            )
        }
        set(value) = store.edit {
            putString("search.provider", value.provider.id)
            putString("search.photonUrl", value.photonUrl)
            putString("search.nominatimUrl", value.nominatimUrl)
            putBoolean("search.geocoderGcj", value.geocoderGcj)
            putString("search.amapKey", value.amapKey)
            putString("search.amapSecret", value.amapSecret)
            putString("search.baiduAk", value.baiduAk)
            putString("search.baiduSk", value.baiduSk)
            putString("search.tencentKey", value.tencentKey)
            putString("search.tencentSk", value.tencentSk)
        }
}
