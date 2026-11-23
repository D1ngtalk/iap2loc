package io.github.d1ngtalk.iap2loc.app.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.ui.Callout
import io.github.d1ngtalk.iap2loc.app.ui.Check
import io.github.d1ngtalk.iap2loc.app.ui.Guides
import io.github.d1ngtalk.iap2loc.app.ui.NumberedSteps
import io.github.d1ngtalk.iap2loc.app.ui.OnResume
import io.github.d1ngtalk.iap2loc.app.ui.SectionCard
import io.github.d1ngtalk.iap2loc.app.ui.StatusRow
import io.github.d1ngtalk.iap2loc.app.ui.Tone
import io.github.d1ngtalk.iap2loc.app.ui.openAppSettings

@Composable
fun ColumnScope.WelcomeStep(nav: Nav) {
    val prefs = LocalContext.current.app.prefs
    var accepted by rememberSaveable { mutableStateOf(prefs.disclaimerAccepted) }

    StepLayout(
        nav,
        title = "欢迎使用 iap2loc",
        subtitle = "iap2loc 让这部安卓手机通过蓝牙扮演一个 MFi 认证的 GPS 配件，把你在地图上选的位置发送给 iPhone。" +
            "整个设置大约 5 分钟，跟着提示一步步来就行。",
        nextLabel = "开始设置",
        nextEnabled = accepted,
    ) {
        SectionCard("你需要准备") {
            StatusRow(Check.DONE, "一部安卓手机", "Android 8.0 及以上、有蓝牙，就是你手上这部。")
            StatusRow(Check.PENDING, "一部 iPhone", "支持 iOS 12 – 27，已在 iOS 12.5、26、27 上测试。")
            StatusRow(
                Check.PENDING,
                "MFi 配件身份文件",
                "合法取得的 identity.pk8（私钥）和 certificate.p7b（证书链）。本 App 不包含任何密钥或证书。",
            )
        }

        SectionCard("它是怎么工作的") {
            NumberedSteps(
                listOf(
                    "安卓手机和 iPhone 通过蓝牙配对。",
                    "iap2loc 用 iAP2 协议连接 iPhone，并用你的 MFi 身份完成认证。",
                    "iPhone 开始向配件要定位后，iap2loc 按你设置的坐标或路径持续发送 GPS 数据。",
                ),
            )
        }

        Callout("仅在自己的设备上用于协议研究与测试。不要把定位伪造用于欺诈或违反服务条款的场景。", Tone.WARN)

        Row(
            Modifier.fillMaxWidth().clickable {
                accepted = !accepted
                prefs.disclaimerAccepted = accepted
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = accepted, onCheckedChange = {
                accepted = it
                prefs.disclaimerAccepted = it
            })
            Text("我已了解，只在自己的设备上使用")
        }
    }
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Asked once and refused without the system showing the dialog again: only settings can help. */
private fun Context.permanentlyDenied(permissions: List<String>, asked: Boolean): Boolean {
    val activity = activity() ?: return false
    return asked && permissions.any { !granted(it) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

@SuppressLint("InlinedApi")
@Composable
fun ColumnScope.PermissionsStep(nav: Nav) {
    val context = LocalContext.current
    val bluetooth = context.app.bluetooth
    var refresh by remember { mutableIntStateOf(0) }
    var askedBluetooth by rememberSaveable { mutableStateOf(false) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var askedLocation by rememberSaveable { mutableStateOf(false) }
    OnResume { refresh++ }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val bluetoothPermissions = if (modern) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Re-read after every permission dialog and whenever the screen comes back from settings.
    val bluetoothGranted = remember(refresh) { bluetoothPermissions.all(context::granted) }
    val enabled = remember(refresh) { bluetooth.isEnabled }
    val notificationsGranted = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.granted(Manifest.permission.POST_NOTIFICATIONS)
    }
    val locationGranted = remember(refresh) {
        context.granted(Manifest.permission.ACCESS_FINE_LOCATION) || context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val ready = bluetoothGranted && enabled

    StepLayout(
        nav,
        title = "允许必要的权限",
        subtitle = "iap2loc 只用这些权限连接 iPhone 和保持后台运行，不会收集或上传任何数据。",
        nextLabel = if (ready) "下一步" else "稍后再说",
    ) {
        SectionCard {
            StatusRow(
                if (bluetoothGranted) Check.DONE else Check.WARN,
                if (modern) "附近的设备（蓝牙）" else "位置（用于搜索蓝牙设备）",
                if (modern) {
                    "用来列出已配对的 iPhone、搜索并配对新设备、建立蓝牙连接。必需。"
                } else {
                    "Android 11 及以下的系统要求「位置」权限才能搜索蓝牙设备，iap2loc 不会读取你的位置。必需。"
                },
            ) {
                if (!bluetoothGranted) {
                    PermissionButtons(context.permanentlyDenied(bluetoothPermissions, askedBluetooth)) {
                        askedBluetooth = true
                        request.launch(bluetoothPermissions.toTypedArray())
                    }
                }
            }

            StatusRow(
                when {
                    !bluetooth.isSupported -> Check.FAILED
                    enabled -> Check.DONE
                    else -> Check.WARN
                },
                "打开蓝牙",
                if (bluetooth.isSupported) "连接 iPhone 时蓝牙必须开着。" else "这部手机没有蓝牙，只能使用 TCP 传输。",
            ) {
                if (bluetooth.isSupported && !enabled) {
                    FilledTonalButton(
                        enabled = bluetoothGranted || !modern,
                        onClick = {
                            runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        },
                    ) { Text("打开蓝牙") }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                StatusRow(
                    if (notificationsGranted) Check.DONE else Check.PENDING,
                    "通知（推荐）",
                    "在通知栏显示运行状态和「停止」按钮；有常驻通知时，系统也更不容易关掉 iap2loc。",
                ) {
                    if (!notificationsGranted) {
                        val permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
                        PermissionButtons(context.permanentlyDenied(permissions, askedNotifications)) {
                            askedNotifications = true
                            request.launch(permissions.toTypedArray())
                        }
                    }
                }
            }

            if (modern) {
                StatusRow(
                    if (locationGranted) Check.DONE else Check.PENDING,
                    "位置（可选）",
                    "控制台启动时以手机当前位置作为默认位置，「当前位置」按钮也要用。不给则默认在上海人民广场附近，可以跳过。",
                ) {
                    if (!locationGranted) {
                        val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        PermissionButtons(context.permanentlyDenied(permissions, askedLocation)) {
                            askedLocation = true
                            request.launch(permissions.toTypedArray())
                        }
                    }
                }
            }
        }

        if (!ready) Callout("蓝牙权限和蓝牙开关是连接 iPhone 必需的。可以先跳过，稍后在这里或系统设置中开启。", Tone.WARN)
    }
}

@Composable
private fun PermissionButtons(denied: Boolean, request: () -> Unit) {
    val context = LocalContext.current
    Row {
        if (denied) {
            FilledTonalButton(onClick = { context.openAppSettings() }) { Text("去系统设置里允许") }
        } else {
            FilledTonalButton(onClick = request) { Text("允许") }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun ColumnScope.BackgroundStep(nav: Nav) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    OnResume { refresh++ }
    val unrestricted = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    StepLayout(
        nav,
        title = "让 iap2loc 在后台持续运行",
        subtitle = "定位每 0.25 秒发送一次。锁屏或切到别的 App 时，系统的省电功能可能会掐断连接，iPhone 的位置就会跳回真实位置。",
    ) {
        SectionCard {
            StatusRow(
                if (unrestricted) Check.DONE else Check.WARN,
                "关闭电池优化",
                "允许 iap2loc 在后台不受限制地运行。只在连接 iPhone 时才保持唤醒。",
            ) {
                if (!unrestricted) {
                    FilledTonalButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                            )
                        }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }) { Text("关闭电池优化") }
                }
            }
        }

        SectionCard("你的手机（${Build.MANUFACTURER}）还需要") {
            NumberedSteps(Guides.backgroundTips())
            OutlinedButton(onClick = { context.openAppSettings() }) { Text("打开 iap2loc 的应用设置") }
        }

        Callout("运行时通知栏会一直显示 iap2loc。要完全停止，点通知里的「停止并退出」或控制台菜单里的「退出」。")
    }
}

/** Android 8–11 only return discovery results while the location switch is on. */
fun Context.discoveryNeedsLocation(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
    val manager = getSystemService(LocationManager::class.java) ?: return false
    val on = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
    }
    return !on
}

@Composable
fun MaterialHint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
