package io.github.d1ngtalk.iap2loc.app.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.transport.Transport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * RFCOMM client for the iPhone iAP2 service, the Android counterpart of BtRfcommTransport.cs.
 * BluetoothSocket resolves the service UUID through SDP, so like on Windows nothing has to be
 * registered locally; the iPhone only has to be bonded.
 */
class AndroidRfcommTransport private constructor(private val socket: BluetoothSocket, address: String) : Transport {
    override val describe = "Android RFCOMM $address"
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val writeGate = Any()
    @Volatile private var closed = false

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = try {
        input.read(buffer, offset, count).coerceAtLeast(0)
    } catch (ex: IOException) {
        if (closed) 0 else throw ex
    }

    override fun write(buffer: ByteArray, offset: Int, count: Int) = synchronized(writeGate) {
        output.write(buffer, offset, count)
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun connect(adapter: BluetoothAdapter, address: String, timeoutSeconds: Int): Transport {
            val device = adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw IOException("目标设备未与本机配对：请先在新手引导或系统蓝牙设置中配对 iPhone")
            }

            // Discovery slows RFCOMM down a lot and can make the connect fail.
            runCatching { adapter.cancelDiscovery() }

            // Encrypted first, as a paired link should be; some stacks only accept the insecure socket.
            val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
                "加密" to { device.createRfcommSocketToServiceRecord(AndroidBluetooth.IAP2) },
                "非加密" to { device.createInsecureRfcommSocketToServiceRecord(AndroidBluetooth.IAP2) },
            )

            var last: Exception? = null
            for ((label, open) in attempts) {
                val socket = try {
                    open()
                } catch (ex: Exception) {
                    last = ex
                    continue
                }

                try {
                    connectWithTimeout(socket, timeoutSeconds)
                    return AndroidRfcommTransport(socket, address)
                } catch (ex: Exception) {
                    runCatching { socket.close() }
                    last = ex
                    Log.info("[$address] $label RFCOMM 连接失败：${ex.message}")
                }
            }

            throw IOException(explain(last), last)
        }

        @SuppressLint("MissingPermission")
        private fun connectWithTimeout(socket: BluetoothSocket, timeoutSeconds: Int) {
            val done = CountDownLatch(1)
            var failure: Exception? = null
            thread(name = "rfcomm-connect", isDaemon = true) {
                try {
                    socket.connect()
                } catch (ex: Exception) {
                    failure = ex
                } finally {
                    done.countDown()
                }
            }

            if (!done.await(timeoutSeconds.coerceIn(1, 120).toLong(), TimeUnit.SECONDS)) {
                runCatching { socket.close() }
                throw IOException("RFCOMM 连接超时（$timeoutSeconds 秒）")
            }

            failure?.let { throw it }
        }

        private fun explain(error: Exception?): String {
            val detail = error?.message.orEmpty()
            return when {
                detail.contains("超时") -> "$detail。iPhone 没有回应 iAP2 蓝牙服务：请解锁 iPhone、打开蓝牙并靠近本机"
                detail.contains("read failed") || detail.contains("socket") || detail.contains("Service discovery") ->
                    "iPhone 拒绝或没有回应 iAP2 蓝牙服务（$detail）。请解锁 iPhone 并保持在「设置 → 蓝牙」页面，" +
                        "确认两台手机已配对；仍失败时在两边都删除配对后重新配对"
                else -> "RFCOMM 连接失败：$detail"
            }
        }
    }
}
