package io.github.d1ngtalk.iap2loc.app.platform

import io.github.d1ngtalk.iap2loc.protocol.MfiSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedIdentityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private data class Material(val keys: KeyPair, val certificate: ByteArray, val subject: String) {
        val bytes get() = keys.private.encoded to certificate

        fun write(directory: File, key: Boolean = true, certificate: Boolean = true) {
            directory.mkdirs()
            if (key) File(directory, MfiSigner.KEY_FILE).writeBytes(keys.private.encoded)
            if (certificate) File(directory, MfiSigner.CERTIFICATE_FILE).writeBytes(this.certificate)
        }
    }

    private fun material(subject: String): Material {
        val keys = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val now = Instant.now()
        val name = X500Name("CN=$subject")
        val builder = X509v3CertificateBuilder(
            name, BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(1, ChronoUnit.DAYS)),
            name, SubjectPublicKeyInfo.getInstance(keys.public.encoded),
        )
        val certificate = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)),
        )
        val chain = CMSSignedDataGenerator().run {
            addCertificates(JcaCertStore(listOf(certificate)))
            generate(CMSProcessableByteArray(ByteArray(0))).encoded
        }
        return Material(keys, chain, subject)
    }

    private fun assertUses(identity: ImportedIdentity, expected: Material, explicitDirectory: String? = null) {
        val signer = assertNotNull(identity.resolve(explicitDirectory).signer)
        assertContentEquals(expected.certificate, signer.certificate)
        val challenge = ByteArray(32) { (it * 3 + 1).toByte() }
        val signature = signer.signChallenge(challenge)
        assertTrue(Signature.getInstance("NONEwithECDSAinP1363Format").run {
            initVerify(expected.keys.public)
            update(challenge)
            verify(signature)
        }, "the selected private key must belong to the selected certificate")
    }

    @Test
    fun completePairsDetermineTheSourceAndPartialFilesNeverMixWithBundledMaterial() {
        val imported = material("Imported")
        val bundled = material("Bundled")
        for (hasBundle in listOf(false, true)) {
            // none, private key only, certificate only, both
            for (files in 0..3) {
                val label = "bundle=$hasBundle, imported files=$files"
                val directory = temporary.newFolder()
                imported.write(directory, key = (files and 1) != 0, certificate = (files and 2) != 0)
                val identity = ImportedIdentity(directory) { if (hasBundle) bundled.bytes else null }
                val expected = if (files == 3) imported else if (hasBundle) bundled else null
                val missing = when (files) {
                    1 -> MfiSigner.CERTIFICATE_FILE
                    2 -> MfiSigner.KEY_FILE
                    else -> null
                }

                assertEquals(files != 0, identity.hasImportedFiles, label)
                assertEquals(missing, identity.missingImportedFile, label)
                assertEquals(hasBundle && files != 3, identity.usingBundled, label)
                assertEquals(expected != null, identity.isPresent, label)
                if (expected == null) {
                    assertNull(identity.resolve(null).signer, label)
                    assertEquals("无", identity.resolve(null).description, label)
                    assertNull(identity.report(), label)
                    assertTrue(assertNotNull(identity.warning).contains("连接和识别"), label)
                } else {
                    assertUses(identity, expected)
                    assertEquals(if (files == 3) "已导入的 MFi 身份" else "APK 内置的 MFi 身份", identity.resolve(null).description, label)
                    val report = assertNotNull(identity.report()).getOrThrow()
                    assertEquals(true, report.keyMatchesCertificate, label)
                    assertTrue(report.certificates.single().subject.contains("CN=${expected.subject}"), label)
                }
                if (missing != null) {
                    val warning = assertNotNull(identity.warning)
                    assertTrue(warning.contains(missing), label)
                    if (hasBundle) assertTrue(warning.contains("补齐前继续使用 APK 内置身份"), label)
                } else if (expected != null) {
                    assertNull(identity.warning, label)
                }
            }
        }
    }

    @Test
    fun completingAndDeletingAnImportSwitchesToWholePairs() {
        val imported = material("Imported")
        val bundled = material("Bundled")
        for (hasBundle in listOf(false, true)) {
            val directory = temporary.newFolder()
            val identity = ImportedIdentity(directory) { if (hasBundle) bundled.bytes else null }
            imported.write(directory, key = false)
            assertEquals(MfiSigner.KEY_FILE, identity.missingImportedFile)
            if (hasBundle) assertUses(identity, bundled) else assertNull(identity.resolve(null).signer)

            imported.write(directory, certificate = false)
            assertUses(identity, imported)
            assertNull(identity.missingImportedFile)
            assertNull(identity.warning)
            assertFalse(identity.usingBundled)

            identity.delete()
            assertFalse(identity.hasImportedFiles)
            assertNull(identity.missingImportedFile)
            assertEquals(hasBundle, identity.usingBundled)
            assertEquals(hasBundle, identity.isPresent)
            if (hasBundle) {
                assertUses(identity, bundled)
                assertNull(identity.warning)
            } else {
                assertNull(identity.resolve(null).signer)
                assertNull(identity.report())
            }
        }
    }

    @Test
    fun explicitDirectoryTakesPriorityAndDoesNotFallBackWhenMissingFiles() {
        val imported = material("Imported")
        val bundled = material("Bundled")
        val explicit = material("Explicit")
        val directory = temporary.newFolder()
        val explicitDirectory = temporary.newFolder()
        imported.write(directory)
        explicit.write(explicitDirectory)
        val identity = ImportedIdentity(directory) { bundled.bytes }
        assertUses(identity, explicit, explicitDirectory.absolutePath)
        assertEquals("文件 ${explicitDirectory.absolutePath}", identity.resolve(explicitDirectory.absolutePath).description)

        File(explicitDirectory, MfiSigner.CERTIFICATE_FILE).delete()
        assertFailsWith<FileNotFoundException> { identity.resolve(explicitDirectory.absolutePath) }
        assertUses(identity, imported)
    }

    @Test
    fun corruptCompleteImportedPairReportsFailureWithoutUsingBundledIdentity() {
        val imported = material("Imported")
        val directory = temporary.newFolder()
        imported.write(directory)
        File(directory, MfiSigner.KEY_FILE).writeBytes(byteArrayOf(1, 2, 3))
        val identity = ImportedIdentity(directory) { error("A complete imported pair must not fall back") }

        assertTrue(identity.isPresent)
        assertFalse(identity.usingBundled)
        assertNull(identity.missingImportedFile)
        assertTrue(assertNotNull(identity.report()).isFailure)
        assertFailsWith<IllegalArgumentException> { identity.resolve(null) }
    }

    @Test
    fun corruptImportedCertificateIsReportedInsteadOfShowingTheBundledReport() {
        val imported = material("Imported")
        val directory = temporary.newFolder()
        imported.write(directory)
        File(directory, MfiSigner.CERTIFICATE_FILE).writeBytes(byteArrayOf(1, 2, 3))
        val identity = ImportedIdentity(directory) { error("A complete imported pair must not fall back") }
        assertFalse(identity.usingBundled)
        assertTrue(assertNotNull(identity.report()).isFailure)
    }
}
