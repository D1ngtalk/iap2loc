package io.github.d1ngtalk.iap2loc.protocol

import io.github.d1ngtalk.iap2loc.transport.TcpTransport
import io.github.d1ngtalk.iap2loc.waitUntil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Kotlin accessory against the desktop C# link layer playing the iPhone
 * (android/tools/golden-vectors, `dotnet run -- phone PORT KEY.pk8`). Skipped unless
 * IAP2LOC_INTEROP_PORT and IAP2LOC_INTEROP_KEY are set; see android/tools/golden-vectors/README.md.
 */
class CSharpInteropTest {
    @Test
    fun talksToTheCSharpPhone() {
        val port = System.getenv("IAP2LOC_INTEROP_PORT")?.toIntOrNull() ?: return
        val key = File(System.getenv("IAP2LOC_INTEROP_KEY") ?: return)

        val signer = MfiSigner.fromMaterial(key.readBytes(), ByteArray(16) { it.toByte() })
        val link = Iap2Link(TcpTransport.connect("127.0.0.1", port), initiate = true, role = "accessory")
        val identity = AccessoryIdentity("iAP2 Loc INTR", "Loc1,1", "carplay-loc", "LC00000000AB", "0.1.0", "1.0")
        val session = LocationSession(link, LocationTarget(31.2304, 121.4737), identity, signer, 250, byteArrayOf(2, 0, 0, 0, 0, 1))
        link.start()

        waitUntil(30_000, "C# phone to subscribe and receive frames") { session.isSubscribed && session.messagesPushed >= 10 }
        assertTrue(session.authenticated)
        session.close()
        link.close()
    }
}
