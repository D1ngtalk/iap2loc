package io.github.d1ngtalk.iap2loc.protocol

data class AccessoryIdentity(
    val name: String,
    val model: String,
    val manufacturer: String,
    val serial: String,
    val firmware: String,
    val hardware: String,
)

/**
 * Builds the IdentificationInformation (0x1D01) payload of a plain MFi GPS accessory.
 *
 * The two lists that matter:
 *   parameter 6  messages this accessory sends      -> must contain 0xFFFB LocationInformation
 *   parameter 7  messages this accessory receives   -> must contain 0xFFFA / 0xFFFC
 *   group 22     the Location component declaration, without it iOS never subscribes
 */
object Identification {
    private val coreSent = intArrayOf(
        0xAA01, // AuthenticationCertificate
        0xAA03, // AuthenticationResponse
        0xFFFB, // LocationInformation
    )

    private val coreReceived = intArrayOf(
        0xAA00, // RequestAuthenticationCertificate
        0xAA02, // RequestAuthenticationChallengeResponse
        0xAA04, // AuthenticationFailed
        0xAA05, // AuthenticationSucceeded
        0xFFFA, // StartLocationInformation
        0xFFFC, // StopLocationInformation
    )

    fun build(identity: AccessoryIdentity, bluetoothAdapter: ByteArray?): ByteArray {
        val parameters = CsmParams()
            .str(0, identity.name)
            .str(1, identity.model)
            .str(2, identity.manufacturer)
            .str(3, identity.serial)
            .str(4, identity.firmware)
            .str(5, identity.hardware)
            .u16List(6, coreSent)
            .u16List(7, coreReceived)
            .u8(8, 0) // no USB power source
            .u16(9, 20) // carried over from the working implementations, semantics undocumented
            .str(12, "en")
            .str(13, "en")

        // Parameter 17 is the Bluetooth transport component. A transport component is mandatory:
        // omitting it makes iOS reject the whole identification with 0x1D03 (offending: 17).
        if (bluetoothAdapter != null && bluetoothAdapter.size == 6) {
            parameters.group(17) {
                u16(0, 0) // component id
                str(1, "blue") // component name
                void(2)
                param(3, bluetoothAdapter) // local adapter address, raw 6 bytes
                str(4, "blue") // transport type
                void(5)
            }
        }

        parameters.group(22) {
            u16(0, 0)
            str(1, identity.name)
            void(17)
            void(18)
        }

        return parameters.toByteArray()
    }
}
