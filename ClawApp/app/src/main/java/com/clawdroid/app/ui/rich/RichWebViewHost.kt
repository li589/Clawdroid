package com.clawdroid.app.ui.rich

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

private const val TAG_PENDING_JS = 0x70C11A71

/**
 * Tiny WebView pool for offline KaTeX / Mermaid / SVG previews.
 */
internal object RichWebViewPool {
    private const val maxSize = 3
    private val lock = Any()
    private val idle = ArrayDeque<WebView>()
    private val live = AtomicInteger(0)

    @SuppressLint("SetJavaScriptEnabled")
    fun acquire(context: android.content.Context): WebView? = synchronized(lock) {
        val existing = idle.pollFirst()
        if (existing != null) {
            live.incrementAndGet()
            return existing
        }
        // Prefer Activity context: applicationContext WebView crashes on some OEM first launches.
        val webView = runCatching { createWebView(context) }
            .recoverCatching { createWebView(context.applicationContext) }
            .getOrNull()
            ?: return null
        live.incrementAndGet()
        webView
    }

    private fun createWebView(context: android.content.Context): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    return url.startsWith("http://") || url.startsWith("https://")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val pending = view?.getTag(TAG_PENDING_JS) as? String
                    if (!pending.isNullOrBlank()) {
                        view.evaluateJavascript(pending, null)
                    }
                }
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            loadUrl("file:///android_asset/richchat/viewer.html")
        }
    }

    fun release(webView: WebView) = synchronized(lock) {
        webView.setTag(TAG_PENDING_JS, null)
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (idle.size < maxSize) {
            webView.loadUrl("file:///android_asset/richchat/viewer.html")
            idle.addLast(webView)
        } else {
            webView.destroy()
        }
        live.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}

@Composable
internal fun RichPreviewWebView(
    mode: String,
    payload: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var host by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        val view = RichWebViewPool.acquire(context)
        host = view
        onDispose {
            if (view != null) {
                RichWebViewPool.release(view)
            }
            host = null
        }
    }

    val webView = host
    if (webView == null) {
        Text(
            text = "富文本预览不可用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(8.dp)
        )
        return
    }

    val b64 = remember(payload) {
        Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    val js = remember(mode, b64, darkTheme) {
        "window.clawRichRender && window.clawRichRender('$mode','$b64',${if (darkTheme) "true" else "false"});"
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 360.dp),
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        update = { view ->
            view.setTag(TAG_PENDING_JS, js)
            view.post { view.evaluateJavascript(js, null) }
        }
    )
}
