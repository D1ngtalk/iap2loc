package io.github.d1ngtalk.iap2loc.app.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.engine.Engine
import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import io.github.d1ngtalk.iap2loc.protocol.TargetMode
import io.github.d1ngtalk.iap2loc.protocol.TargetUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * The process-wide engine. [EngineService] owns its lifetime so streaming survives the activities;
 * screens only observe [status] and use [engine] while it is running.
 */
object EngineHost {
    sealed interface Status {
        data object Stopped : Status
        data object Starting : Status
        data class Running(val port: Int, val lanAccess: Boolean) : Status
        data class Failed(val message: String) : Status
    }

    private val state = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = state

    @Volatile
    var engine: Engine? = null
        private set

    /** Where the phone was when [engine] started; its target starts there. Null falls back to the built-in default. */
    @Volatile
    var home: GeoPoint? = null
        private set

    /** The target is still the phone's position from startup, i.e. nothing has moved it yet. */
    fun targetIsHome(): Boolean {
        val here = home ?: return false
        val target = engine?.manager?.target?.snapshot() ?: return false
        return target.mode == TargetMode.POINT && target.routePoints == 0 &&
            abs(target.latitude - here.latitude) < 1e-6 && abs(target.longitude - here.longitude) < 1e-6
    }

    fun configFile(context: Context) = File(context.filesDir, "iap2loc.json")

    /** Starts the foreground service (idempotent); the engine comes up on its worker thread. */
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
    }

    /** Applies console settings (LAN access, port). Running sessions are dropped. */
    fun restart(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java).setAction(EngineService.ACTION_RESTART))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, EngineService::class.java))
    }

    suspend fun awaitEngine(context: Context, timeoutMs: Long = 15_000): Engine? {
        start(context)
        return withTimeoutOrNull(timeoutMs) {
            state.first { it is Status.Running || it is Status.Failed }
            engine
        }
    }

    @Synchronized
    internal fun open(context: Context) {
        if (engine != null) return
        state.value = Status.Starting
        val prefs = context.app.prefs
        try {
            val created = Engine(configFile(context), port = prefs.port, lanAccess = prefs.lanAccess)
            home = DeviceLocation.find(context)?.also { here ->
                created.manager.applyTarget(TargetUpdate(mode = TargetMode.POINT, latitude = here.latitude, longitude = here.longitude), null)
                Log.info("默认位置：手机当前位置（${"%.6f".format(Locale.ROOT, here.latitude)}, ${"%.6f".format(Locale.ROOT, here.longitude)}）")
            }
            engine = created
            state.value = Status.Running(created.server.port, prefs.lanAccess)
        } catch (ex: Exception) {
            Log.error("控制台启动失败：${ex.message}")
            state.value = Status.Failed(ex.message ?: ex.toString())
        }
    }

    /** A restart reports Starting instead of Stopped, so the console does not treat it as "exit". */
    @Synchronized
    internal fun close(restarting: Boolean = false) {
        val current = engine
        engine = null
        home = null
        runCatching { current?.close() }
        state.value = if (restarting) Status.Starting else Status.Stopped
    }
}
