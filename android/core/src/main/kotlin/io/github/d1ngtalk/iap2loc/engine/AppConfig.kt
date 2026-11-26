package io.github.d1ngtalk.iap2loc.engine

import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.protocol.AccessoryProfile
import io.github.d1ngtalk.iap2loc.web.JsonPretty
import io.github.d1ngtalk.iap2loc.web.child
import io.github.d1ngtalk.iap2loc.web.double
import io.github.d1ngtalk.iap2loc.web.bool
import io.github.d1ngtalk.iap2loc.web.list
import io.github.d1ngtalk.iap2loc.web.obj
import io.github.d1ngtalk.iap2loc.web.parseObject
import io.github.d1ngtalk.iap2loc.web.text
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class Favorite(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Double?,
) {
    fun toJson() = obj(
        "id" to id, "name" to name, "latitude" to latitude, "longitude" to longitude,
        "altitude" to altitude, "accuracy" to accuracy,
    )
}

/**
 * The single settings file: accessory identity and saved points. Same file format as the desktop
 * iap2loc.json, so a config can be copied between the two.
 *
 * The serial rotates on every start, because iOS keeps per-accessory location state and drops
 * fixes from an accessory whose last known position is far away. The Bluetooth address is
 * generated once and then reused, so the link still belongs to the same paired device.
 */
class AppConfig private constructor(val file: File) {
    private val gate = Any()

    var randomizeIdentity = true
        private set
    var randomizeAdapter = true
        private set
    var accessory = AccessoryProfile.DEFAULT
        private set
    private val favorites = ArrayList<Favorite>()

    /** Runtime switch for --fixed-identity; never written to the file. */
    @Volatile var rotateIdentityOnStart = true

    /** Runtime switch for --fixed-adapter; never written to the file. */
    @Volatile var useAdapterOverride = true

    val effectiveProfile: AccessoryProfile
        get() = synchronized(gate) {
            if (randomizeAdapter && useAdapterOverride) accessory else accessory.copy(adapterAddress = null)
        }

    companion object {
        fun load(file: File): AppConfig {
            val config = AppConfig(file.absoluteFile)
            try {
                if (!config.file.isFile) return config
                val root = parseObject(config.file.readText()) ?: throw IllegalArgumentException("not a JSON object")
                root.bool("randomizeIdentity")?.let { config.randomizeIdentity = it }
                root.bool("randomizeAdapter")?.let { config.randomizeAdapter = it }
                root.child("accessory")?.let { config.accessory = readProfile(it).sanitize() }
                root.list("favorites")?.forEach { element ->
                    (element as? JsonObject)?.let(::readFavorite)?.let(config.favorites::add)
                }
            } catch (ex: Exception) {
                Log.warn("配置文件无法读取，改用默认值：${ex.message}")
            }

            return config
        }

        private fun readProfile(node: JsonObject) = AccessoryProfile(
            name = node.text("name") ?: "",
            model = node.text("model") ?: "",
            manufacturer = node.text("manufacturer") ?: "",
            serial = node.text("serial") ?: "",
            firmware = node.text("firmware") ?: "",
            hardware = node.text("hardware") ?: "",
            adapterAddress = node.text("adapterAddress"),
        )

        private fun readFavorite(node: JsonObject): Favorite? {
            val latitude = node.double("latitude") ?: return null
            val longitude = node.double("longitude") ?: return null
            return Favorite(
                node.text("id") ?: newId(),
                node.text("name") ?: label(latitude, longitude),
                latitude,
                longitude,
                node.double("altitude"),
                node.double("accuracy"),
            )
        }

        private fun newId() = UUID.randomUUID().toString().replace("-", "").take(8)

        private fun label(latitude: Double, longitude: Double) =
            String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude)
    }

    /** Rotates the identity and fills in the adapter address on first run, then persists both. */
    fun prepareForRun(localAdapter: ByteArray?) = synchronized(gate) {
        if (randomizeIdentity && rotateIdentityOnStart) accessory = accessory.rotate()

        if (randomizeAdapter && useAdapterOverride && accessory.adapterOverride() == null) {
            accessory = accessory.copy(adapterAddress = AccessoryProfile.randomAdapterAddress(localAdapter))
        }

        Log.info("配置 $file")
        val profile = effectiveProfile
        Log.info("配件身份 ${profile.serial}，蓝牙地址 ${profile.adapterAddress ?: "本机"}")
        saveLocked()
    }

    /** Rotates the identity and the reported adapter address together, then persists both. */
    fun rotateIdentity(localAdapter: ByteArray?): AccessoryProfile = synchronized(gate) {
        var next = accessory.rotate()
        if (randomizeAdapter && useAdapterOverride) {
            next = next.copy(adapterAddress = AccessoryProfile.randomAdapterAddress(localAdapter))
        }

        accessory = next
        saveLocked()
        effectiveProfile
    }

    /** Settings screen: the switches that live in the file. */
    fun update(randomizeIdentity: Boolean? = null, randomizeAdapter: Boolean? = null, adapterAddress: String? = null) =
        synchronized(gate) {
            randomizeIdentity?.let { this.randomizeIdentity = it }
            randomizeAdapter?.let { this.randomizeAdapter = it }
            adapterAddress?.let { accessory = accessory.copy(adapterAddress = it.ifBlank { null }) }
            saveLocked()
        }

    fun favoritesSnapshot(): List<Favorite> = synchronized(gate) { favorites.toList() }

    fun addFavorite(name: String?, latitude: Double, longitude: Double, altitude: Double?, accuracy: Double?): Favorite {
        val favorite = Favorite(
            newId(),
            if (name.isNullOrBlank()) label(latitude, longitude) else name.trim(),
            latitude,
            longitude,
            altitude,
            accuracy,
        )

        synchronized(gate) {
            favorites.add(0, favorite)
            while (favorites.size > 200) favorites.removeAt(favorites.size - 1)
            saveLocked()
        }

        return favorite
    }

    fun removeFavorite(id: String): Boolean = synchronized(gate) {
        val removed = favorites.removeAll { it.id == id }
        if (removed) saveLocked()
        removed
    }

    fun save() = synchronized(gate) { saveLocked() }

    private fun saveLocked() {
        try {
            file.parentFile?.mkdirs()
            val profile = accessory
            val root = obj(
                "randomizeIdentity" to randomizeIdentity,
                "randomizeAdapter" to randomizeAdapter,
                "accessory" to obj(
                    "name" to profile.name,
                    "model" to profile.model,
                    "manufacturer" to profile.manufacturer,
                    "serial" to profile.serial,
                    "firmware" to profile.firmware,
                    "hardware" to profile.hardware,
                    "adapterAddress" to profile.adapterAddress,
                ),
                "favorites" to favorites.map { it.toJson() },
            )
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(JsonPretty.encodeToString(JsonObject.serializer(), root))
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
        } catch (ex: Exception) {
            Log.warn("配置保存失败（$file）：${ex.message}")
        }
    }
}
