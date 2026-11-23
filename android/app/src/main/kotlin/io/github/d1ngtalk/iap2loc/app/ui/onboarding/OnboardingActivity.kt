package io.github.d1ngtalk.iap2loc.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.ui.ConsoleActivity
import io.github.d1ngtalk.iap2loc.app.ui.theme.Iap2LocTheme

/**
 * The first-run guide: from "what is this" to an iPhone that receives the location, one decision
 * per screen. It can be reopened from the console menu at any time.
 */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getIntExtra(EXTRA_STEP, 0).coerceIn(0, Step.entries.size - 1)
        val reopened = app.prefs.onboardingDone
        setContent { Iap2LocTheme { OnboardingFlow(start, reopened, ::finishGuide, ::finish) } }
    }

    private fun finishGuide() {
        app.prefs.onboardingDone = true
        startActivity(Intent(this, ConsoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        const val EXTRA_STEP = "step"
    }
}

enum class Step(val title: String) {
    WELCOME("欢迎"),
    PERMISSIONS("权限"),
    BACKGROUND("后台运行"),
    IDENTITY("MFi 身份"),
    PAIRING("配对 iPhone"),
    TEST("连接测试"),
    IPHONE("iPhone 设置"),
}

/** What a step can do with the wizard. */
class Nav(
    val step: Step,
    val next: () -> Unit,
    val back: () -> Unit,
    val finish: () -> Unit,
    /** Jumps over steps that cannot work yet, e.g. the connection test without a paired iPhone. */
    val goTo: (Step) -> Unit,
) {
    val isFirst get() = step.ordinal == 0
    val isLast get() = step.ordinal == Step.entries.size - 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingFlow(start: Int, reopened: Boolean, onFinish: () -> Unit, onClose: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(start) }
    val step = Step.entries[index]
    var confirmSkip by remember { mutableStateOf(false) }
    val nav = Nav(
        step,
        next = { if (index < Step.entries.size - 1) index++ else onFinish() },
        back = { if (index > 0) index-- },
        finish = onFinish,
        goTo = { index = it.ordinal },
    )

    BackHandler(enabled = index > 0) { index-- }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("新手引导 · ${step.title}") },
                    navigationIcon = {
                        if (index > 0) {
                            IconButton(onClick = { index-- }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一步") }
                        }
                    },
                    actions = {
                        // Reopened from the console: leaving early just goes back there. On the first
                        // run there is always a way out, so nobody gets stuck on a step they cannot finish.
                        if (reopened) {
                            TextButton(onClick = onClose) { Text("关闭") }
                        } else {
                            TextButton(onClick = { confirmSkip = true }) { Text("跳过引导") }
                        }
                    },
                )
                LinearProgressIndicator(
                    progress = { (index + 1f) / Step.entries.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                Step.WELCOME -> WelcomeStep(nav)
                Step.PERMISSIONS -> PermissionsStep(nav)
                Step.BACKGROUND -> BackgroundStep(nav)
                Step.IDENTITY -> IdentityStep(nav)
                Step.PAIRING -> PairingStep(nav)
                Step.TEST -> ConnectionTestStep(nav)
                Step.IPHONE -> IphoneStep(nav)
            }
        }
    }

    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text("跳过新手引导？") },
            text = {
                Text("可以直接进入控制台。之后随时可以在控制台右上角菜单 →「新手引导」重新打开，完成权限、MFi 身份导入和 iPhone 配对。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSkip = false
                    onFinish()
                }) { Text("进入控制台") }
            },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("继续引导") } },
        )
    }
}

/** Title, scrolling body and the bottom buttons shared by every step. */
@Composable
fun ColumnScope.StepLayout(
    nav: Nav,
    title: String,
    subtitle: String? = null,
    nextLabel: String = "下一步",
    nextEnabled: Boolean = true,
    onNext: () -> Unit = nav.next,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
        Spacer(Modifier.height(8.dp))
    }

    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!nav.isFirst) OutlinedButton(onClick = nav.back) { Text("上一步") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onNext, enabled = nextEnabled) { Text(nextLabel) }
    }
}
