package io.github.d1ngtalk.iap2loc.transport

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** A byte duplex that iAP2 can run over: Bluetooth RFCOMM, a TCP tunnel, or an in-memory pair. */
interface Transport : Closeable {
    val describe: String

    /** Blocking read. Returns the number of bytes read, or 0 when the peer closed. */
    fun read(buffer: ByteArray, offset: Int, count: Int): Int

    fun write(buffer: ByteArray, offset: Int, count: Int)
}

/**
 * iAP2 over a raw TCP stream. This is the shape the wireless CarPlay link has once the AirPlay
 * session is up: the accessory gets a byte pipe that carries iAP2 CSM messages.
 */
class TcpTransport private constructor(private val socket: Socket, override val describe: String) : Transport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    companion object {
        fun connect(host: String, port: Int, timeoutSeconds: Int = 10): TcpTransport {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutSeconds * 1000)
            } catch (ex: Exception) {
                socket.close()
                throw IOException("TCP connect to $host:$port failed: ${ex.message}", ex)
            }

            socket.tcpNoDelay = true
            return TcpTransport(socket, "TCP $host:$port")
        }

        /** Wraps an already accepted socket, used by tests and loopback setups. */
        fun fromSocket(socket: Socket, describe: String): TcpTransport {
            socket.tcpNoDelay = true
            return TcpTransport(socket, describe)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = try {
        input.read(buffer, offset, count).coerceAtLeast(0)
    } catch (ex: IOException) {
        if (socket.isClosed) 0 else throw ex
    }

    override fun write(buffer: ByteArray, offset: Int, count: Int) = output.write(buffer, offset, count)

    override fun close() = socket.close()
}
