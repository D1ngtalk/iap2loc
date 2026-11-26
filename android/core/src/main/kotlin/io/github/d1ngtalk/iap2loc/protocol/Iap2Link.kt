package io.github.d1ngtalk.iap2loc.protocol

import io.github.d1ngtalk.iap2loc.Event
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.monotonicMillis
import io.github.d1ngtalk.iap2loc.transport.Transport
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LinkState(val wire: String) {
    DETECT("Detect"),
    NEGOTIATE("Negotiate"),
    NORMAL("Normal"),
    DEAD("Dead"),
}

/** One control-session message as the phone sent it. */
class CsmMessage(val messageId: Int, val parameters: Map<Int, ByteArray>)

/**
 * iAP2 link layer: detect marker, rate negotiation, sequence/acknowledge, retransmission and
 * control-session (CSM) framing. A line-by-line port of Protocol/Iap2Link.cs.
 */
class Iap2Link(private val transport: Transport, private val initiate: Boolean, val role: String) : Closeable {
    companion object {
        const val SESSION_CONTROL = 10

        private const val CONTROL_ACK = 0x40
        private const val CONTROL_RETRANSMIT = 0x20
        private const val CONTROL_SYNC = 0x80
        private const val CONTROL_RESET = 0x10

        internal val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)

        // version, maxOutgoing, maxLength (65535), timeout (4000 ms), retries, then session TLVs.
        internal val SYNC_PAYLOAD = intArrayOf(
            1, 4, 255, 255, 15, 160, 1, 244, 4, 3, 10, 0, 2, 11, 2, 1, 12, 1, 2,
        ).map { it.toByte() }.toByteArray()

        internal fun checksum(data: ByteArray, offset: Int, count: Int): Byte {
            var sum = 0
            for (i in 0 until count) sum += data[offset + i].toInt() and 0xFF
            return (-(sum and 0xFF)).toByte()
        }

        private fun checksumIsZero(data: ByteArray, offset: Int, count: Int): Boolean {
            var sum = 0
            for (i in 0 until count) sum += data[offset + i].toInt() and 0xFF
            return (sum and 0xFF) == 0
        }
    }

    private val gate = Any()
    private val reader = Thread(::readLoop, "iap2-rx-$role").apply { isDaemon = true }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "iap2-tick-$role").apply { isDaemon = true }
    }

    private val pending = ArrayList<Pending>()
    private val queue = ArrayDeque<Queued>()
    private val outOfOrder = HashMap<Int, Queued>()

    private var readBuffer = ByteArray(64 * 1024)
    private var readLength = 0
    private var csmBuffer = ByteArray(64 * 1024)
    private var csmLength = 0

    private var state = LinkState.DETECT
    private var started = false
    private var sequence = 0
    private var acknowledge = 0
    private var maxOutgoing = 4
    private var maxLength = 4000
    private var timeoutMs = 500
    private var maxRetries = 4
    private var startedAt = 0L
    private var lastMarkerAt = 0L
    private var lastSyncAt = 0L
    private var disposed = false

    @Volatile var bytesIn = 0L
        private set

    @Volatile var bytesOut = 0L
        private set

    @Volatile var csmMessages = 0
        private set

    val csmReceived = Event<CsmMessage>()
    val ready = Event<Unit>()
    val failed = Event<String>()

    val linkState: LinkState get() = synchronized(gate) { state }

    init {
        timer.scheduleAtFixedRate({
            try {
                tick()
            } catch (ex: Exception) {
                fail("transport write failed: ${ex.message}")
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
    }

    fun start() {
        synchronized(gate) {
            started = true
            startedAt = monotonicMillis()
            if (initiate) {
                writeRaw(MARKER)
                lastMarkerAt = startedAt
            }
        }

        reader.start()
    }

    fun send(messageId: Int, vararg parameters: ByteArray) = sendRaw(Csm.encode(messageId, *parameters), SESSION_CONTROL)

    fun sendRaw(data: ByteArray, session: Int) = synchronized(gate) {
        if (state == LinkState.DEAD) throw IllegalStateException("iAP2 link is closed")

        val chunkSize = maxOf(64, maxLength - 10)
        var offset = 0
        while (offset < data.size) {
            val size = minOf(chunkSize, data.size - offset)
            queue.addLast(Queued(session, data.copyOfRange(offset, offset + size)))
            offset += size
        }

        flush()
    }

    override fun close() {
        synchronized(gate) {
            if (disposed) return
            disposed = true
            state = LinkState.DEAD
        }

        timer.shutdownNow()
        runCatching { transport.close() }
    }

    private fun readLoop() {
        val buffer = ByteArray(8192)
        while (true) {
            val read = try {
                transport.read(buffer, 0, buffer.size)
            } catch (ex: Exception) {
                fail("transport read failed: ${ex.message}")
                return
            }

            if (read <= 0) {
                fail("transport closed by the peer")
                return
            }

            try {
                synchronized(gate) {
                    bytesIn += read
                    append(buffer, read)
                    process()
                }
            } catch (ex: Exception) {
                // A failed write while answering must end the link, not the reader thread (or the app).
                fail("transport write failed: ${ex.message}")
                return
            }
        }
    }

    private fun append(data: ByteArray, count: Int) {
        if (readLength + count > readBuffer.size) {
            readBuffer = readBuffer.copyOf(maxOf(readBuffer.size * 2, readLength + count))
        }

        data.copyInto(readBuffer, readLength, 0, count)
        readLength += count
    }

    private fun startsWithMarker(): Boolean =
        readLength >= MARKER.size && (MARKER.indices).all { readBuffer[it] == MARKER[it] }

    private fun process() {
        if (state == LinkState.DETECT) {
            if (readLength < MARKER.size) return

            if (!startsWithMarker()) {
                fail("the device did not answer the iAP2 detect marker")
                return
            }

            consumeRead(MARKER.size)
            if (!initiate) {
                // The peer opened the conversation; answer with our own marker before negotiating.
                writeRaw(MARKER)
            }

            state = LinkState.NEGOTIATE
            startedAt = monotonicMillis()
            Log.trace("[$role] marker exchanged, negotiating")
            sendSync()
        }

        while (state != LinkState.DEAD && readLength >= 9) {
            if (startsWithMarker()) {
                consumeRead(MARKER.size)
                continue
            }

            if (readBuffer.u8(0) != 0xFF || readBuffer.u8(1) != 0x5A) {
                consumeRead(1)
                continue
            }

            val length = readBuffer.u16(2)
            if (length < 9) {
                fail("invalid iAP2 packet length $length")
                return
            }

            if (readLength < length) return

            val packet = readBuffer.copyOfRange(0, length)
            consumeRead(length)
            handle(packet)
        }
    }

    private fun handle(packet: ByteArray) {
        if (!checksumIsZero(packet, 0, 9)) {
            Log.trace("[$role] header checksum mismatch, dropped")
            return
        }

        val payloadLength = packet.size - 10
        if (payloadLength > 0 && !checksumIsZero(packet, 9, payloadLength + 1)) {
            Log.trace("[$role] payload checksum mismatch, dropped")
            return
        }

        val control = packet.u8(4)
        val sequence = packet.u8(5)
        val acknowledge = packet.u8(6)
        val session = packet.u8(7)
        val payload = if (payloadLength > 0) packet.copyOfRange(9, 9 + payloadLength) else null

        if (Log.verbose) {
            Log.trace("[$role] rx ctrl=0x${"%02X".format(control)} seq=$sequence ack=$acknowledge sid=$session len=${maxOf(0, payloadLength)}")
        }

        if (control and CONTROL_RESET != 0) {
            fail("the device reset the iAP2 link")
            return
        }

        if (control and CONTROL_SYNC != 0) {
            if (payload == null || payload.size < 10 || payload[0].toInt() != 1) {
                fail("invalid iAP2 sync parameters")
                return
            }

            maxOutgoing = payload.u8(1).coerceIn(1, 64)
            maxLength = maxOf(64, payload.u16(2))
            timeoutMs = maxOf(50, payload.u16(4))
            if (payload.size > 8) maxRetries = maxOf(1, payload.u8(8))

            this.acknowledge = sequence
            sendAck()
            Log.trace("[$role] sync: outgoing=$maxOutgoing len=$maxLength timeout=$timeoutMs")
        }

        if (control and CONTROL_ACK != 0) {
            completePending(acknowledge)
            if (state == LinkState.NEGOTIATE) {
                state = LinkState.NORMAL
                Log.trace("[$role] link ready")
                ready(Unit)
            }

            flush()
        }

        if (control and CONTROL_RETRANSMIT != 0 && payload != null) {
            for (missing in payload) {
                val entry = pending.firstOrNull { it.sequence == (missing.toInt() and 0xFF) }
                if (entry != null) writePacket(CONTROL_ACK, entry.sequence, this.acknowledge, entry.session, entry.data)
            }

            return
        }

        if (control and CONTROL_ACK.inv() == 0 && payload != null) acceptSequence(sequence, session, payload)
    }

    private fun acceptSequence(sequence: Int, session: Int, payload: ByteArray) {
        val distance = (sequence - acknowledge) and 0xFF
        if (distance == 1) {
            acknowledge = sequence
            deliver(session, payload)

            while (true) {
                val next = outOfOrder.remove((acknowledge + 1) and 0xFF) ?: break
                acknowledge = (acknowledge + 1) and 0xFF
                deliver(next.session, next.data)
            }

            sendAck()
        } else if (distance in 2..127 && outOfOrder.size < 64) {
            outOfOrder[sequence] = Queued(session, payload)
            val missing = ArrayList<Byte>()
            var expected = (acknowledge + 1) and 0xFF
            while (expected != sequence) {
                missing.add(expected.toByte())
                expected = (expected + 1) and 0xFF
            }

            writePacket(CONTROL_RETRANSMIT, this.sequence, acknowledge, 0, missing.toByteArray())
        }
    }

    private fun deliver(session: Int, payload: ByteArray) {
        if (session != SESSION_CONTROL) return

        if (csmLength + payload.size > csmBuffer.size) {
            csmBuffer = csmBuffer.copyOf(maxOf(csmBuffer.size * 2, csmLength + payload.size))
        }

        payload.copyInto(csmBuffer, csmLength)
        csmLength += payload.size

        while (csmLength >= 6) {
            if (csmBuffer.u8(0) != 0x40 || csmBuffer.u8(1) != 0x40) {
                fail("control session lost the 0x4040 marker")
                return
            }

            val length = csmBuffer.u16(2)
            if (length < 6) {
                fail("invalid control message length $length")
                return
            }

            if (csmLength < length) return

            val messageId = csmBuffer.u16(4)
            val parameters = try {
                Csm.parseParams(csmBuffer, 6, length - 6)
            } catch (ex: Exception) {
                fail("invalid control parameters: ${ex.message}")
                return
            }

            csmBuffer.copyInto(csmBuffer, 0, length, csmLength)
            csmLength -= length
            csmMessages++
            csmReceived(CsmMessage(messageId, parameters))
        }
    }

    private fun completePending(acknowledge: Int) {
        pending.removeAll { ((acknowledge - it.sequence) and 0xFF) < 128 }
    }

    private fun sendAck() = writePacket(CONTROL_ACK, sequence, acknowledge, 0, null)

    private fun sendSync() {
        lastSyncAt = monotonicMillis()
        writePacket(CONTROL_SYNC, sequence, acknowledge, 0, SYNC_PAYLOAD)
    }

    private fun flush() {
        if (state != LinkState.NORMAL) return

        while (queue.isNotEmpty() && pending.size < maxOutgoing) {
            val item = queue.removeFirst()
            sequence = (sequence + 1) and 0xFF
            writePacket(CONTROL_ACK, sequence, acknowledge, item.session, item.data)
            pending.add(Pending(sequence, item.session, item.data, monotonicMillis()))
        }
    }

    private fun writePacket(control: Int, sequence: Int, acknowledge: Int, session: Int, payload: ByteArray?) {
        val length = if (payload == null) 9 else payload.size + 10
        val packet = ByteArray(length)
        packet[0] = 0xFF.toByte()
        packet[1] = 0x5A
        packet[2] = (length shr 8).toByte()
        packet[3] = length.toByte()
        packet[4] = control.toByte()
        packet[5] = sequence.toByte()
        packet[6] = acknowledge.toByte()
        packet[7] = session.toByte()
        packet[8] = checksum(packet, 0, 8)

        if (payload != null) {
            payload.copyInto(packet, 9)
            packet[length - 1] = checksum(payload, 0, payload.size)
        }

        writeRaw(packet)
    }

    private fun writeRaw(data: ByteArray) {
        transport.write(data, 0, data.size)
        bytesOut += data.size
    }

    private fun tick() = synchronized(gate) {
        if (!started || state == LinkState.DEAD || disposed) return

        val now = monotonicMillis()
        when (state) {
            LinkState.DETECT -> {
                if (now - startedAt > 15_000) {
                    fail("iAP2 detect timed out")
                    return
                }

                if (initiate && now - lastMarkerAt > 1_000) {
                    writeRaw(MARKER)
                    lastMarkerAt = now
                }
            }

            LinkState.NEGOTIATE -> {
                if (now - startedAt > 20_000) {
                    fail("iAP2 negotiation timed out")
                    return
                }

                if (now - lastSyncAt > 500) sendSync()
            }

            LinkState.NORMAL -> {
                for (entry in pending.toList()) {
                    if (now - entry.sentAt < timeoutMs) continue

                    if (++entry.retries > maxRetries) {
                        fail("iAP2 acknowledge timed out")
                        return
                    }

                    entry.sentAt = now
                    writePacket(CONTROL_ACK, entry.sequence, acknowledge, entry.session, entry.data)
                }
            }

            LinkState.DEAD -> Unit
        }
    }

    private fun fail(reason: String) {
        synchronized(gate) {
            if (state == LinkState.DEAD) return
            state = LinkState.DEAD
        }

        failed(reason)
    }

    private fun consumeRead(count: Int) {
        readBuffer.copyInto(readBuffer, 0, count, readLength)
        readLength -= count
    }

    private class Pending(val sequence: Int, val session: Int, val data: ByteArray, var sentAt: Long) {
        var retries = 0
    }

    private class Queued(val session: Int, val data: ByteArray)
}
