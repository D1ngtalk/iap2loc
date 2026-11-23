package io.github.d1ngtalk.iap2loc.engine

import io.github.d1ngtalk.iap2loc.protocol.AccessoryProfile
import io.github.d1ngtalk.iap2loc.protocol.IdentitySource
import io.github.d1ngtalk.iap2loc.protocol.LocationTarget
import io.github.d1ngtalk.iap2loc.protocol.TargetState
import io.github.d1ngtalk.iap2loc.protocol.TargetUpdate
import io.github.d1ngtalk.iap2loc.web.obj
import kotlinx.serialization.json.JsonObject
import java.io.Closeable

/** Owns every running session. All of them share one target, so the map moves them together. */
class SessionManager(latitude: Double, longitude: Double, private val config: AppConfig) : Closeable {
    private val gate = Any()
    private val sessions = ArrayList<RunSession>()
    private var nextId = 0

    val target = LocationTarget(latitude, longitude)

    /** Identity handed to new sessions. Existing sessions keep the one they started with. */
    val profile: AccessoryProfile get() = config.effectiveProfile

    fun rotateIdentity(localAdapter: ByteArray?): AccessoryProfile = config.rotateIdentity(localAdapter)

    fun add(request: RunRequest): RunSession {
        val session = synchronized(gate) {
            RunSession("s${++nextId}", request.copy(profile = profile), target).also(sessions::add)
        }

        // Connecting can block for seconds; never hold the manager lock while doing it.
        session.start()
        return session
    }

    fun find(id: String): RunSession? = synchronized(gate) { sessions.firstOrNull { it.id == id } }

    fun list(): List<RunSession> = synchronized(gate) { sessions.toList() }

    fun remove(id: String): Boolean {
        val session = synchronized(gate) {
            val found = sessions.firstOrNull { it.id == id } ?: return false
            sessions.remove(found)
            found
        }

        session.stop()
        session.close()
        return true
    }

    fun stopAll() {
        val all = synchronized(gate) {
            val copy = sessions.toList()
            sessions.clear()
            copy
        }

        for (session in all) {
            session.stop()
            session.close()
        }
    }

    fun applyTarget(update: TargetUpdate, rate: Int?) {
        target.apply(update)
        if (rate == null) return
        for (session in list()) session.setInterval(rate)
    }

    /** The /api/state payload, field for field the desktop shape plus a few extra session fields. */
    fun snapshot(): JsonObject {
        val current = profile
        return obj(
            "warning" to IdentitySource.warning,
            "identity" to obj(
                "name" to current.name,
                "model" to current.model,
                "serial" to current.serial,
                "adapter" to current.adapterAddress,
                "path" to config.file.path,
            ),
            "target" to target.snapshot().toJson(),
            "sessions" to list().map { session ->
                obj(
                    "id" to session.id,
                    "label" to session.label,
                    "transport" to session.request.transport,
                    "address" to if (session.request.transport.equals("bt", ignoreCase = true)) {
                        session.request.mac
                    } else {
                        "${session.request.host}:${session.request.port}"
                    },
                    "running" to session.isRunning,
                    "link" to session.linkState,
                    "authenticated" to session.authenticated,
                    "subscribed" to session.subscribed,
                    "sent" to session.sent,
                    "error" to session.lastError,
                    "connecting" to session.isConnecting,
                    "stage" to session.stage.wire,
                    "identification" to session.identification?.name?.lowercase(),
                    "authenticationFailed" to session.authenticationFailed,
                    "hasIdentity" to session.hasIdentity,
                )
            },
        )
    }

    override fun close() = stopAll()
}

fun TargetState.toJson(): JsonObject = obj(
    "mode" to mode.wire,
    "latitude" to latitude,
    "longitude" to longitude,
    "altitude" to altitude,
    "accuracy" to accuracy,
    "speed" to speed,
    "bearing" to bearing,
    "jitterMeters" to jitterMeters,
    "jitterAccuracy" to jitterAccuracy,
    "accuracyMin" to accuracyMin,
    "accuracyMax" to accuracyMax,
    "jitterAltitude" to jitterAltitude,
    "altitudeMin" to altitudeMin,
    "altitudeMax" to altitudeMax,
    "resolvedLatitude" to resolvedLatitude,
    "resolvedLongitude" to resolvedLongitude,
    "resolvedAccuracy" to resolvedAccuracy,
    "resolvedAltitude" to resolvedAltitude,
    "routePoints" to routePoints,
    "routeLengthMeters" to routeLengthMeters,
    "pathTravelledMeters" to pathTravelledMeters,
    "pathSpeedMps" to pathSpeedMps,
    "pathLoop" to pathLoop,
    "pathDurationSeconds" to pathDurationSeconds,
    "pathTimed" to pathTimed,
    "quality" to quality,
    "satellites" to satellites,
)
