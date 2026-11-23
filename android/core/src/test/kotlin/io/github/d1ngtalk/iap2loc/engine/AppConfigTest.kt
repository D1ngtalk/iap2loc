package io.github.d1ngtalk.iap2loc.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppConfigTest {
    private val directory = Files.createTempDirectory("iap2loc-config").toFile()

    @Test
    fun readsTheDesktopFileAndRotatesOnStart() {
        val file = directory.resolve("iap2loc.json")
        // A file as the Windows/macOS build writes it.
        file.writeText(
            """
            {
              "randomizeIdentity": true,
              "randomizeAdapter": true,
              "accessory": {
                "name": "iAP2 Loc 1234",
                "model": "Loc1,1",
                "manufacturer": "carplay-loc",
                "serial": "LC000000001234",
                "firmware": "0.1.0",
                "hardware": "1.0",
                "adapterAddress": "3C:22:FB:AA:BB:CC"
              },
              "favorites": [
                { "id": "ab12cd34", "name": "外滩", "latitude": 31.2400, "longitude": 121.4900, "altitude": null, "accuracy": 5 }
              ]
            }
            """.trimIndent(),
        )

        val config = AppConfig.load(file)
        assertEquals("外滩", config.favoritesSnapshot().single().name)
        assertEquals("3C:22:FB:AA:BB:CC", config.effectiveProfile.adapterAddress)

        config.prepareForRun(null)
        val rotated = config.effectiveProfile
        assertNotEquals("LC000000001234", rotated.serial)
        assertTrue(rotated.serial.matches(Regex("LC[0-9A-F]{12}")), rotated.serial)
        assertEquals("iAP2 Loc " + rotated.serial.takeLast(4), rotated.name)
        assertEquals("3C:22:FB:AA:BB:CC", rotated.adapterAddress, "the address is kept once generated")

        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(rotated.serial, saved.getValue("accessory").jsonObject.getValue("serial").jsonPrimitive.content)
        assertTrue(saved.getValue("randomizeIdentity").jsonPrimitive.boolean)
        assertEquals(1, saved.getValue("favorites").jsonArray.size)
    }

    @Test
    fun firstRunGeneratesAnAddressAndFavoritesPersist() {
        val file = directory.resolve("fresh/iap2loc.json")
        val config = AppConfig.load(file)
        config.prepareForRun(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60))
        val address = assertNotNull(config.effectiveProfile.adapterAddress)
        assertTrue(address.startsWith("10:20:30:"), "keeps the local OUI: $address")

        val favorite = config.addFavorite("", 31.1, 121.2, 4.0, null)
        assertEquals("31.1000, 121.2000", favorite.name)

        val reloaded = AppConfig.load(file)
        assertEquals(favorite, reloaded.favoritesSnapshot().single())
        assertTrue(reloaded.removeFavorite(favorite.id))
        assertTrue(AppConfig.load(file).favoritesSnapshot().isEmpty())
    }

    @Test
    fun fixedAdapterReportsNoOverride() {
        val config = AppConfig.load(directory.resolve("fixed.json"))
        config.update(randomizeAdapter = false)
        config.prepareForRun(null)
        assertEquals(null, config.effectiveProfile.adapterAddress)

        config.update(randomizeAdapter = true, adapterAddress = "02:11:22:33:44:55")
        assertEquals("02:11:22:33:44:55", config.effectiveProfile.adapterAddress)
    }

    @Test
    fun brokenFileFallsBackToDefaults() {
        val file = directory.resolve("broken.json").apply { writeText("{ not json") }
        val config = AppConfig.load(file)
        assertTrue(config.favoritesSnapshot().isEmpty())
        assertEquals("Loc1,1", config.effectiveProfile.model)
    }
}
