package io.github.d1ngtalk.iap2loc.protocol

import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val notBefore: Instant,
    val notAfter: Instant,
    val serial: String,
) {
    fun isValidAt(now: Instant): Boolean = !now.isBefore(notBefore) && !now.isAfter(notAfter)
}

data class IdentityReport(
    val keyBits: Int,
    val certificates: List<CertificateInfo>,
    /** True or false when a certificate public key could be tested against the private key, else null. */
    val keyMatchesCertificate: Boolean?,
)

/**
 * The inspect-identity command, as a library: what the key is, which certificates the chain
 * holds and whether the private key belongs to one of them.
 */
object IdentityInspector {
    fun isPrivateKey(bytes: ByteArray): Boolean = runCatching { MfiSigner.importKey(bytes) }.isSuccess

    fun parseCertificates(bytes: ByteArray): List<X509Certificate> =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
            .filterIsInstance<X509Certificate>()

    fun inspect(pk8: ByteArray, p7b: ByteArray): IdentityReport {
        val key = MfiSigner.importKey(pk8)
        val certificates = parseCertificates(p7b)
        if (certificates.isEmpty()) throw IllegalArgumentException("certificate.p7b 中没有可解析的证书")

        val probe = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance("NONEwithECDSA").run {
            initSign(key)
            update(probe)
            sign()
        }

        val ecCertificates = certificates.filter { it.publicKey.algorithm == "EC" }
        val matches = if (ecCertificates.isEmpty()) {
            null
        } else {
            ecCertificates.any { certificate ->
                runCatching {
                    Signature.getInstance("NONEwithECDSA").run {
                        initVerify(certificate.publicKey)
                        update(probe)
                        verify(signature)
                    }
                }.getOrDefault(false)
            }
        }

        return IdentityReport(
            key.params.curve.field.fieldSize,
            certificates.map {
                CertificateInfo(
                    it.subjectX500Principal.name,
                    it.issuerX500Principal.name,
                    it.notBefore.toInstant(),
                    it.notAfter.toInstant(),
                    it.serialNumber.toString(16).uppercase(),
                )
            },
            matches,
        )
    }
}
