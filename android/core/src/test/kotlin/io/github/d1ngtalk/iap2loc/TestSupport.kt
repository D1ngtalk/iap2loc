package io.github.d1ngtalk.iap2loc

import io.github.d1ngtalk.iap2loc.protocol.Csm
import io.github.d1ngtalk.iap2loc.protocol.CsmMessage
import io.github.d1ngtalk.iap2loc.protocol.Iap2Link
import io.github.d1ngtalk.iap2loc.transport.Transport
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One end of an in-memory duplex. Unlike PipedStream it does not care which thread writes. */
class MemoryTransport(override val describe: String) : Transport {
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private var pending = ByteArray(0)
    private var pendingOffset = 0
    lateinit var peer: MemoryTransport
    @Volatile private var closed = false

    companion object {
        private val EOF = ByteArray(0)

        fun pair(): Pair<MemoryTransport, MemoryTransport> {
            val a = MemoryTransport("memory-a")
            val b = MemoryTransport("memory-b")
            a.peer = b
            b.peer = a
            return a to b
        }
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (pendingOffset >= pending.size) {
            val next = inbox.take()
            if (next === EOF) return 0
            pending = next
            pendingOffset = 0
        }

        val size = minOf(count, pending.size - pendingOffset)
        pending.copyInto(buffer, offset, pendingOffset, pendingOffset + size)
        pendingOffset += size
        return size
    }

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        if (closed) throw java.io.IOException("closed")
        peer.inbox.put(buffer.copyOfRange(offset, offset + count))
    }

    override fun close() {
        if (closed) return
        closed = true
        inbox.put(EOF)
        peer.inbox.put(EOF)
    }
}

fun newP256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

/** Verifies a raw r||s signature over a 32-byte digest. */
fun verifyP1363(publicKey: PublicKey, digest: ByteArray, signature: ByteArray): Boolean {
    fun der(value: ByteArray): ByteArray {
        val integer = BigInteger(1, value).toByteArray()
        return byteArrayOf(0x02, integer.size.toByte()) + integer
    }

    val body = der(signature.copyOfRange(0, 32)) + der(signature.copyOfRange(32, 64))
    val encoded = byteArrayOf(0x30, body.size.toByte()) + body
    return Signature.getInstance("NONEwithECDSA").run {
        initVerify(publicKey)
        update(digest)
        verify(encoded)
    }
}

/**
 * A scripted iPhone on the other end of a link: answers identification and authentication the way
 * iOS does and records what the accessory sent.
 */
class SimulatedPhone(transport: Transport, private val publicKey: PublicKey?, private val periodMs: Int = 100) : AutoCloseable {
    val link = Iap2Link(transport, initiate = false, role = "phone")
    val received = LinkedBlockingQueue<CsmMessage>()
    val locations = LinkedBlockingQueue<String>()
    @Volatile var signatureValid: Boolean? = null
    private val challenge = ByteArray(32) { (it * 7 + 3).toByte() }

    init {
        link.ready += { link.send(0x1D00) }
        link.csmReceived += ::handle
    }

    fun start() = apply { link.start() }

    private fun handle(message: CsmMessage) {
        received.put(message)
        when (message.messageId) {
            0x1D01 -> {
                link.send(0x1D02)
                link.send(0xAA00)
            }

            0xAA01 -> link.send(0xAA02, Csm.param(0, challenge))
            0xAA03 -> {
                signatureValid = publicKey?.let { verifyP1363(it, challenge, message.parameters.getValue(0)) }
                link.send(0xAA05)
                subscribe()
            }

            0xFFFB -> for (sentence in message.parameters.values) {
                locations.put(String(sentence, Charsets.UTF_8).trimEnd('\u0000'))
            }
        }
    }

    fun subscribe() {
        val period = byteArrayOf(0, 0, (periodMs shr 8).toByte(), periodMs.toByte())
        link.send(
            0xFFFA,
            Csm.param(1, ByteArray(0)), Csm.param(2, ByteArray(0)),
            Csm.param(0x8001, period), Csm.param(0x8002, period),
        )
    }

    fun nextMessage(id: Int, timeoutMs: Long = 10_000): CsmMessage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val message = received.poll(left.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: throw AssertionError("no 0x%04X within $timeoutMs ms".format(id))
            if (message.messageId == id) return message
        }
    }

    fun nextLocation(timeoutMs: Long = 10_000): String =
        locations.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("no location sentence within $timeoutMs ms")

    override fun close() = link.close()
}

fun waitUntil(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $message")
        Thread.sleep(20)
    }
}
