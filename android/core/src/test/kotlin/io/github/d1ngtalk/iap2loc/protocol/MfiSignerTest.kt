package io.github.d1ngtalk.iap2loc.protocol

import io.github.d1ngtalk.iap2loc.newP256
import io.github.d1ngtalk.iap2loc.verifyP1363
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MfiSignerTest {
    private val order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    @Test
    fun signaturesAreRawLowSAndVerify() {
        val keys = newP256()
        val signer = MfiSigner.fromMaterial(keys.private.encoded, ByteArray(4))
        repeat(40) { round ->
            val challenge = ByteArray(32) { (it * 31 + round).toByte() }
            val signature = signer.signChallenge(challenge)
            assertEquals(64, signature.size)
            assertTrue(BigInteger(1, signature.copyOfRange(32, 64)) <= order.shiftRight(1), "low S")
            assertTrue(verifyP1363(keys.public, challenge, signature), "verifies")
        }
    }

    @Test
    fun rejectsWrongChallengeLengthAndKeys() {
        val signer = MfiSigner.fromMaterial(newP256().private.encoded, ByteArray(0))
        assertFailsWith<IllegalStateException> { signer.signChallenge(ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { MfiSigner.fromMaterial(ByteArray(10), ByteArray(0)) }
    }

    @Test
    fun highSIsFlipped() {
        val high = ByteArray(64) { 0x7F }.also { order.subtract(BigInteger.ONE).toByteArray().takeLast(32).toByteArray().copyInto(it, 32) }
        val low = MfiSigner.normalizeLowS(high)
        assertEquals(BigInteger.ONE, BigInteger(1, low.copyOfRange(32, 64)))
        assertTrue(low.copyOfRange(0, 32).contentEquals(high.copyOfRange(0, 32)))
    }

    @Test
    fun inspectorReadsAPkcs7ChainAndMatchesTheKey() {
        val keys = newP256()
        val certificate = selfSigned(keys, "CN=Test Accessory, O=iap2loc tests")
        val p7b = pkcs7(certificate)

        val report = IdentityInspector.inspect(keys.private.encoded, p7b)
        assertEquals(256, report.keyBits)
        assertEquals(1, report.certificates.size)
        assertTrue(report.certificates[0].subject.contains("CN=Test Accessory"))
        assertTrue(report.certificates[0].isValidAt(Instant.now()))
        assertEquals(true, report.keyMatchesCertificate)

        val other = IdentityInspector.inspect(newP256().private.encoded, p7b)
        assertEquals(false, other.keyMatchesCertificate)

        assertTrue(IdentityInspector.isPrivateKey(keys.private.encoded))
        assertTrue(!IdentityInspector.isPrivateKey(p7b))
        assertEquals(1, IdentityInspector.parseCertificates(p7b).size)
    }

    companion object {
        fun selfSigned(keys: KeyPair, subject: String): X509Certificate {
            val now = Instant.now()
            val builder = X509v3CertificateBuilder(
                X500Name(subject), BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(365, ChronoUnit.DAYS)),
                X500Name(subject), org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keys.public.encoded),
            )
            val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))
            return JcaX509CertificateConverter().getCertificate(holder)
        }

        /** A certs-only PKCS#7 (degenerate SignedData), the shape of certificate.p7b. */
        fun pkcs7(certificate: X509Certificate): ByteArray {
            val generator = CMSSignedDataGenerator()
            generator.addCertificates(JcaCertStore(listOf(certificate)))
            return generator.generate(CMSProcessableByteArray(ByteArray(0))).encoded
        }
    }
}
