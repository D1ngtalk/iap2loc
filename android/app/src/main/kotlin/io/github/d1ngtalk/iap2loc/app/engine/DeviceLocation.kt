package io.github.d1ngtalk.iap2loc.app.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The phone's own position (WGS-84), the console's starting point instead of a fixed city. */
internal object DeviceLocation {
    /** A cached fix younger than this is used as is; older ones only when no fresh fix arrives. */
    private const val FRESH_MS = 10 * 60_000L
    private val preferred = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)

    /** Null without the (optional) location permission, with location off, or when nothing arrives in time. */
    @SuppressLint("MissingPermission")
    fun find(context: Context, timeoutMs: Long = 4_000): GeoPoint? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())

        val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time < FRESH_MS) return cached.toPoint()

        // Ask every usable provider at once and take the first answer: fused can stay silent (no Play
        // services, emulator), GPS needs the sky, network needs a connection.
        val asked = preferred.filter(providers::contains).ifEmpty { return cached?.toPoint() }
        val fresh = AtomicReference<Location?>()
        val pending = AtomicInteger(asked.size)
        val done = CountDownLatch(1)
        val cancels = asked.map { CancellationSignal() }
        for ((provider, cancel) in asked.zip(cancels)) {
            runCatching {
                LocationManagerCompat.getCurrentLocation(manager, provider, cancel, Runnable::run) { location ->
                    if (location != null) fresh.compareAndSet(null, location)
                    if (location != null || pending.decrementAndGet() == 0) done.countDown()
                }
            }.onFailure { if (pending.decrementAndGet() == 0) done.countDown() }
        }
        runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
        cancels.forEach(CancellationSignal::cancel)
        return (fresh.get() ?: cached)?.toPoint()
    }

    private fun Location.toPoint() = GeoPoint(latitude, longitude)
}
