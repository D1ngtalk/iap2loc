package io.github.d1ngtalk.iap2loc.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.d1ngtalk.iap2loc.BuildConfig
import io.github.d1ngtalk.iap2loc.app.app
import io.github.d1ngtalk.iap2loc.app.engine.EngineHost
import io.github.d1ngtalk.iap2loc.app.ui.onboarding.OnboardingActivity
import io.github.d1ngtalk.iap2loc.app.ui.theme.Iap2LocTheme

/**
 * The full desktop console (Web/index.html) in a WebView, served by the in-process web server.
 * The native top bar adds what only the phone needs: guide, help, settings and exit.
 */
class ConsoleActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var geolocationRequest: Pair<String, GeolocationPermissions.Callback>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        geolocationRequest?.let { (origin, callback) -> callback.invoke(origin, granted.values.any { it }, false) }
        geolocationRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.prefs.onboardingDone && intent.getBooleanExtra(EXTRA_SKIP_ONBOARDING, false).not()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        EngineHost.start(this)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Leaving must not stop streaming; "退出" in the menu does that.
                moveTaskToBack(true)
                Toast.makeText(this@ConsoleActivity, "已切到后台，定位会继续发送。完全退出请用菜单里的「退出」。", Toast.LENGTH_LONG).show()
            }
        })

        setContent { Iap2LocTheme { ConsoleScreen() } }
    }

    override fun onStart() {
        super.onStart()
        // Reopening the app after "停止并退出" in the notification brings the console back.
        if (app.prefs.onboardingDone) EngineHost.start(this)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConsoleScreen() {
        val status by EngineHost.status.collectAsState()
        var menu by remember { mutableStateOf(false) }
        var lanDialog by remember { mutableStateOf(false) }
        var seenRunning by remember { mutableStateOf(false) }

        LaunchedEffect(status) {
            if (status is EngineHost.Status.Running) seenRunning = true
            // The notification's "停止并退出" stopped the service: close the console too.
            if (status is EngineHost.Status.Stopped && seenRunning) finishAndRemoveTask()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("iap2loc") },
                    actions = {
                        IconButton(onClick = { open(HelpActivity::class.java) }) {
                            Icon(Icons.Filled.Info, contentDescription = "帮助")
                        }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val entries = listOf<Pair<String, () -> Unit>>(
                                "新手引导" to { open(OnboardingActivity::class.java) },
                                "iPhone 端切换定位源" to { open(HelpActivity::class.java, HelpActivity.SECTION_IPHONE) },
                                "帮助与故障排查" to { open(HelpActivity::class.java) },
                                "设置" to { open(SettingsActivity::class.java) },
                                "局域网访问" to { lanDialog = true },
                                "刷新页面" to { webView?.reload() },
                                "导出日志" to { shareLog() },
                                "退出" to {
                                    EngineHost.stop(this@ConsoleActivity)
                                    finishAndRemoveTask()
                                },
                            )
                            for ((label, action) in entries) {
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    menu = false
                                    action()
                                })
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val current = status) {
                    is EngineHost.Status.Running -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context -> webView ?: createWebView(context).also { webView = it } },
                        update = { view ->
                            val url = "http://127.0.0.1:${current.port}/"
                            if (loadedUrl != url) {
                                loadedUrl = url
                                view.loadUrl(url)
                            }
                        },
                    )

                    is EngineHost.Status.Failed -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("控制台启动失败：${current.message}", textAlign = TextAlign.Center)
                        Button(onClick = { EngineHost.restart(this@ConsoleActivity) }) { Text("重试") }
                    }

                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }

        if (lanDialog) LanDialog(status) { lanDialog = false }
    }

    @Composable
    private fun LanDialog(status: EngineHost.Status, onDismiss: () -> Unit) {
        val running = status as? EngineHost.Status.Running
        val text = if (running?.lanAccess == true) {
            val addresses = Network.lanAddresses().map { "http://$it:${running.port}/" }
            if (addresses.isEmpty()) {
                "局域网访问已开启，但手机当前没有连接 Wi-Fi。"
            } else {
                "在同一 Wi-Fi 下的电脑或平板浏览器中打开：\n\n" + addresses.joinToString("\n") +
                    "\n\n同一网络内的任何人都能打开这个控制台，用完请在设置中关闭。"
            }
        } else {
            "局域网访问当前关闭，只有这部手机能打开控制台。需要在电脑上操作时，可以在「设置」里开启。"
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("局域网访问") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
            dismissButton = {
                TextButton(onClick = {
                    onDismiss()
                    open(SettingsActivity::class.java)
                }) { Text("设置") }
            },
        )
    }

    private fun open(activity: Class<*>, section: String? = null) {
        startActivity(Intent(this, activity).apply { if (section != null) putExtra(HelpActivity.EXTRA_SECTION, section) })
    }

    private fun shareLog() {
        val log = EngineHost.engine?.server?.logText().orEmpty().ifEmpty { "（暂无日志）" }
        val text = "iap2loc ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n$log"
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(Intent.EXTRA_SUBJECT, "iap2loc 日志"),
                "导出日志",
            ),
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        // Without explicit MATCH_PARENT the WebView sizes itself to the content, and the page's
        // 100dvh layout collapses to a few pixels.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setGeolocationEnabled(true)
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                OfflineAssets.intercept(context, request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                if (host == "127.0.0.1" || host == "localhost") return false
                // Links in the page (help, licences) open in the browser, never inside the console.
                openUrl(request.url.toString())
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            // GPX import: <input type="file"> needs a picker; "*/*" because file managers rarely know .gpx.
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return runCatching { pickFile.launch(Intent.createChooser(pick, "选择 GPX 文件")) }
                    .onFailure {
                        fileCallback = null
                        callback.onReceiveValue(null)
                    }
                    .isSuccess
            }

            // The console's "当前位置" button uses navigator.geolocation.
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (granted) {
                    callback.invoke(origin, true, false)
                } else {
                    geolocationRequest = origin to callback
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }
    }

    companion object {
        /** For automated smoke tests: open the console without the first-run guide. */
        const val EXTRA_SKIP_ONBOARDING = "skipOnboarding"
    }
}
