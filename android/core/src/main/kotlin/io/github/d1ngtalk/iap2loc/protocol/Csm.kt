package io.github.d1ngtalk.iap2loc.protocol

import java.io.ByteArrayOutputStream

/**
 * iAP2 control-session messages: 40 40 | length(2) | message id(2) | parameters.
 * Each parameter is length(2) | id(2) | data, where length includes the four header bytes.
 */
object Csm {
    const val MAGIC = 0x4040

    fun encode(messageId: Int, vararg parameters: ByteArray): ByteArray {
        val length = 6 + parameters.sumOf { it.size }
        val buffer = ByteArray(length)
        buffer[0] = 0x40
        buffer[1] = 0x40
        buffer[2] = (length shr 8).toByte()
        buffer[3] = length.toByte()
        buffer[4] = (messageId shr 8).toByte()
        buffer[5] = messageId.toByte()

        var offset = 6
        for (parameter in parameters) {
            parameter.copyInto(buffer, offset)
            offset += parameter.size
        }

        return buffer
    }

    fun param(id: Int, data: ByteArray): ByteArray {
        val length = data.size + 4
        val buffer = ByteArray(length)
        buffer[0] = (length shr 8).toByte()
        buffer[1] = length.toByte()
        buffer[2] = (id shr 8).toByte()
        buffer[3] = id.toByte()
        data.copyInto(buffer, 4)
        return buffer
    }

    /** Parses a parameter block into id → data. Later duplicates win, which is what iOS does. */
    fun parseParams(buffer: ByteArray, start: Int, count: Int): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var offset = start
        val end = start + count
        while (offset + 4 <= end) {
            val length = buffer.u16(offset)
            if (length < 4 || offset + length > end) {
                throw IllegalArgumentException("malformed iAP2 parameter at offset $offset (length $length)")
            }

            val id = buffer.u16(offset + 2)
            result[id] = buffer.copyOfRange(offset + 4, offset + length)
            offset += length
        }

        return result
    }
}

/** Fluent builder for iAP2 parameter blocks, including nested groups. */
class CsmParams {
    private val stream = ByteArrayOutputStream()

    fun param(id: Int, data: ByteArray) = apply { stream.write(Csm.param(id, data)) }

    fun u8(id: Int, value: Int) = param(id, byteArrayOf(value.toByte()))

    fun u16(id: Int, value: Int) = param(id, byteArrayOf((value shr 8).toByte(), value.toByte()))

    /** iAP2 strings are NUL terminated, including the NMEA sentence payload. */
    fun str(id: Int, value: String) = param(id, (value + "\u0000").toByteArray(Charsets.UTF_8))

    fun void(id: Int) = param(id, ByteArray(0))

    fun u16List(id: Int, values: IntArray): CsmParams {
        val data = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            data[index * 2] = (value shr 8).toByte()
            data[index * 2 + 1] = value.toByte()
        }

        return param(id, data)
    }

    fun group(id: Int, body: CsmParams.() -> Unit) = param(id, CsmParams().apply(body).toByteArray())

    fun toByteArray(): ByteArray = stream.toByteArray()
}

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

internal fun ByteArray.u32(index: Int): Int =
    (u8(index) shl 24) or (u8(index + 1) shl 16) or (u8(index + 2) shl 8) or u8(index + 3)

fun ByteArray.toHex(separator: String = ""): String = joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }
