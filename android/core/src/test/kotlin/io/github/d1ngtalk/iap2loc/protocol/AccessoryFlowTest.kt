package io.github.d1ngtalk.iap2loc.protocol

import io.github.d1ngtalk.iap2loc.MemoryTransport
import io.github.d1ngtalk.iap2loc.SimulatedPhone
import io.github.d1ngtalk.iap2loc.newP256
import io.github.d1ngtalk.iap2loc.waitUntil
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole accessory against a scripted iPhone: link, identification, MFi, subscription, NMEA. */
class AccessoryFlowTest {
    private val identity = AccessoryIdentity("iAP2 Loc TEST", "Loc1,1", "carplay-loc", "LC0011223344", "0.1.0", "1.0")
    private val adapter = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)

    @Test
    fun authenticatesAndStreamsTheTarget() {
        val keys = newP256()
        val signer = MfiSigner.fromMaterial(keys.private.encoded, byteArrayOf(0x30, 0x01, 0x02))
        val (accessorySide, phoneSide) = MemoryTransport.pair()
        val target = LocationTarget(31.2304, 121.4737)
        val link = Iap2Link(accessorySide, initiate = true, role = "accessory")
        val session = LocationSession(link, target, identity, signer, 250, adapter)

        SimulatedPhone(phoneSide, keys.public, periodMs = 50).start().use { phone ->
            link.start()

            val identification = phone.nextMessage(0x1D01)
            assertEquals("iAP2 Loc TEST", String(identification.parameters.getValue(0)).trimEnd('\u0000'))
            assertTrue(17 in identification.parameters, "transport component present")
            assertTrue(22 in identification.parameters, "location component present")

            assertEquals(3, phone.nextMessage(0xAA01).parameters.getValue(0).size, "certificate passed through")
            phone.nextMessage(0xAA03)
            waitUntil(message = "authentication") { session.authenticated }
            assertEquals(true, phone.signatureValid, "challenge signature verifies with the public key")
            assertEquals(IdentificationState.ACCEPTED, session.identification)

            val first = phone.nextLocation()
            assertTrue(first.startsWith("\$GPGGA") || first.startsWith("\$GPRMC"), first)
            assertContains(phone.nextLocation() + first, "3113.8240,N,12128.4220,E")
            waitUntil(message = "subscribed") { session.isSubscribed }

            // A target change reaches the stream without reconnecting.
            target.apply(TargetUpdate(latitude = -33.86882, longitude = 151.20929))
            waitUntil(message = "new target in the stream") { phone.nextLocation().contains("3352.1292,S,15112.5574,E") }

            // 0xFFFC stops the stream.
            phone.link.send(0xFFFC)
            waitUntil(message = "unsubscribed") { !session.isSubscribed }
            Thread.sleep(200)
            phone.locations.clear()
            Thread.sleep(300)
            assertTrue(phone.locations.isEmpty(), "no frames after StopLocationInformation")
        }

        session.close()
        link.close()
    }

    @Test
    fun withoutIdentityTheLinkStillIdentifies() {
        val (accessorySide, phoneSide) = MemoryTransport.pair()
        val link = Iap2Link(accessorySide, initiate = true, role = "accessory")
        val session = LocationSession(link, LocationTarget(0.0, 0.0), identity, null, 250, null)

        SimulatedPhone(phoneSide, null).start().use { phone ->
            link.start()
            val identification = phone.nextMessage(0x1D01)
            assertTrue(17 !in identification.parameters, "no transport component without an adapter")
            waitUntil(message = "identification accepted") { session.identification == IdentificationState.ACCEPTED }
            Thread.sleep(300)
            assertEquals(false, session.authenticated)
        }

        session.close()
        link.close()
    }

    @Test
    fun manyMessagesSurviveSequenceWraparound() {
        val (a, b) = MemoryTransport.pair()
        val sender = Iap2Link(a, initiate = true, role = "sender")
        val receiver = Iap2Link(b, initiate = false, role = "receiver")
        val got = LinkedBlockingQueue<Int>()
        receiver.csmReceived += { got.put(it.parameters.getValue(0).let { data -> (data[0].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF) }) }
        val ready = LinkedBlockingQueue<Unit>()
        sender.ready += { ready.put(Unit) }
        receiver.start()
        sender.start()
        ready.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("link not ready")

        repeat(700) { index -> sender.send(0x7000, Csm.param(0, byteArrayOf((index shr 8).toByte(), index.toByte()))) }
        val order = List(700) { got.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("message $it lost") }
        assertEquals((0 until 700).toList(), order)
        assertEquals(LinkState.NORMAL, sender.linkState)

        sender.close()
        receiver.close()
    }
}
