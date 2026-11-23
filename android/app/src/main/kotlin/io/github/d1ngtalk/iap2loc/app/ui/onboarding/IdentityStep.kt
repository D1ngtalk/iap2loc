package io.github.d1ngtalk.iap2loc.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.platform.ImportedIdentity
import io.github.d1ngtalk.iap2loc.app.ui.Callout
import io.github.d1ngtalk.iap2loc.app.ui.Check
import io.github.d1ngtalk.iap2loc.app.ui.SectionCard
import io.github.d1ngtalk.iap2loc.app.ui.StatusRow
import io.github.d1ngtalk.iap2loc.app.ui.Tone
import io.github.d1ngtalk.iap2loc.protocol.IdentityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ColumnScope.IdentityStep(nav: Nav) {
    val identity = LocalContext.current.app.identity
    var confirmSkip by remember { mutableStateOf(false) }
    val present = remember(IdentityRevision.intValue) { identity.isPresent }

    StepLayout(
        nav,
        title = "导入 MFi 身份",
        subtitle = "iPhone 只接受通过 MFi 认证的配件发来的定位。认证需要你合法取得的配件身份：" +
            "identity.pk8（PKCS#8 格式的 P-256 私钥）和 certificate.p7b（证书链）。",
        nextLabel = if (present) "下一步" else "跳过",
        onNext = { if (present) nav.next() else confirmSkip = true },
    ) {
        IdentityDetails()
        Callout("文件只保存在本 App 的私有目录里，不会被备份、同步或上传。可以一次选择两个文件，也可以选择包含它们的 .zip 压缩包。")
    }

    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text("不导入身份继续？") },
            text = { Text("没有 MFi 身份也能连接和识别，但认证不会通过，iPhone 不会采用 iap2loc 发送的定位。之后可以在「设置」里导入。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSkip = false
                    nav.next()
                }) { Text("仍然跳过") }
            },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("返回导入") } },
        )
    }
}

/** Bumped after an import so every identity view re-reads the files. */
private val IdentityRevision = mutableIntStateOf(0)

/** The import button and the inspect-identity summary; shared with the settings screen. */
@Composable
fun IdentityDetails() {
    val context = LocalContext.current
    val identity: ImportedIdentity = context.app.identity
    val scope = rememberCoroutineScope()
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val revision = IdentityRevision.intValue
    val report = remember(revision) { identity.report() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { identity.import(context.contentResolver, uris) }
            problems = outcome.problems + listOfNotNull(
                "只导入了私钥，还需要 certificate.p7b".takeIf { outcome.keyImported && !outcome.certificateImported && !identity.isPresent },
                "只导入了证书，还需要 identity.pk8".takeIf { outcome.certificateImported && !outcome.keyImported && !identity.isPresent },
            )
            busy = false
            IdentityRevision.intValue++
        }
    }

    SectionCard("MFi 身份") {
        when {
            report == null -> StatusRow(Check.WARN, "尚未导入", "选择 identity.pk8 和 certificate.p7b 两个文件。")
            report.isFailure -> StatusRow(Check.FAILED, "文件无法使用", report.exceptionOrNull()?.message)
            else -> ReportRows(report.getOrThrow())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = !busy, onClick = { pick.launch(arrayOf("*/*")) }) {
                Text(if (identity.isPresent) "重新选择文件" else "选择文件")
            }
            if (identity.isPresent) {
                OutlinedButton(onClick = {
                    identity.delete()
                    problems = emptyList()
                    IdentityRevision.intValue++
                }) { Text("删除") }
            }
        }
    }

    for (problem in problems) Callout(problem, Tone.ERROR)
}

@Composable
private fun ReportRows(report: IdentityReport) {
    val now = Instant.now()
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    StatusRow(
        if (report.keyBits == 256) Check.DONE else Check.FAILED,
        "私钥：P-${report.keyBits}",
        if (report.keyBits == 256) null else "MFi 3.0 需要 P-256 私钥",
    )

    val leaf = report.certificates.first()
    StatusRow(
        if (report.certificates.all { it.isValidAt(now) }) Check.DONE else Check.WARN,
        "证书：${report.certificates.size} 张",
        "主题 ${leaf.subject}\n有效期 ${format.format(leaf.notBefore)} 至 ${format.format(leaf.notAfter)}" +
            if (report.certificates.any { !it.isValidAt(now) }) "\n有证书不在有效期内，iPhone 可能拒绝认证" else "",
    )

    when (report.keyMatchesCertificate) {
        true -> StatusRow(Check.DONE, "私钥与证书匹配")
        false -> StatusRow(Check.WARN, "私钥与证书不匹配", "证书里的公钥不属于这把私钥，认证大概率会失败。请确认两个文件来自同一个身份。")
        null -> StatusRow(Check.PENDING, "无法检查私钥与证书是否匹配", "证书不是 EC 公钥，可以继续测试。")
    }
}
