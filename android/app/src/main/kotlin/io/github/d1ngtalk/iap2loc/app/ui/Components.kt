package io.github.d1ngtalk.iap2loc.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.d1ngtalk.iap2loc.app.ui.theme.Bad
import io.github.d1ngtalk.iap2loc.app.ui.theme.Ok
import io.github.d1ngtalk.iap2loc.app.ui.theme.Warn

/** How a checklist row is doing. */
enum class Check { DONE, ACTIVE, PENDING, WARN, FAILED }

@Composable
fun CheckIcon(state: Check, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (state) {
            Check.DONE -> Icon(Icons.Filled.CheckCircle, contentDescription = "完成", tint = Ok)
            Check.ACTIVE -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Check.PENDING -> Box(
                Modifier.size(14.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Check.WARN -> Icon(Icons.Filled.Warning, contentDescription = "注意", tint = Warn)
            Check.FAILED -> Icon(Icons.Filled.Close, contentDescription = "失败", tint = Bad)
        }
    }
}

/** A row in a status checklist: icon, title, an explanation and an optional action. */
@Composable
fun StatusRow(
    state: Check,
    title: String,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        CheckIcon(state, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                action()
            }
        }
    }
}

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

enum class Tone { INFO, WARN, ERROR, OK }

/** A coloured hint box for tips and warnings. */
@Composable
fun Callout(text: String, tone: Tone = Tone.INFO) {
    val (color, icon) = when (tone) {
        Tone.INFO -> MaterialTheme.colorScheme.primary to Icons.Filled.Info
        Tone.WARN -> Warn to Icons.Filled.Warning
        Tone.ERROR -> Bad to Icons.Filled.Warning
        Tone.OK -> Ok to Icons.Filled.CheckCircle
    }
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.09f), MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Numbered instructions, the main tool of the beginner guide. */
@Composable
fun NumberedSteps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(12.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, not only the small switch.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            MaterialHintText(body)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun MaterialHintText(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Runs [onResume] whenever the screen comes back, e.g. after a system settings page. */
@Composable
fun OnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest = rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest.value() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

const val SOURCE_URL = "https://github.com/D1ngtalk/iap2loc"
