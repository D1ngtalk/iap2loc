package io.github.d1ngtalk.iap2loc.protocol

import java.io.File
import java.io.FileNotFoundException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * MFi authentication 3.0: the accessory holds a P-256 key (identity.pk8) and its certificate
 * chain (certificate.p7b). The phone sends a 32-byte challenge; the accessory answers with a
 * 64-byte r||s ECDSA signature over that challenge.
 *
 * This project does not ship any authentication material.
 */
class MfiSigner private constructor(private val key: ECPrivateKey, val certificate: ByteArray) {
    companion object {
        const val KEY_FILE = "identity.pk8"
        const val CERTIFICATE_FILE = "certificate.p7b"

        private val p256Order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

        fun load(directory: File): MfiSigner {
            val keyFile = File(directory, KEY_FILE)
            val certificateFile = File(directory, CERTIFICATE_FILE)
            if (!keyFile.isFile || !certificateFile.isFile) {
                throw FileNotFoundException("MFi material missing: expected $keyFile and $certificateFile")
            }

            return fromMaterial(keyFile.readBytes(), certificateFile.readBytes())
        }

        /** Builds a signer from in-memory material. */
        fun fromMaterial(pk8: ByteArray, certificate: ByteArray): MfiSigner {
            val key = importKey(pk8)
            val bits = key.params.curve.field.fieldSize
            if (bits != 256) throw IllegalArgumentException("MFi 3.0 expects a P-256 key, got $bits bits")
            return MfiSigner(key, certificate)
        }

        fun importKey(pk8: ByteArray): ECPrivateKey = try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pk8)) as ECPrivateKey
        } catch (ex: Exception) {
            throw IllegalArgumentException("cannot import identity.pk8: ${ex.message}", ex)
        }

        /** DER SEQUENCE { INTEGER r, INTEGER s } to fixed-width r||s. */
        internal fun derToP1363(der: ByteArray, width: Int): ByteArray {
            var offset = 0
            fun readLength(): Int {
                val first = der[offset++].toInt() and 0xFF
                if (first < 0x80) return first
                var length = 0
                repeat(first and 0x7F) { length = (length shl 8) or (der[offset++].toInt() and 0xFF) }
                return length
            }

            fun readInteger(): ByteArray {
                require(der[offset++].toInt() == 0x02) { "ECDSA signature: INTEGER expected" }
                val length = readLength()
                val value = der.copyOfRange(offset, offset + length)
                offset += length
                return value
            }

            require(der[offset++].toInt() == 0x30) { "ECDSA signature: SEQUENCE expected" }
            readLength()
            val result = ByteArray(width * 2)
            for ((slot, integer) in listOf(readInteger(), readInteger()).withIndex()) {
                val trimmed = integer.dropWhile { it.toInt() == 0 }.toByteArray()
                require(trimmed.size <= width) { "ECDSA signature component too long" }
                trimmed.copyInto(result, slot * width + width - trimmed.size)
            }

            return result
        }

        internal fun normalizeLowS(signature: ByteArray): ByteArray {
            if (signature.size != 64) return signature

            val s = BigInteger(1, signature.copyOfRange(32, 64))
            if (s <= p256Order.shiftRight(1)) return signature

            val normalized = p256Order.subtract(s).toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
            val result = signature.copyOf()
            result.fill(0, 32, 64)
            normalized.copyInto(result, 64 - normalized.size)
            return result
        }
    }

    /** Signs the challenge as a digest. The result is low-S so it looks like a coprocessor signature. */
    fun signChallenge(challenge: ByteArray): ByteArray {
        if (challenge.size != 32) {
            throw IllegalStateException(
                "MFi 3.0 challenge must be 32 bytes, got ${challenge.size}; the phone may be using " +
                    "MFi 2.0C (RSA/SHA-1), which this implementation does not support",
            )
        }

        val signer = Signature.getInstance("NONEwithECDSA")
        signer.initSign(key)
        signer.update(challenge)
        return normalizeLowS(derToP1363(signer.sign(), 32))
    }
}
