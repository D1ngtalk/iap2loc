package io.github.d1ngtalk.iap2loc.engine

import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.protocol.AccessoryProfile
import io.github.d1ngtalk.iap2loc.protocol.IdentificationState
import io.github.d1ngtalk.iap2loc.protocol.IdentitySource
import io.github.d1ngtalk.iap2loc.protocol.Iap2Link
import io.github.d1ngtalk.iap2loc.protocol.LocationSession
import io.github.d1ngtalk.iap2loc.protocol.LocationTarget
import io.github.d1ngtalk.iap2loc.protocol.toHex
import io.github.d1ngtalk.iap2loc.transport.Bluetooth
import io.github.d1ngtalk.iap2loc.transport.TcpTransport
import io.github.d1ngtalk.iap2loc.transport.Transport
import java.io.Closeable

data class RunRequest(
    val transport: String = "bt",
    val mac: String? = null,
    val host: String? = null,
    val port: Int = 0,
    val identityDirectory: String? = null,
    val rate: Int = 250,
    val profile: AccessoryProfile = AccessoryProfile.DEFAULT,
    val label: String = "iPhone",
) {
    val isBluetooth: Boolean get() = transport.startsWith("bt", ignoreCase = true) || transport.equals("bluetooth", true)
}

/** How far a session got, in the order a working connection passes through. For the onboarding test. */
enum class SessionStage(val wire: String) {
    CONNECTING("connecting"),
    LINK("link"),
    IDENTIFYING("identifying"),
    AUTHENTICATING("authenticating"),
    WAITING_SUBSCRIPTION("waitingSubscription"),
    STREAMING("streaming"),
    FAILED("failed"),
    STOPPED("stopped"),
}

/** One accessory session. Several can run at once; all of them follow the shared target. */
class RunSession(val id: String, val request: RunRequest, private val target: LocationTarget) : Closeable {
    private val gate = Any()

    private var transport: Transport? = null
    private var link: Iap2Link? = null
    private var location: LocationSession? = null
    private var hasSigner = false
    private var error: String? = null
    private var running = false
    private var connecting = false
    private var closed = false

    val label: String get() = request.label.ifBlank { "iPhone" }

    val isRunning: Boolean get() = synchronized(gate) { running }

    val isConnecting: Boolean get() = synchronized(gate) { connecting }

    val linkState: String get() = synchronized(gate) { link?.linkState?.wire ?: "Idle" }

    val authenticated: Boolean get() = synchronized(gate) { location?.authenticated ?: false }

    val subscribed: Boolean get() = synchronized(gate) { location?.isSubscribed ?: false }

    val sent: Int get() = synchronized(gate) { location?.messagesPushed ?: 0 }

    val lastError: String? get() = synchronized(gate) { error }

    val identification: IdentificationState? get() = synchronized(gate) { location?.identification }

    val authenticationFailed: Boolean get() = synchronized(gate) { location?.authenticationFailed ?: false }

    /** False when the session started without MFi material; authentication then cannot pass. */
    val hasIdentity: Boolean get() = synchronized(gate) { hasSigner }

    val stage: SessionStage
        get() = synchronized(gate) {
            val session = location
            when {
                connecting -> SessionStage.CONNECTING
                error != null -> SessionStage.FAILED
                !running || session == null -> SessionStage.STOPPED
                link?.linkState?.wire != "Normal" -> SessionStage.LINK
                session.isSubscribed -> SessionStage.STREAMING
                session.authenticated -> SessionStage.WAITING_SUBSCRIPTION
                session.identification == IdentificationState.ACCEPTED -> SessionStage.AUTHENTICATING
                else -> SessionStage.IDENTIFYING
            }
        }

    fun start() {
        synchronized(gate) {
            if (running || connecting || closed) return
            connecting = true
            error = null
        }

        // Connecting blocks for seconds: never hold the session lock while doing it, so the
        // console keeps answering state polls.
        var opened: Transport? = null
        try {
            val resolved = IdentitySource.resolve(request.identityDirectory)
            val signer = resolved.signer
            if (signer == null) {
                Log.warn("[$label] 未配置身份：链路与识别可用，认证不会通过")
            } else {
                Log.info("[$label] 身份：${resolved.description}，证书 ${signer.certificate.size} 字节")
            }

            opened = when (request.transport.lowercase()) {
                "bt", "bluetooth" -> Bluetooth.connect(request.mac ?: throw IllegalArgumentException("缺少蓝牙地址"))
                "tcp", "wifi" -> TcpTransport.connect(
                    request.host ?: "127.0.0.1",
                    if (request.port > 0) request.port else throw IllegalArgumentException("缺少 TCP 端口"),
                )

                else -> throw IllegalArgumentException("未知传输 '${request.transport}'")
            }

            val localAdapter = if (request.isBluetooth) Bluetooth.localAdapterAddress() else null
            val reportedAdapter = request.profile.adapterOverride()
            val adapter = reportedAdapter ?: localAdapter
            val identity = request.profile.toIdentity()

            if (reportedAdapter != null) Log.warn("[$label] 蓝牙地址已改写为 ${reportedAdapter.toHex(":")}（实验项）")

            val newLink = Iap2Link(opened, initiate = true, role = "accessory-$id")
            val newLocation = LocationSession(newLink, target, identity, signer, request.rate, bluetoothAdapter = adapter)

            newLink.failed += { reason ->
                synchronized(gate) {
                    error = reason
                    running = false
                }

                Log.error("[$label] 链路中断：$reason")
            }

            synchronized(gate) {
                connecting = false
                if (closed) {
                    // Removed while it was still connecting: drop the fresh connection.
                    newLocation.close()
                    newLink.close()
                    return
                }

                transport = opened
                link = newLink
                location = newLocation
                hasSigner = signer != null
                running = true
            }

            newLink.start()
            Log.ok("[$label] 已启动：${opened.describe}，配件身份 ${identity.serial}")
        } catch (ex: Exception) {
            val message = ex.message ?: ex.toString()
            synchronized(gate) {
                error = message
                connecting = false
            }

            Log.error("[$label] 启动失败：$message")
            runCatching { opened?.close() }
            dispose()
        }
    }

    fun setInterval(intervalMs: Int) = synchronized(gate) { location?.setInterval(intervalMs) }

    fun stop() {
        synchronized(gate) {
            if (!running && link == null) return
            running = false
        }

        close()
        Log.info("[$label] 已停止")
    }

    override fun close() {
        synchronized(gate) { closed = true }
        dispose()
    }

    private fun dispose() = synchronized(gate) {
        running = false
        location?.close()
        location = null
        link?.close()
        link = null
        transport = null
    }
}
