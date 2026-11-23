package io.github.d1ngtalk.iap2loc.protocol

import java.io.File

/**
 * Optional identity source. The stock build reads identity.pk8 + certificate.p7b from a directory;
 * the Android app installs a provider that falls back to the files imported during onboarding.
 */
interface IdentityProvider {
    val name: String

    /** Human readable problem to surface in the console, if any. */
    val warning: String?

    fun resolve(directory: String?): ResolvedIdentity

    /** Called once when the console starts, before any session exists. */
    fun prime() {}
}

class ResolvedIdentity(val signer: MfiSigner?, val description: String)

/** Resolves the signing material for a session. */
object IdentitySource {
    @Volatile
    var provider: IdentityProvider? = null

    val warning: String? get() = provider?.warning

    fun resolve(directory: String?): ResolvedIdentity {
        provider?.let { return it.resolve(directory) }

        if (!directory.isNullOrBlank()) {
            val path = File(directory).absoluteFile
            return ResolvedIdentity(MfiSigner.load(path), "文件 $path")
        }

        return ResolvedIdentity(null, "无")
    }

    fun prime() {
        provider?.prime()
    }
}
