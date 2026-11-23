package io.github.d1ngtalk.iap2loc.app.ui

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse

/**
 * Web/index.html loads Leaflet from unpkg.com. The APK carries the same Leaflet 1.9.4 files, so
 * the map works where that CDN is slow or blocked; only the map tiles still need the network.
 */
object OfflineAssets {
    private const val PREFIX = "https://unpkg.com/leaflet@1.9.4/dist/"

    fun intercept(context: Context, url: Uri): WebResourceResponse? {
        val text = url.toString()
        if (!text.startsWith(PREFIX)) return null

        val path = text.removePrefix(PREFIX).substringBefore('?')
        if (path.contains("..")) return null

        val mime = when (path.substringAfterLast('.')) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "png" -> "image/png"
            else -> return null
        }

        return runCatching {
            WebResourceResponse(mime, if (mime.startsWith("image")) null else "utf-8", context.assets.open("leaflet/$path"))
        }.getOrNull()
    }
}
