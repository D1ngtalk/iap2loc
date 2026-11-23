package io.github.d1ngtalk.iap2loc.app

import android.content.Context
import androidx.core.content.edit
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
}
