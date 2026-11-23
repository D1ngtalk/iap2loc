package io.github.d1ngtalk.iap2loc.app.ui

import android.location.Geocoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.search.SearchProvider
import io.github.d1ngtalk.iap2loc.app.search.SearchSettings
import io.github.d1ngtalk.iap2loc.app.ui.theme.Iap2LocTheme
import io.github.d1ngtalk.iap2loc.geocode.NominatimSearch
import io.github.d1ngtalk.iap2loc.geocode.PhotonSearch

/** 地点搜索设置: which service the place search uses, and the credentials of every service. */
class SearchSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Iap2LocTheme { SearchSettingsScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SearchSettingsScreen() {
        var settings by remember { mutableStateOf(app.prefs.search) }
        // Saved on every change, like the port field on the main settings page.
        fun update(next: SearchSettings) {
            settings = next
            app.prefs.search = next
        }

        @Composable
        fun Provider(provider: SearchProvider, content: @Composable ColumnScope.() -> Unit) = ProviderCard(
            provider,
            selected = settings.provider == provider,
            onSelect = { update(settings.copy(provider = provider)) },
            content = content,
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("地点搜索设置") },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Callout(
                    "选择控制台「搜索地点」使用的服务商。搜索词会发送给所选服务商，Photon 与腾讯还会收到当前目标坐标，" +
                        "用来优先显示附近的结果。国内服务商返回的 GCJ-02 坐标会自动换算成 WGS-84 再发给 iPhone。",
                )

                Provider(SearchProvider.PHOTON) {
                    ServerField("服务器地址", settings.photonUrl, PhotonSearch.DEFAULT_URL) { update(settings.copy(photonUrl = it)) }
                    MaterialHintText("也可以填写自建的 Photon 服务器。公共服务器由 komoot 免费提供，请不要频繁调用。")
                }

                Provider(SearchProvider.NOMINATIM) {
                    ServerField("服务器地址", settings.nominatimUrl, NominatimSearch.DEFAULT_URL) { update(settings.copy(nominatimUrl = it)) }
                    MaterialHintText("也可以填写自建的 Nominatim 服务器。")
                    TextButton(onClick = { openUrl(NOMINATIM_POLICY) }) { Text("公共服务器使用政策") }
                }

                Provider(SearchProvider.ANDROID) {
                    MaterialHintText(if (Geocoder.isPresent()) "本机提供地理编码服务。" else "本机没有地理编码服务，选它搜索会直接失败。")
                    SwitchRow(
                        "中国境内的结果按 GCJ-02 换算",
                        "Google 在中国大陆返回 GCJ-02 坐标；关闭后把结果当作 WGS-84 直接使用。境外结果不受影响。",
                        settings.geocoderGcj,
                    ) { update(settings.copy(geocoderGcj = it)) }
                }

                Provider(SearchProvider.AMAP) {
                    KeyField("Web服务 Key", settings.amapKey, required = settings.provider == SearchProvider.AMAP) {
                        update(settings.copy(amapKey = it))
                    }
                    KeyField("数字签名私钥（可选）", settings.amapSecret) { update(settings.copy(amapSecret = it)) }
                    MaterialHintText("在高德开放平台「应用管理」创建应用并添加 Key，服务平台选「Web服务」。为 Key 开启数字签名时填写私钥。")
                    TextButton(onClick = { openUrl(AMAP_CONSOLE) }) { Text("打开高德控制台") }
                }

                Provider(SearchProvider.BAIDU) {
                    KeyField("服务端 AK", settings.baiduAk, required = settings.provider == SearchProvider.BAIDU) {
                        update(settings.copy(baiduAk = it))
                    }
                    KeyField("SK（sn 校验，可选）", settings.baiduSk) { update(settings.copy(baiduSk = it)) }
                    MaterialHintText(
                        "在百度地图开放平台创建应用，应用类型选「服务端」。请求校验方式选「sn 校验方式」并填写 SK，" +
                            "或选「IP 白名单校验」并填写 0.0.0.0/0。",
                    )
                    TextButton(onClick = { openUrl(BAIDU_CONSOLE) }) { Text("打开百度控制台") }
                }

                Provider(SearchProvider.TENCENT) {
                    KeyField("Key", settings.tencentKey, required = settings.provider == SearchProvider.TENCENT) {
                        update(settings.copy(tencentKey = it))
                    }
                    KeyField("SK（签名校验，可选）", settings.tencentSk) { update(settings.copy(tencentSk = it)) }
                    MaterialHintText("在腾讯位置服务控制台创建 Key 并勾选「WebServiceAPI」，推荐选择「签名校验」并填写 SK。")
                    TextButton(onClick = { openUrl(TENCENT_CONSOLE) }) { Text("打开腾讯位置服务控制台") }
                }
            }
        }
    }

    private companion object {
        const val NOMINATIM_POLICY = "https://operations.osmfoundation.org/policies/nominatim/"
        const val AMAP_CONSOLE = "https://console.amap.com/dev/key/app"
        const val BAIDU_CONSOLE = "https://lbsyun.baidu.com/apiconsole/key"
        const val TENCENT_CONSOLE = "https://lbs.qq.com/dev/console/application/mine"
    }
}

@Composable
private fun ProviderCard(
    provider: SearchProvider,
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (selected) Text("正在使用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialHintText(provider.summary)
            content()
        }
    }
}

@Composable
private fun ServerField(label: String, value: String, default: String, onChange: (String) -> Unit) {
    val valid = SearchSettings.isServerUrl(value)
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = if (valid) null else ({ Text("需要以 https:// 开头") }),
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    if (value != default) TextButton(onClick = { onChange(default) }) { Text("恢复默认（$default）") }
}

@Composable
private fun KeyField(label: String, value: String, required: Boolean = false, onChange: (String) -> Unit) {
    val missing = required && value.isBlank()
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = if (missing) ({ Text("使用该服务商时必填") }) else null,
        isError = missing,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
