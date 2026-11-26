package io.github.d1ngtalk.iap2loc.protocol

import java.security.SecureRandom

/**
 * Everything iOS sees as "which accessory is this": the identification strings plus, optionally,
 * the Bluetooth address reported in transport component 17.
 *
 * The serial is rotated on every start (see [rotate]), the adapter address is generated
 * once and then reused, so iOS sees a new accessory on the same Bluetooth link each run.
 */
data class AccessoryProfile(
    val name: String = "iAP2 Loc",
    val model: String = "Loc1,1",
    val manufacturer: String = "carplay-loc",
    val serial: String = "LOC000000001",
    val firmware: String = "0.1.0",
    val hardware: String = "1.0",
    /**
     * Overrides the adapter address in component 17. Null keeps the real one. iOS accepts the
     * rewritten value, so the accessory looks like another unit from the same vendor.
     */
    val adapterAddress: String? = null,
) {
    companion object {
        val DEFAULT = AccessoryProfile()
        private val random = SecureRandom()

        /** A different unit from the same vendor: keeps the adapter's OUI, randomises the rest. */
        fun randomAdapterAddress(localAdapter: ByteArray?): String {
            val bytes = ByteArray(6).also { random.nextBytes(it) }
            if (localAdapter != null && localAdapter.size == 6) localAdapter.copyInto(bytes, 0, 0, 3)
            return bytes.toHex(":")
        }

        /** Six bytes from "AA:BB:CC:DD:EE:FF", or null when unset or malformed. */
        fun parseAddress(text: String?): ByteArray? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(':', '-').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 6) return null
            return ByteArray(6) { index -> parts[index].toIntOrNull(16)?.takeIf { it in 0..255 }?.toByte() ?: return null }
        }
    }

    fun toIdentity() = AccessoryIdentity(name, model, manufacturer, serial, firmware, hardware)

    fun adapterOverride(): ByteArray? = parseAddress(adapterAddress)

    fun rotate(): AccessoryProfile {
        val serial = "LC" + ByteArray(6).also { random.nextBytes(it) }.toHex()
        return copy(serial = serial, name = "iAP2 Loc " + serial.takeLast(4))
    }

    fun sanitize() = copy(
        name = clean(name, DEFAULT.name),
        model = clean(model, DEFAULT.model),
        manufacturer = clean(manufacturer, DEFAULT.manufacturer),
        serial = clean(serial, DEFAULT.serial),
        firmware = clean(firmware, DEFAULT.firmware),
        hardware = clean(hardware, DEFAULT.hardware),
    )

    private fun clean(value: String?, fallback: String) = if (value.isNullOrBlank()) fallback else value.trim()
}
