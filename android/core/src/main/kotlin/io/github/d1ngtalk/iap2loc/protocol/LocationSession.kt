package io.github.d1ngtalk.iap2loc.protocol

import io.github.d1ngtalk.iap2loc.Event
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.monotonicMillis
import java.io.Closeable
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Where identification stands, for the UI: the desktop only logs it. */
enum class IdentificationState { PENDING, ACCEPTED, REJECTED }

/**
 * The accessory brain: answers identification and MFi authentication, then streams NMEA
 * LocationInformation at the requested rate whenever the phone has subscribed. The target is a
 * shared, mutable object so the map can be clicked while the session stays connected.
 * A port of Protocol/LocationSession.cs.
 */
class LocationSession(
    private val link: Iap2Link,
    private val target: LocationTarget,
    private val identity: AccessoryIdentity,
    private val signer: MfiSigner?,
    intervalMs: Int,
    private val bluetoothAdapter: ByteArray? = null,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) },
) : Closeable {
    private companion object {
        /** A displacement beyond this is a new target, not jitter or a path step. */
        const val JUMP_THRESHOLD_METERS = 100.0

        /** Reported on the jump frame: about 900 km/h, an airliner rather than a teleport. */
        const val JUMP_SPEED_METERS_PER_SECOND = 250.0
    }

    /** What the phone asked for in 0xFFFA: sentence ids 1/2 and optional periods. */
    private class Subscription(val sentences: Set<Int>, val requestedMs: Map<Int, Int>)

    private val timerGate = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "iap2-push-${link.role}").apply { isDaemon = true }
    }
    private var timerActive = false
    private var future: ScheduledFuture<*>? = null

    @Volatile private var subscribed = false
    @Volatile private var subscription: Subscription? = null
    private val lastSent = HashMap<Int, Long>()
    private val pushGate = Any()
    private var lastFixLatitude = 0.0
    private var lastFixLongitude = 0.0
    private var hasLastFix = false
    @Volatile private var intervalMs = maxOf(10, intervalMs)
    private val pushed = AtomicInteger()
    @Volatile private var disposed = false
    private val onCsm: (CsmMessage) -> Unit = ::handle

    /** Raised on 0x1D02 (true) or 0x1D03 (false, with the offending parameters). */
    val identificationFinished = Event<Pair<Boolean, String>>()

    @Volatile var identification = IdentificationState.PENDING
        private set

    @Volatile var authenticated = false
        private set

    /** The phone answered 0xAA04: it rejected the certificate or the signature. */
    @Volatile var authenticationFailed = false
        private set

    val isSubscribed: Boolean get() = subscribed

    val messagesPushed: Int get() = pushed.get()

    init {
        link.csmReceived += onCsm
    }

    fun setInterval(milliseconds: Int) {
        val next = maxOf(10, milliseconds)
        synchronized(timerGate) {
            if (next == intervalMs) return
            intervalMs = next
            if (timerActive) schedule(0)
        }
    }

    private fun handle(message: CsmMessage) {
        val parameters = message.parameters
        try {
            when (message.messageId) {
                0x1D00 -> {
                    Log.step(
                        "rx 0x1D00 StartIdentification → tx 0x1D01 " +
                            "(transport 17 ${if (bluetoothAdapter == null) "absent" else "present"})",
                    )
                    link.send(0x1D01, Identification.build(identity, bluetoothAdapter))
                }

                0x1D02 -> {
                    identification = IdentificationState.ACCEPTED
                    Log.ok("rx 0x1D02 IdentificationAccepted")
                    identificationFinished(true to "")
                }

                0x1D03 -> {
                    identification = IdentificationState.REJECTED
                    val detail = describe(parameters)
                    Log.error("rx 0x1D03 IdentificationRejected, offending parameters: $detail${hint(parameters)}")
                    identificationFinished(false to detail)
                }

                0xAA00 -> {
                    if (signer == null) {
                        Log.warn("rx 0xAA00 RequestAuthenticationCertificate, but no identity was given")
                    } else {
                        Log.step("rx 0xAA00 RequestAuthenticationCertificate → tx 0xAA01 (${signer.certificate.size} bytes)")
                        link.send(0xAA01, Csm.param(0, signer.certificate))
                    }
                }

                0xAA02 -> {
                    val challenge = parameters[0]
                    when {
                        signer == null ->
                            Log.warn("rx 0xAA02 challenge, but no identity was given; authentication cannot succeed")

                        challenge == null || challenge.isEmpty() -> Log.error("rx 0xAA02 without a challenge in parameter 0")

                        else -> {
                            val signature = signer.signChallenge(challenge)
                            Log.step("rx 0xAA02 challenge (${challenge.size} B) → tx 0xAA03 signature (${signature.size} B)")
                            link.send(0xAA03, Csm.param(0, signature))
                        }
                    }
                }

                0xAA04 -> {
                    authenticationFailed = true
                    Log.error("rx 0xAA04 AuthenticationFailed: the phone rejected the certificate or the signature")
                }

                0xAA05 -> {
                    authenticated = true
                    Log.ok("rx 0xAA05 AuthenticationSucceeded")
                }

                0xFFFA -> {
                    val parsed = parseSubscription(parameters)
                    synchronized(pushGate) {
                        subscription = parsed
                        lastSent.clear()
                    }
                    subscribed = true
                    Log.ok("rx 0xFFFA StartLocationInformation, ${describe(parsed)}")
                    Log.info("0xFFFA raw parameters: ${describeRaw(parameters)}")
                    startTimer()
                }

                0xFFFC -> {
                    subscribed = false
                    subscription = null
                    Log.info("rx 0xFFFC StopLocationInformation")
                    stopTimer()
                }

                0xEA00, 0xEA01 ->
                    Log.trace("rx 0x${hex4(message.messageId)} external accessory protocol session (ignored)")

                else -> Log.trace("rx 0x${hex4(message.messageId)} parameters=[${describe(parameters)}]")
            }
        } catch (ex: Exception) {
            Log.error("handling 0x${hex4(message.messageId)} failed: ${ex.message}")
        }
    }

    private fun startTimer() = synchronized(timerGate) {
        if (!timerActive) {
            timerActive = true
            val state = target.snapshot()
            Log.info(
                if (state.mode == TargetMode.PATH) {
                    "streaming ${plannedInterval()} ms NMEA along ${state.routePoints} points " +
                        if (state.pathTimed) {
                            "(${Nmea.fixed(state.routeLengthMeters / 1000, 2)} km over " +
                                "${hms(state.pathDurationSeconds)} of GPX time)"
                        } else {
                            "(${Nmea.fixed(state.routeLengthMeters / 1000, 2)} km at ${Nmea.fixed(state.pathSpeedMps * 3.6, 1)} km/h)"
                        }
                } else {
                    "streaming ${plannedInterval()} ms NMEA for ${Nmea.fixed(state.latitude, 6)},${Nmea.fixed(state.longitude, 6)}"
                },
            )
        }

        // A repeated 0xFFFA restarts the cadence right away.
        schedule(0)
    }

    private fun stopTimer() = synchronized(timerGate) {
        timerActive = false
        future?.cancel(false)
        future = null
    }

    /** One pending push at a time, like a one-shot System.Threading.Timer that is re-armed. */
    private fun schedule(delayMs: Int) {
        if (!timerActive || disposed) return
        future?.cancel(false)
        future = runCatching { scheduler.schedule(::push, delayMs.toLong(), TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun push() {
        try {
            if (disposed || !subscribed || link.linkState != LinkState.NORMAL) return

            synchronized(pushGate) {
                val fix = target.next()
                val (speed, bearing) = motionFor(fix)
                val current = subscription

                val nmea = Nmea.encode(
                    fix.latitude,
                    fix.longitude,
                    fix.altitudeMeters,
                    bearing,
                    speed,
                    fix.accuracyMeters,
                    fix.quality,
                    fix.satellites,
                    clock(),
                )

                val parameters = ArrayList<ByteArray>()
                val sent = ArrayList<Int>()
                val tick = monotonicMillis()

                for (sentence in nmea.split("\r\n").filter { it.isNotEmpty() }) {
                    val id = sentenceId(sentence)
                    if (current != null && current.sentences.isNotEmpty() && id !in current.sentences) continue
                    if (!due(current, id, tick)) continue

                    parameters.add(Csm.param(0, (sentence + "\r\n\u0000").toByteArray(Charsets.UTF_8)))
                    sent.add(id)
                }

                if (parameters.isEmpty()) return

                link.send(0xFFFB, *parameters.toTypedArray())

                // Only RMC carries speed and course, so the jump marker is consumed when RMC goes out.
                if (2 in sent) {
                    lastFixLatitude = fix.latitude
                    lastFixLongitude = fix.longitude
                    hasLastFix = true
                }

                for (id in sent) lastSent[id] = tick

                val count = pushed.incrementAndGet()
                if (count == 1 || count % 30 == 0) {
                    Log.trace("tx 0xFFFB #$count ${parameters.size} sentence(s), first: ${nmea.split("\r\n")[0]}")
                }
            }
        } catch (ex: Exception) {
            Log.warn("sending 0xFFFB failed: ${ex.message}")
        } finally {
            synchronized(timerGate) { schedule(nextDelay()) }
        }
    }

    /**
     * A 1000 km jump with GPRMC speed 0 is a contradiction, and a receiver is likely to throw the fix
     * away. On the frame that jumps, report a cruise speed and the bearing towards the new point; the
     * next frame falls back to the target's own speed (usually 0), which is what a parked car looks like.
     */
    private fun motionFor(fix: LocationFix): Pair<Double?, Double?> {
        if (!hasLastFix) return fix.speedMetersPerSecond to fix.bearingDegrees

        val from = GeoPoint(lastFixLatitude, lastFixLongitude)
        val to = GeoPoint(fix.latitude, fix.longitude)
        val distance = Geo.distance(from, to)
        if (distance < JUMP_THRESHOLD_METERS) return fix.speedMetersPerSecond to fix.bearingDegrees

        val bearing = Geo.bearing(from, to)
        Log.info(
            "位置跳变 ${Nmea.fixed(distance / 1000, 1)} km，该帧按 ${Nmea.fixed(JUMP_SPEED_METERS_PER_SECOND, 0)} m/s、" +
                "航向 ${Nmea.fixed(bearing, 0)}° 上报",
        )
        return JUMP_SPEED_METERS_PER_SECOND to bearing
    }

    /** A sentence is due when its own period elapsed; the device period wins when it asked for one. */
    private fun due(subscription: Subscription?, sentenceId: Int, now: Long): Boolean {
        val last = lastSent[sentenceId] ?: return true
        return now - last >= effectiveInterval(subscription, sentenceId)
    }

    private fun effectiveInterval(subscription: Subscription?, sentenceId: Int): Int {
        val requested = subscription?.requestedMs?.get(sentenceId)
        return if (requested != null && requested > 0) requested else intervalMs
    }

    /** Logging only: the cadence the device asked for, or our own interval. */
    private fun plannedInterval(): Int {
        val current = subscription ?: return intervalMs
        return trackedSentences(current).minOfOrNull { effectiveInterval(current, it) } ?: intervalMs
    }

    /** Wake up exactly when the next sentence is due. */
    private fun nextDelay(): Int {
        val current = subscription ?: return intervalMs
        val now = monotonicMillis()
        val soonest = synchronized(pushGate) {
            trackedSentences(current).minOfOrNull { id ->
                val interval = effectiveInterval(current, id)
                val elapsed = lastSent[id]?.let { now - it } ?: interval.toLong()
                interval - elapsed.toInt()
            }
        }

        return if (soonest == null) intervalMs else maxOf(10, soonest)
    }

    private fun trackedSentences(subscription: Subscription): List<Int> =
        if (subscription.sentences.isNotEmpty()) subscription.sentences.toList() else listOf(1, 2)

    private fun describe(parameters: Map<Int, ByteArray>): String =
        parameters.entries.joinToString(", ") { "${it.key}:${it.value.size}B" }

    /**
     * Every parameter the phone actually sent, including ids the subscription parser does not know.
     * Periods are decoded because "0x8001=250ms" is easier to read than four hex bytes.
     */
    private fun describeRaw(parameters: Map<Int, ByteArray>): String =
        if (parameters.isEmpty()) {
            "none"
        } else {
            parameters.entries.sortedBy { it.key }.joinToString(", ") { (key, value) ->
                when {
                    (key == 0x8001 || key == 0x8002) && value.size == 4 -> "0x${hex4(key)}=${value.u32(0)}ms"
                    value.isEmpty() -> "0x${hex4(key)}(empty)"
                    else -> "0x${hex4(key)}(${value.size}B)"
                }
            }
        }

    /**
     * 0xFFFA carries the sentence ids it wants as empty parameters 1 (GGA) and 2 (RMC);
     * 0x8001 / 0x8002 are the matching periods in milliseconds. A period the device asks for
     * overrides the configured interval; the configured value only applies when it asks for none.
     */
    private fun parseSubscription(parameters: Map<Int, ByteArray>): Subscription {
        val sentences = HashSet<Int>()
        val periods = HashMap<Int, Int>()

        for ((id, data) in parameters) {
            if ((id == 1 || id == 2) && data.isEmpty()) {
                sentences.add(id)
            } else if ((id == 0x8001 || id == 0x8002) && data.size == 4) {
                val milliseconds = data.u32(0)
                if (milliseconds > 0) periods[id - 0x8000] = milliseconds
            }
        }

        return Subscription(sentences, periods)
    }

    private fun describe(subscription: Subscription): String {
        val known = listOf(1 to "gga", 2 to "rmc")
        val sentences = if (subscription.sentences.isEmpty()) {
            "all"
        } else {
            known.filter { it.first in subscription.sentences }.joinToString(",") { it.second }
        }
        val periods = if (subscription.requestedMs.isEmpty()) {
            "none"
        } else {
            known.filter { it.first in subscription.requestedMs }
                .joinToString(",") { "${it.second} ${subscription.requestedMs[it.first]}ms" }
        }

        return "sentences=[$sentences], requested=[$periods], sending every ${plannedInterval()} ms"
    }

    private fun sentenceId(sentence: String): Int = when {
        sentence.startsWith("\$GPGGA") || sentence.startsWith("\$GNGGA") -> 1
        sentence.startsWith("\$GPRMC") || sentence.startsWith("\$GNRMC") -> 2
        else -> 0
    }

    /** Maps the parameter ids iOS quotes in 0x1D03 to the thing that is usually wrong. */
    private fun hint(parameters: Map<Int, ByteArray>): String {
        val hints = buildList {
            if (6 in parameters) add("6 = declared messages sent, iOS refuses one of them")
            if (7 in parameters) add("7 = declared messages received, iOS refuses one of them")
            if (16 in parameters) add("16 = USB transport component")
            if (17 in parameters) add("17 = Bluetooth transport component was missing or malformed")
            if (22 in parameters) add("22 = location component, check the nested parameters")
            if (24 in parameters) add("24 = Wi-Fi transport component")
        }
        return if (hints.isEmpty()) "" else " (" + hints.joinToString("; ") + ")"
    }

    private fun hex4(value: Int) = "%04X".format(value)

    private fun hms(seconds: Double): String {
        val total = seconds.toLong()
        return "%02d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
    }

    override fun close() {
        disposed = true
        stopTimer()
        scheduler.shutdownNow()
        link.csmReceived -= onCsm
    }
}
