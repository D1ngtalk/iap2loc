package io.github.d1ngtalk.iap2loc.engine

import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.protocol.IdentitySource
import io.github.d1ngtalk.iap2loc.transport.Bluetooth
import io.github.d1ngtalk.iap2loc.web.WebServer
import java.io.Closeable
import java.io.File
import java.net.BindException

/** What `iap2loc serve` starts: the config, the shared target with its sessions, and the console. */
class Engine(
    configFile: File,
    port: Int = DEFAULT_PORT,
    lanAccess: Boolean = false,
    latitude: Double = 31.2304,
    longitude: Double = 121.4737,
) : Closeable {
    companion object {
        const val DEFAULT_PORT = 8737
    }

    val config: AppConfig = AppConfig.load(configFile)
    val manager: SessionManager
    val server: WebServer

    init {
        config.prepareForRun(Bluetooth.localAdapterAddress())
        manager = SessionManager(latitude, longitude, config)

        // Let a custom build warm up its identity source before any session starts.
        IdentitySource.prime()

        server = try {
            WebServer(port, manager, config, lanAccess)
        } catch (ex: BindException) {
            // Another app holds the port: any free one will do, the app opens whatever was bound.
            Log.warn("端口 $port 被占用，改用随机端口")
            WebServer(0, manager, config, lanAccess)
        }

        server.start()
    }

    override fun close() {
        server.close()
        manager.close()
    }
}
