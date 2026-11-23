package io.github.d1ngtalk.iap2loc.app.ui.onboarding

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Parcelable
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.d1ngtalk.iap2loc.R
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.ui.Callout
import io.github.d1ngtalk.iap2loc.app.ui.NumberedSteps
import io.github.d1ngtalk.iap2loc.app.ui.OnResume
import io.github.d1ngtalk.iap2loc.app.ui.SectionCard
import io.github.d1ngtalk.iap2loc.app.ui.Tone
import io.github.d1ngtalk.iap2loc.app.ui.theme.Ok
import io.github.d1ngtalk.iap2loc.transport.Bluetooth

private class Found(val device: BluetoothDevice, val name: String?, val phone: Boolean)

@SuppressLint("MissingPermission")
@Composable
fun ColumnScope.PairingStep(nav: Nav) {
    val context = LocalContext.current
    val bluetooth = context.app.bluetooth
    val prefs = context.app.prefs

    var refresh by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(prefs.deviceAddress) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Found>() }
    val iap2 = remember { mutableStateMapOf<String, Boolean>() }
    OnResume { refresh++ }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        if (found.any { it.device.address == device.address }) return
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { device.name }.getOrNull()
                        val type = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                        found.add(Found(device, name, type?.majorDeviceClass == BluetoothClass.Device.Major.PHONE))
                    }

                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
                            selected = device.address
                            prefs.deviceAddress = device.address
                            prefs.deviceName = runCatching { device.name }.getOrNull()
                            runCatching { device.fetchUuidsWithSdp() }
                        }
                        refresh++
                    }

                    BluetoothDevice.ACTION_UUID -> {
                        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        @Suppress("DEPRECATION")
                        val uuids: Array<Parcelable>? = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                        iap2[device.address] = bluetooth.hasIap2(uuids) || bluetooth.hasIap2(device)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_UUID)
        }
        // Sent by the Bluetooth process, which is another app as far as receiver export rules go.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            if (bluetooth.canScan()) runCatching { bluetooth.adapter?.cancelDiscovery() }
        }
    }

    val canConnect = remember(refresh) { bluetooth.canConnect() && bluetooth.isEnabled }
    val bonded = remember(refresh, canConnect) {
        bluetooth.bonded().map { it to bluetooth.describe(it) }.sortedByDescending { it.second.likelyApple }
    }
    val visibleBonded = bonded.filter { showAll || it.second.likelyApple || it.first.address == selected }
    val selectedDevice = bonded.firstOrNull { it.first.address == selected }

    var confirmLater by remember { mutableStateOf(false) }

    StepLayout(
        nav,
        title = "配对 iPhone",
        subtitle = "两台手机需要先通过蓝牙配对一次，以后打开 iap2loc 就能直接连接。",
        nextLabel = if (selectedDevice != null) "下一步" else "稍后配对",
        onNext = {
            val (device, info) = selectedDevice ?: run {
                confirmLater = true
                return@StepLayout
            }
            prefs.deviceAddress = device.address
            prefs.deviceName = info.name
            nav.next()
        },
    ) {
        if (!canConnect) {
            Callout("需要先允许「附近的设备」权限并打开蓝牙。请回到上一步完成设置。", Tone.WARN)
            return@StepLayout
        }

        SectionCard("操作步骤") {
            NumberedSteps(
                listOf(
                    "在 iPhone 上打开「设置 → 蓝牙」，确认蓝牙已开启，并停留在这个页面，这样 iPhone 才能被搜到。",
                    "点下面的「搜索附近设备」，找到你的 iPhone 后点「配对」。",
                    "两台手机都会弹出配对请求：确认显示的数字一致，然后两边都点「配对」。",
                    "配对完成后，在「已配对的设备」里选中这台 iPhone。",
                ),
            )
        }

        SectionCard("已配对的设备") {
            if (visibleBonded.isEmpty()) {
                MaterialHint(if (bonded.isEmpty()) "还没有配对任何设备。" else "没有看起来像 iPhone 的设备，可以勾选「显示全部」。")
            }
            for ((device, info) in visibleBonded) {
                val chosen = device.address == selected
                Row(
                    Modifier.fillMaxWidth().clickable {
                        selected = device.address
                        prefs.deviceAddress = device.address
                        prefs.deviceName = info.name
                        if (iap2[device.address] == null && !bluetooth.hasIap2(device)) runCatching { device.fetchUuidsWithSdp() }
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info.name, style = MaterialTheme.typography.bodyLarge)
                        MaterialHint(info.address + if (info.likelyApple) " · Apple 设备" else "")
                    }
                    if (bluetooth.hasIap2(device) || iap2[device.address] == true) {
                        Text("iAP2 ✓", color = Ok, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(Modifier.clickable { showAll = !showAll }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showAll, onCheckedChange = { showAll = it })
                Text("显示全部已配对设备", style = MaterialTheme.typography.bodyMedium)
            }
        }

        selectedDevice?.let { (device, _) ->
            when {
                bluetooth.hasIap2(device) || iap2[device.address] == true ->
                    Callout("已在这台设备上检测到 iPhone 的 iAP2 服务，可以继续。", Tone.OK)
                iap2[device.address] == false ->
                    Callout("暂未检测到 iAP2 服务。部分安卓手机读不到这条记录，不一定影响连接，下一步测试一下就知道。", Tone.WARN)
            }
        }

        SectionCard("附近的设备") {
            if (!bluetooth.canScan()) {
                MaterialHint("搜索需要「附近的设备」权限（Android 11 及以下为「位置」权限）。也可以用下面的系统蓝牙设置配对。")
            } else {
                if (context.discoveryNeedsLocation()) {
                    Callout("Android 11 及以下需要打开系统的「位置信息」开关才能搜到设备。", Tone.WARN)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        val adapter = bluetooth.adapter ?: return@FilledTonalButton
                        if (scanning) {
                            runCatching { adapter.cancelDiscovery() }
                        } else {
                            found.clear()
                            runCatching { adapter.startDiscovery() }
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (scanning) "停止搜索" else "搜索附近设备")
                    }
                    if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                val candidates = found
                    .filter { it.device.bondState != BluetoothDevice.BOND_BONDED }
                    .filter { showAll || it.name != null }
                    .sortedByDescending { Bluetooth.isLikelyApple(it.name.orEmpty(), it.device.address, it.phone) }
                for (candidate in candidates) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(candidate.name ?: "(未命名)", style = MaterialTheme.typography.bodyLarge)
                            MaterialHint(candidate.device.address)
                        }
                        if (candidate.device.bondState == BluetoothDevice.BOND_BONDING) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(onClick = {
                                runCatching { bluetooth.adapter?.cancelDiscovery() }
                                runCatching { candidate.device.createBond() }
                                refresh++
                            }) { Text("配对") }
                        }
                    }
                }
                if (!scanning && found.isNotEmpty() && candidates.isEmpty()) MaterialHint("没有找到未配对的设备。确认 iPhone 停留在「设置 → 蓝牙」页面后再搜一次。")
            }
        }

        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
            Text("改用系统蓝牙设置配对")
        }
    }

    if (confirmLater) {
        AlertDialog(
            onDismissRequest = { confirmLater = false },
            title = { Text("稍后再配对？") },
            text = {
                Text("还没有选中已配对的 iPhone，连接测试会先跳过。配对后可以在控制台「设备」里直接连接，或从控制台菜单重新打开新手引导。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLater = false
                    nav.goTo(Step.IPHONE)
                }) { Text("稍后配对") }
            },
            dismissButton = { TextButton(onClick = { confirmLater = false }) { Text("继续配对") } },
        )
    }
}
