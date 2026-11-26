package io.github.d1ngtalk.iap2loc

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/** The same levels and messages as the desktop build, so logs from both read alike. */
object Log {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Volatile
    var verbose: Boolean = false

    /** Platform output: stdout on the JVM, logcat on Android. Must not throw. */
    @Volatile
    var output: (level: String, message: String) -> Unit = { level, message ->
        println("${LocalTime.now().format(clock)} ${level.padEnd(5)} $message")
    }

    private val listeners = CopyOnWriteArrayList<(LocalTime, String, String) -> Unit>()

    /** Every line, for the web console. Handlers must not throw. */
    fun addListener(listener: (LocalTime, String, String) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (LocalTime, String, String) -> Unit) = listeners.remove(listener)

    fun info(message: String) = write("info", message)

    fun step(message: String) = write("step", message)

    fun warn(message: String) = write("warn", message)

    fun error(message: String) = write("error", message)

    fun ok(message: String) = write("ok", message)

    fun trace(message: String) {
        if (verbose) write("trace", message)
    }

    fun format(time: LocalTime): String = time.format(clock)

    private fun write(level: String, message: String) {
        val now = LocalTime.now()
        runCatching { output(level, message) }
        for (listener in listeners) {
            // A broken log handler must never take the engine down.
            runCatching { listener(now, level, message) }
        }
    }
}

/** A multicast callback list, the Kotlin counterpart of a C# event. */
class Event<T> {
    private val handlers = CopyOnWriteArrayList<(T) -> Unit>()

    operator fun plusAssign(handler: (T) -> Unit) {
        handlers.add(handler)
    }

    operator fun minusAssign(handler: (T) -> Unit) {
        handlers.remove(handler)
    }

    operator fun invoke(value: T) {
        for (handler in handlers) handler(value)
    }
}

/** Monotonic milliseconds, the counterpart of Environment.TickCount64. */
fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
