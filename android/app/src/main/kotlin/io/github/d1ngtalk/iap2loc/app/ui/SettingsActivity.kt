package io.github.d1ngtalk.iap2loc.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.d1ngtalk.iap2loc.BuildConfig
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.engine.EngineHost
import io.github.d1ngtalk.iap2loc.app.ui.onboarding.IdentityDetails
import io.github.d1ngtalk.iap2loc.app.ui.onboarding.OnboardingActivity
import io.github.d1ngtalk.iap2loc.app.ui.theme.Iap2LocTheme
import io.github.d1ngtalk.iap2loc.engine.AppConfig
import io.github.d1ngtalk.iap2loc.engine.Engine
import io.github.d1ngtalk.iap2loc.protocol.AccessoryProfile
import io.github.d1ngtalk.iap2loc.transport.Bluetooth

/** Everything the desktop sets with command-line flags, as switches. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Iap2LocTheme { SettingsScreen() } }
    }

    /** The running engine's config, or the file itself while the service is down. */
    private fun config(): AppConfig = EngineHost.engine?.config ?: AppConfig.load(EngineHost.configFile(this))

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        val prefs = app.prefs
        val status by EngineHost.status.collectAsState()
        var revision by remember { mutableIntStateOf(0) }
        val config = remember(revision, status) { config() }
        var addressDialog by remember { mutableStateOf(false) }
        var portText by remember { mutableStateOf(prefs.port.toString()) }
        var lan by remember { mutableStateOf(prefs.lanAccess) }
        var verbose by remember { mutableStateOf(prefs.verboseLog) }
        var pendingRestart by remember { mutableStateOf(false) }
        var searchProvider by remember { mutableStateOf(prefs.search.provider) }
        OnResume { searchProvider = prefs.search.provider }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IdentityDetails()

                SectionCard("配件身份") {
                    val profile = config.effectiveProfile
                    MaterialHintText("当前：${profile.serial} · ${profile.model}\n上报的蓝牙地址：${profile.adapterAddress ?: "本机（未改写）"}")
                    SwitchRow(
                        "每次启动更换配件身份",
                        "iOS 会按配件记住上次的位置，换一个序列号能避免它沿用旧定位。对应桌面版 --fixed-identity 的反面。",
                        config.randomizeIdentity,
                    ) {
                        config.update(randomizeIdentity = it)
                        revision++
                    }
                    SwitchRow(
                        "上报随机蓝牙地址",
                        "安卓不允许 App 读取本机蓝牙地址，关闭后需要手动填写，否则 iPhone 会拒绝识别。",
                        config.randomizeAdapter,
                    ) {
                        config.update(randomizeAdapter = it)
                        revision++
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            EngineHost.engine?.manager?.rotateIdentity(Bluetooth.localAdapterAddress())
                                ?: config.rotateIdentity(Bluetooth.localAdapterAddress())
                            revision++
                        }) { Text("立即更换身份与地址") }
                        OutlinedButton(onClick = { addressDialog = true }) { Text("手动填写地址") }
                    }
                    MaterialHintText("更换后对之后新连接的设备生效。")
                }

                SectionCard("控制台") {
                    SwitchRow(
                        "允许局域网访问",
                        "让同一 Wi-Fi 下的电脑或平板用浏览器打开控制台。开启后同一网络里的任何人都能操作，用完请关闭。",
                        lan,
                    ) {
                        lan = it
                        prefs.lanAccess = it
                        pendingRestart = true
                    }
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { text ->
                            portText = text.filter(Char::isDigit).take(5)
                            portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let {
                                prefs.port = it
                                pendingRestart = true
                            }
                        },
                        label = { Text("端口（默认 ${Engine.DEFAULT_PORT}）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    val running = status as? EngineHost.Status.Running
                    if (running != null) {
                        val addresses = if (running.lanAccess) Network.lanAddresses().map { "http://$it:${running.port}/" } else emptyList()
                        MaterialHintText(
                            "本机：http://127.0.0.1:${running.port}/" +
                                if (addresses.isEmpty()) "" else "\n局域网：" + addresses.joinToString("\n"),
                        )
                    }
                    if (pendingRestart) {
                        Callout("需要重启控制台才能生效，正在连接的设备会断开。", Tone.WARN)
                        FilledTonalButton(onClick = {
                            pendingRestart = false
                            EngineHost.restart(this@SettingsActivity)
                        }) { Text("立即重启控制台") }
                    }
                }

                SectionCard("地点搜索") {
                    MaterialHintText("控制台顶部的放大镜按地名搜索，选中结果后模拟位置立即切换过去。\n当前服务商：${searchProvider.label}")
                    OutlinedButton(onClick = { startActivity(Intent(this@SettingsActivity, SearchSettingsActivity::class.java)) }) {
                        Text("服务商与 Key")
                    }
                }

                SectionCard("诊断") {
                    SwitchRow("详细协议日志", "在控制台日志里显示 iAP2 收发细节，排查问题时打开。对应桌面版 -v。", verbose) {
                        verbose = it
                        prefs.verboseLog = it
                        Log.verbose = it
                    }
                }

                SectionCard("其他") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { startActivity(Intent(this@SettingsActivity, OnboardingActivity::class.java)) }) {
                            Text("重新运行新手引导")
                        }
                        OutlinedButton(onClick = { startActivity(Intent(this@SettingsActivity, HelpActivity::class.java)) }) {
                            Text("帮助")
                        }
                    }
                    MaterialHintText(
                        "iap2loc ${BuildConfig.VERSION_NAME} · GPL-3.0 开源\n配置文件：${EngineHost.configFile(this@SettingsActivity).path}",
                    )
                    TextButton(onClick = { openUrl(SOURCE_URL) }) { Text("源代码与许可证：$SOURCE_URL") }
                }
            }
        }

        if (addressDialog) {
            AddressDialog(config.effectiveProfile.adapterAddress.orEmpty(), onDismiss = { addressDialog = false }) { address ->
                config.update(randomizeAdapter = true, adapterAddress = address)
                addressDialog = false
                revision++
            }
        }
    }
}

@Composable
private fun AddressDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val valid = AccessoryProfile.parseAddress(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上报的蓝牙地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("填写想在识别信息里上报的蓝牙地址，例如本机地址（设置 → 关于手机 → 状态信息 → 蓝牙地址）。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase().take(17) },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    isError = text.isNotEmpty() && !valid,
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
