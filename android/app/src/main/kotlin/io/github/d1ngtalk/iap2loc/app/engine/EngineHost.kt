package io.github.d1ngtalk.iap2loc.app.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.engine.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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
        runCatching { current?.close() }
        state.value = if (restarting) Status.Starting else Status.Stopped
    }
}
