package io.github.d1ngtalk.iap2loc.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.d1ngtalk.iap2loc.protocol.AccessoryProfile
import io.github.d1ngtalk.iap2loc.transport.Bluetooth
import io.github.d1ngtalk.iap2loc.transport.BluetoothDeviceInfo
import io.github.d1ngtalk.iap2loc.transport.BluetoothPlatform
import io.github.d1ngtalk.iap2loc.transport.Transport
import java.io.IOException
import java.util.UUID

/** The bt transport on Android: bonded devices from BluetoothAdapter, RFCOMM through BluetoothSocket. */
class AndroidBluetooth(private val context: Context) : BluetoothPlatform {
    companion object {
        /** The iPhone's iAP2 device service. */
        val IAP2: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

        /** Some Android stacks report 128-bit SDP UUIDs byte-reversed. */
        private val IAP2_REVERSED: UUID = UUID.fromString("fecacade-afde-cade-defa-cade00000000")

        /** What BluetoothAdapter.getAddress() returns to apps since Android 6. */
        private const val HIDDEN_ADDRESS = "02:00:00:00:00:00"
    }

    val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isSupported: Boolean get() = adapter != null

    val isEnabled: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Listing bonded devices and connecting. */
    fun canConnect(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** Discovering unpaired devices: its own permission on 12+, location before that. */
    fun canScan(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    fun bonded(): List<BluetoothDevice> {
        val current = adapter ?: return emptyList()
        if (!canConnect()) return emptyList()
        return runCatching { current.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun describe(device: BluetoothDevice): BluetoothDeviceInfo {
        val name = runCatching { device.name }.getOrNull()?.ifBlank { null } ?: "(未命名)"
        val address = device.address.uppercase()
        return BluetoothDeviceInfo(
            address = address,
            name = name,
            connected = false,
            remembered = true,
            paired = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
            likelyApple = hasIap2(device) || Bluetooth.isLikelyApple(name, address, isPhone(device)),
        )
    }

    @SuppressLint("MissingPermission")
    fun isPhone(device: BluetoothDevice): Boolean =
        runCatching { device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }.getOrDefault(false)

    /** True when the cached SDP record lists the iAP2 service. Empty until a query ran once. */
    @SuppressLint("MissingPermission")
    fun hasIap2(device: BluetoothDevice): Boolean =
        runCatching { device.uuids.orEmpty().any { it.uuid == IAP2 || it.uuid == IAP2_REVERSED } }.getOrDefault(false)

    fun hasIap2(uuids: Array<out android.os.Parcelable>?): Boolean =
        uuids.orEmpty().any { (it as? android.os.ParcelUuid)?.uuid.let { uuid -> uuid == IAP2 || uuid == IAP2_REVERSED } }

    override fun devices(): List<BluetoothDeviceInfo> = bonded().map(::describe)
        .sortedWith(compareByDescending<BluetoothDeviceInfo> { it.likelyApple }.thenBy { it.name })

    /**
     * Android hides the adapter address from apps; Settings.Secure still has it on some older builds.
     * Null is fine: the config then reports a generated address in identification parameter 17.
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    override fun localAdapterAddress(): ByteArray? {
        val candidates = listOfNotNull(
            runCatching { if (canConnect()) adapter?.address else null }.getOrNull(),
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull(),
        )

        return candidates.firstOrNull { it.isNotBlank() && !it.equals(HIDDEN_ADDRESS, ignoreCase = true) }
            ?.let(AccessoryProfile::parseAddress)
    }

    override fun connect(address: String, timeoutSeconds: Int): Transport {
        val current = adapter ?: throw IOException("这部手机没有蓝牙")
        if (!canConnect()) throw IOException("缺少「附近的设备」权限：请在新手引导或系统设置中允许 iap2loc 使用蓝牙")
        if (!isEnabled) throw IOException("蓝牙未开启：请先打开手机蓝牙")
        return AndroidRfcommTransport.connect(current, address, timeoutSeconds)
    }
}
