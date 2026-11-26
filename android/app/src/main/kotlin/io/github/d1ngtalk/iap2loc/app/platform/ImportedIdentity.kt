package io.github.d1ngtalk.iap2loc.app.platform

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.d1ngtalk.iap2loc.protocol.IdentityInspector
import io.github.d1ngtalk.iap2loc.protocol.IdentityProvider
import io.github.d1ngtalk.iap2loc.protocol.IdentityReport
import io.github.d1ngtalk.iap2loc.protocol.MfiSigner
import io.github.d1ngtalk.iap2loc.protocol.ResolvedIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * MFi material imported during onboarding, kept in app-private storage (excluded from backups).
 * A blank identity directory uses imported files first, then optional release-bundled assets.
 */
class ImportedIdentity internal constructor(
    val directory: File,
    loadBundledMaterial: () -> Pair<ByteArray, ByteArray>?,
) : IdentityProvider {
    constructor(context: Context) : this(File(context.filesDir, "identity"), bundledLoader(context))

    private val bundledMaterial: Pair<ByteArray, ByteArray>? by lazy(loadBundledMaterial)
    private val keyFile get() = File(directory, MfiSigner.KEY_FILE)
    private val certificateFile get() = File(directory, MfiSigner.CERTIFICATE_FILE)

    private val hasCompleteImportedIdentity: Boolean get() = keyFile.isFile && certificateFile.isFile
    val hasImportedFiles: Boolean get() = keyFile.isFile || certificateFile.isFile
    val missingImportedFile: String?
        get() = when {
            keyFile.isFile && !certificateFile.isFile -> MfiSigner.CERTIFICATE_FILE
            certificateFile.isFile && !keyFile.isFile -> MfiSigner.KEY_FILE
            else -> null
        }
    val usingBundled: Boolean get() = !hasCompleteImportedIdentity && bundledMaterial != null
    val isPresent: Boolean get() = hasCompleteImportedIdentity || usingBundled

    override val name = "imported"

    override val warning: String?
        get() {
            missingImportedFile?.let { missing ->
                return "自定义 MFi 身份尚未完整导入，还需要 $missing。" +
                    if (usingBundled) "补齐前继续使用 APK 内置身份。" else "当前仍可连接和识别，但认证不会通过。"
            }
            return if (isPresent) null else
                "尚未导入 MFi 身份（identity.pk8 与 certificate.p7b）：可以连接和识别，但认证不会通过，iPhone 不会采用这里发送的定位。" +
                "请在右上角菜单 →「新手引导」中导入。"
        }

    override fun resolve(directory: String?): ResolvedIdentity = when {
        !directory.isNullOrBlank() -> File(directory).let { ResolvedIdentity(MfiSigner.load(it), "文件 ${it.absolutePath}") }
        hasCompleteImportedIdentity -> ResolvedIdentity(MfiSigner.load(this.directory), "已导入的 MFi 身份")
        else -> bundledMaterial?.let { (key, certificate) ->
            ResolvedIdentity(MfiSigner.fromMaterial(key, certificate), "APK 内置的 MFi 身份")
        } ?: ResolvedIdentity(null, "无")
    }

    /** The inspect-identity summary, or the reason the files cannot be used. */
    fun report(): Result<IdentityReport>? = when {
        hasCompleteImportedIdentity -> runCatching { IdentityInspector.inspect(keyFile.readBytes(), certificateFile.readBytes()) }
        else -> bundledMaterial?.let { (key, certificate) -> runCatching { IdentityInspector.inspect(key, certificate) } }
    }

    fun delete() {
        keyFile.delete()
        certificateFile.delete()
    }

    class ImportOutcome(val keyImported: Boolean, val certificateImported: Boolean, val problems: List<String>)

    /**
     * Accepts identity.pk8 and certificate.p7b in any order, also PEM text or a zip holding both.
     * Files are recognised by content, not name, because chat apps and file managers rename them.
     */
    fun import(resolver: ContentResolver, uris: List<Uri>): ImportOutcome {
        var key: ByteArray? = null
        var certificate: ByteArray? = null
        val problems = ArrayList<String>()

        fun classify(name: String, bytes: ByteArray) {
            val candidates = listOfNotNull(bytes, pemBody(bytes))
            when {
                candidates.any { IdentityInspector.isPrivateKey(it) } -> key = candidates.first { IdentityInspector.isPrivateKey(it) }
                runCatching { IdentityInspector.parseCertificates(bytes).isNotEmpty() }.getOrDefault(false) -> certificate = bytes
                String(bytes.take(64).toByteArray(), Charsets.US_ASCII).contains("EC PRIVATE KEY") ->
                    problems += "$name：这是 SEC1 格式（BEGIN EC PRIVATE KEY）的私钥，请先转换为 PKCS#8，例如 " +
                        "openssl pkcs8 -topk8 -nocrypt -in key.pem -outform DER -out identity.pk8"
                else -> problems += "$name：无法识别，需要 identity.pk8（私钥）或 certificate.p7b（证书链）"
            }
        }

        for (uri in uris) {
            val name = displayName(resolver, uri)
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readAtMost(MAX_FILE + 1) } }.getOrNull()
            when {
                bytes == null -> problems += "$name：无法读取"
                bytes.size > MAX_FILE -> problems += "$name：文件过大，不像是密钥或证书"
                isZip(bytes) -> unzip(bytes).ifEmpty { problems += "$name：压缩包里没有可用的文件"; emptyList() }
                    .forEach { (entry, data) -> classify("$name/$entry", data) }
                else -> classify(name, bytes)
            }
        }

        directory.mkdirs()
        key?.let { writeAtomically(keyFile, it) }
        certificate?.let { writeAtomically(certificateFile, it) }
        return ImportOutcome(key != null, certificate != null, problems)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "文件"

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            target.writeBytes(bytes)
            temporary.delete()
        }
    }

    private companion object {
        const val MAX_FILE = 1 shl 20

        fun bundledLoader(context: Context): () -> Pair<ByteArray, ByteArray>? {
            val assets = context.applicationContext.assets
            return {
                val files = assets.list("offline-mfi").orEmpty()
                if (MfiSigner.KEY_FILE in files && MfiSigner.CERTIFICATE_FILE in files) {
                    assets.open("offline-mfi/${MfiSigner.KEY_FILE}").use { it.readBytes() } to
                        assets.open("offline-mfi/${MfiSigner.CERTIFICATE_FILE}").use { it.readBytes() }
                } else {
                    null
                }
            }
        }

        fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2].toInt() == 3 && bytes[3].toInt() == 4

        fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
            val entries = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name.substringAfterLast('/').startsWith(".")) continue
                    val data = zip.readAtMost(MAX_FILE + 1)
                    if (data.size <= MAX_FILE) entries += entry.name.substringAfterLast('/') to data
                    if (entries.size >= 16) break
                }
            }

            return entries
        }

        /** InputStream.readNBytes needs API 33. */
        fun InputStream.readAtMost(limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }

            return output.toByteArray()
        }

        /** The DER inside "-----BEGIN PRIVATE KEY-----" text, if the file is PEM. */
        fun pemBody(bytes: ByteArray): ByteArray? {
            val text = runCatching { String(bytes, Charsets.US_ASCII) }.getOrNull() ?: return null
            val match = Regex("-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----").find(text) ?: return null
            return runCatching { Base64.getMimeDecoder().decode(match.groupValues[1]) }.getOrNull()
        }
    }
}
