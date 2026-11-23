package io.github.d1ngtalk.iap2loc.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.d1ngtalk.iap2loc.Log
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.engine.EngineHost
import io.github.d1ngtalk.iap2loc.app.search.SearchProvider
import io.github.d1ngtalk.iap2loc.app.ui.theme.Iap2LocTheme
import io.github.d1ngtalk.iap2loc.geocode.Place
import io.github.d1ngtalk.iap2loc.geocode.PlaceSearchException
import io.github.d1ngtalk.iap2loc.protocol.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * Looks a place name up with the service chosen in 地点搜索设置 and hands the picked candidate back
 * to the console, which moves the target there.
 */
class PlaceSearchActivity : ComponentActivity() {
    /** Opens the search; the result is the picked place, or null when the user backs out. */
    class Pick : ActivityResultContract<Unit, Place?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, PlaceSearchActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Place? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
            val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            return Place(intent.getStringExtra(EXTRA_NAME).orEmpty(), intent.getStringExtra(EXTRA_ADDRESS).orEmpty(), latitude, longitude)
        }
    }

    private sealed interface Outcome {
        data object Idle : Outcome
        data object Searching : Outcome
        data class Found(val query: String, val places: List<Place>) : Outcome
        data class Failed(val message: String, val needsSettings: Boolean = false) : Outcome
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Iap2LocTheme { SearchScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SearchScreen() {
        var settings by remember { mutableStateOf(app.prefs.search) }
        var query by rememberSaveable { mutableStateOf("") }
        var outcome by remember { mutableStateOf<Outcome>(Outcome.Idle) }
        var running by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(Unit) { focus.requestFocus() }
        // Back from the settings page with another service or a new key.
        OnResume { settings = app.prefs.search }

        fun search() {
            val text = query.trim()
            if (text.isEmpty()) return
            running?.cancel()
            settings.missing?.let {
                outcome = Outcome.Failed("请先在地点搜索设置里填写$it。", needsSettings = true)
                return
            }

            keyboard?.hide()
            outcome = Outcome.Searching
            val provider = settings.provider
            val client = settings.client(this)
            // Services with a location bias rank hits around the current target first.
            val near = EngineHost.engine?.manager?.target?.snapshot()?.let { GeoPoint(it.latitude, it.longitude) }
            running = scope.launch {
                outcome = try {
                    Outcome.Found(text, withContext(Dispatchers.IO) { client.search(text, near) })
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    val message = when (ex) {
                        is PlaceSearchException -> ex.message ?: "搜索失败"
                        is IOException -> "网络错误：${ex.message ?: ex.javaClass.simpleName}"
                        else -> "搜索失败：${ex.message ?: ex.javaClass.simpleName}"
                    }
                    Log.warn("地点搜索失败（${provider.label}）：$message")
                    Outcome.Failed(message)
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("搜索地点") },
                    navigationIcon = {
                        IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    },
                    actions = {
                        IconButton(onClick = ::openSettings) { Icon(Icons.Filled.Settings, contentDescription = "地点搜索设置") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                    placeholder = { Text("地名、地址或景点") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                outcome = Outcome.Idle
                                focus.requestFocus()
                            }) { Icon(Icons.Filled.Clear, contentDescription = "清空") }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MaterialHintText("服务商：${settings.provider.label}", Modifier.weight(1f))
                    TextButton(onClick = ::openSettings) { Text("更换") }
                }

                when (val current = outcome) {
                    Outcome.Idle -> MaterialHintText(
                        "输入后按键盘上的搜索键。选中一个结果，模拟位置会立即切换过去（单点模式）。",
                        Modifier.padding(16.dp),
                    )

                    Outcome.Searching -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))

                    is Outcome.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Callout(current.message, Tone.ERROR)
                        if (current.needsSettings) OutlinedButton(onClick = ::openSettings) { Text("打开地点搜索设置") }
                    }

                    is Outcome.Found -> if (current.places.isEmpty()) {
                        MaterialHintText("没有找到「${current.query}」。换个关键词，或在设置里换一个服务商试试。", Modifier.padding(16.dp))
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(current.places) { place ->
                                PlaceRow(place) { pick(place) }
                                HorizontalDivider()
                            }
                            item { Attribution(settings.provider) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaceRow(place: Place, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(place.name) },
            supportingContent = {
                Column {
                    if (place.address.isNotEmpty()) Text(place.address)
                    Text(
                        String.format(Locale.ROOT, "%.6f, %.6f", place.latitude, place.longitude),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            leadingContent = { Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }

    @Composable
    private fun Attribution(provider: SearchProvider) {
        val credit = if (provider.openStreetMap) "，地点数据 © OpenStreetMap 贡献者" else ""
        MaterialHintText("结果来自 ${provider.label}$credit。列表中的坐标为 WGS-84。", Modifier.padding(16.dp))
    }

    private fun openSettings() = startActivity(Intent(this, SearchSettingsActivity::class.java))

    private fun pick(place: Place) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_NAME, place.name)
                .putExtra(EXTRA_ADDRESS, place.address)
                .putExtra(EXTRA_LATITUDE, place.latitude)
                .putExtra(EXTRA_LONGITUDE, place.longitude),
        )
        finish()
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_LATITUDE = "latitude"
        private const val EXTRA_LONGITUDE = "longitude"
    }
}
