package io.github.d1ngtalk.iap2loc.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.engine.EngineHost
import io.github.d1ngtalk.iap2loc.app.ui.Callout
import io.github.d1ngtalk.iap2loc.app.ui.Check
import io.github.d1ngtalk.iap2loc.app.ui.Guides
import io.github.d1ngtalk.iap2loc.app.ui.NumberedSteps
import io.github.d1ngtalk.iap2loc.app.ui.SectionCard
import io.github.d1ngtalk.iap2loc.app.ui.StatusRow
import io.github.d1ngtalk.iap2loc.app.ui.Tone
import io.github.d1ngtalk.iap2loc.engine.RunRequest
import io.github.d1ngtalk.iap2loc.protocol.IdentificationState
import io.github.d1ngtalk.iap2loc.transport.Bluetooth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Stage(val title: String, val detail: String)

private val stages = listOf(
    Stage("蓝牙连接 iPhone", "打开 iPhone 的 iAP2 蓝牙服务（RFCOMM）"),
    Stage("建立 iAP2 链路", "两边交换握手标记并协商链路参数"),
    Stage("配件识别", "iPhone 接受 iap2loc 作为 GPS 配件"),
    Stage("MFi 认证", "用你的 MFi 身份回答 iPhone 的认证挑战"),
    Stage("iPhone 请求定位", "通常要在下一步按说明操作 iPhone 后才会出现，现在不必等待"),
)

/** Plain-language next steps for whatever went wrong. */
private fun tips(view: SessionView): List<String> {
    val error = view.error.orEmpty()
    return when {
        view.identification == IdentificationState.REJECTED ->
            listOf("iPhone 拒绝了配件识别。点「更换配件身份后重试」；仍不行时在设置里打开详细协议日志，并导出日志反馈。")
        view.authenticationFailed ->
            listOf("iPhone 拒绝了 MFi 证书或签名：请确认导入的是合法、完整且相互匹配的 identity.pk8 与 certificate.p7b。")
        error.contains("未与本机配对") -> listOf("回到上一步，确认 iPhone 出现在「已配对的设备」里；没有的话重新配对。")
        error.contains("蓝牙未开启") || error.contains("权限") -> listOf("回到「权限」一步，允许蓝牙权限并打开蓝牙。")
        error.contains("detect marker") || error.contains("negotiation") || error.contains("detect timed out") ->
            listOf("蓝牙已连上，但 iPhone 没有按 iAP2 回应。请解锁 iPhone 后重试。")
        error.isNotEmpty() -> listOf(
            "解锁 iPhone，停留在「设置 → 蓝牙」页面，并把两台手机放在一起。",
            "在 iPhone 的蓝牙列表里确认这部安卓手机显示为已配对。",
            "关闭再打开两边的蓝牙后重试；仍失败时两边都删除配对记录，再重新配对。",
        )
        else -> emptyList()
    }
}

@Composable
fun ColumnScope.ConnectionTestStep(nav: Nav) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val address = prefs.deviceAddress
    val name = prefs.deviceName ?: address ?: "iPhone"
    val scope = rememberCoroutineScope()
    val view = rememberSessionView(address)
    var starting by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    var best by remember { mutableIntStateOf(0) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }

    fun run(rotate: Boolean = false) {
        if (address == null || starting) return
        starting = true
        startError = null
        best = 0
        scope.launch {
            val engine = EngineHost.awaitEngine(context)
            if (engine == null) {
                startError = "控制台没有启动，请稍后重试"
                starting = false
                return@launch
            }

            withContext(Dispatchers.IO) {
                if (rotate) engine.manager.rotateIdentity(Bluetooth.localAdapterAddress())
                engine.manager.list().filter { it.request.mac.equals(address, ignoreCase = true) }.forEach { engine.manager.remove(it.id) }
                engine.manager.add(RunRequest(transport = "bt", mac = address, rate = 250, label = name))
            }
            starting = false
        }
    }

    LaunchedEffect(address) {
        // Start right away the first time; a test that is already running is kept.
        if (!autoStarted && findSession(address)?.isRunning != true) run()
        autoStarted = true
    }

    // The furthest stage this test reached: a link that dies later must not erase it.
    val reached = maxOf(best, view?.progress ?: 0)
    SideEffect { if (reached > best) best = reached }
    val failedAt = when {
        view == null || starting -> 0
        view.identification == IdentificationState.REJECTED -> 3
        view.authenticationFailed -> 4
        view.error != null -> reached + 1
        else -> 0
    }
    val active = starting || (view != null && view.error == null && !view.authenticationFailed)
    // The provider, not the session: a session that never connected has not resolved its identity.
    val noIdentity = !context.app.identity.isPresent
    val passed = reached >= 4

    StepLayout(
        nav,
        title = "连接测试",
        subtitle = "现在让 iap2loc 连接「$name」，检查蓝牙、iAP2 链路、识别和 MFi 认证是否正常。请解锁 iPhone 并放在旁边。",
        nextLabel = if (passed) "下一步" else "跳过",
    ) {
        if (address == null) {
            Callout("还没有选择 iPhone，请回到上一步。", Tone.WARN)
            return@StepLayout
        }

        SectionCard {
            stages.forEachIndexed { index, stage ->
                val number = index + 1
                val state = when {
                    number <= reached -> Check.DONE
                    number == failedAt -> Check.FAILED
                    number == 4 && reached >= 3 && noIdentity -> Check.WARN
                    number == 5 -> Check.PENDING
                    number == reached + 1 && active -> Check.ACTIVE
                    else -> Check.PENDING
                }
                val detail = if (number == 4 && noIdentity) "未导入 MFi 身份，认证不会通过。可以在设置里导入后重试。" else stage.detail
                StatusRow(state, stage.title, detail)
            }
        }

        when {
            startError != null -> Callout(startError!!, Tone.ERROR)
            view?.error != null -> Callout("出错了：${view.error}", Tone.ERROR)
            passed -> Callout("连接和认证都正常。下一步让 iPhone 改用 iap2loc 发送的位置。", Tone.OK)
        }
        view?.let { tips(it) }.orEmpty().takeIf { it.isNotEmpty() }?.let { NumberedSteps(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = !starting, onClick = { run() }) { Text(if (view == null) "开始测试" else "重新测试") }
            if (view?.identification == IdentificationState.REJECTED) {
                OutlinedButton(enabled = !starting, onClick = { run(rotate = true) }) { Text("更换配件身份后重试") }
            }
        }
    }
}

@Composable
fun ColumnScope.IphoneStep(nav: Nav) {
    val address = LocalContext.current.app.prefs.deviceAddress
    val view = rememberSessionView(address)

    StepLayout(
        nav,
        title = "在 iPhone 上切换定位源",
        subtitle = "iPhone 平时用自己的 GPS、Wi-Fi 和基站定位。按下面的步骤操作一次，让它改用 iap2loc 发送的位置。",
        nextLabel = "进入控制台",
        onNext = nav.finish,
    ) {
        SectionCard {
            when {
                view?.subscribed == true -> StatusRow(Check.DONE, "iPhone 正在接收 iap2loc 的定位", "已发送 ${view.sent} 条")
                view?.authenticated == true -> StatusRow(Check.ACTIVE, "等待 iPhone 请求定位…", "按下面的步骤操作后通常会出现。")
                else -> StatusRow(Check.WARN, "iPhone 尚未连接", "可以进入控制台后在「设备」里连接，再回来按这些步骤操作。")
            }
        }

        SectionCard("操作步骤") { NumberedSteps(Guides.iphoneSteps) }
        Callout(Guides.IOS26, Tone.WARN)
        Callout(Guides.IPHONE_RETRY)

        SectionCard("接下来") {
            MaterialHint(
                "进入控制台后，在地图上点选目标位置（或切换到「路径」模式画路线、导入 GPX），然后点右上角的「应用修改」。" +
                    "默认位置是手机当前位置（未授予位置权限或定位不可用时为上海人民广场附近）。之后随时可以在右上角菜单重新打开这个引导。",
            )
        }
    }
}
