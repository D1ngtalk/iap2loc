package io.github.d1ngtalk.iap2loc.app

import android.app.Application
import android.content.Context
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.app.platform.AndroidBluetooth
import io.github.d1ngtalk.iap2loc.app.platform.ImportedIdentity
import io.github.d1ngtalk.iap2loc.protocol.IdentitySource
import io.github.d1ngtalk.iap2loc.transport.Bluetooth

/** Wires the platform pieces into the shared core once per process. */
class Iap2LocApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var bluetooth: AndroidBluetooth
        private set
    lateinit var identity: ImportedIdentity
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        bluetooth = AndroidBluetooth(this)
        identity = ImportedIdentity(this)

        Log.output = { level, message ->
            when (level) {
                "error" -> android.util.Log.e(TAG, message)
                "warn" -> android.util.Log.w(TAG, message)
                "trace" -> android.util.Log.d(TAG, message)
                else -> android.util.Log.i(TAG, message)
            }
        }
        Log.verbose = prefs.verboseLog
        Bluetooth.platform = bluetooth
        IdentitySource.provider = identity
    }

    companion object {
        const val TAG = "iap2loc"
    }
}

val Context.app: Iap2LocApp get() = applicationContext as Iap2LocApp
