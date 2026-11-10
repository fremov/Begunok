package ru.begunok.ui.math

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private class MathWebViewHolder {
    var view: WebView? = null
    var released = false

    fun release() {
        released = true
        view?.let { webView ->
            view = null
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.onPause()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
    }
}

/** The complete article is read from packaged assets, never the original website. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MathWebView(
    lessonId: String,
    lessonIds: Set<String>,
    isDark: Boolean,
    onOpenLesson: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(lessonId) { mutableIntStateOf(0) }
    val openLesson by rememberUpdatedState(onOpenLesson)
    val background = MaterialTheme.colorScheme.surface.toArgb()
    key(lessonId, isDark, attempt) {
        val holder = remember { MathWebViewHolder() }
        val lifecycleOwner = LocalLifecycleOwner.current
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }

        DisposableEffect(lifecycleOwner, holder) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> holder.view?.onResume()
                    Lifecycle.Event.ON_PAUSE -> holder.view?.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                holder.release()
            }
        }
        LaunchedEffect(loading, error) {
            if (loading && error == null) {
                delay(15_000)
                if (loading) {
                    holder.view?.stopLoading()
                    loading = false
                    error = "Локальный просмотрщик не отвечает. Повторите открытие материала."
                }
            }
        }
        Box(modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    try {
                        val provider = MathAssetProvider(context.assets, lessonIds)
                        val selected = provider.lessonUrl(lessonId, isDark)
                        WebView(context).apply {
                            holder.view = this
                            setBackgroundColor(background)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = false
                                allowFileAccess = false
                                allowContentAccess = false
                                @Suppress("DEPRECATION")
                                allowFileAccessFromFileURLs = false
                                @Suppress("DEPRECATION")
                                allowUniversalAccessFromFileURLs = false
                                blockNetworkLoads = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                safeBrowsingEnabled = false
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(true)
                                setGeolocationEnabled(false)
                                mediaPlaybackRequiresUserGesture = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                disabledActionModeMenuItems = WebSettings.MENU_ITEM_SHARE or
                                    WebSettings.MENU_ITEM_WEB_SEARCH or WebSettings.MENU_ITEM_PROCESS_TEXT
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            setDownloadListener { _, _, _, _, _ -> }
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                                    callback.invoke(origin, false, false)
                                }
                                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message) = false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (!request.isForMainFrame || request.method != "GET") return true
                                    val targetId = provider.lessonId(request.url) ?: return true
                                    if (targetId != lessonId) {
                                        openLesson(targetId)
                                        return true
                                    }
                                    // Preserve native theme even for a same-lesson relative href.
                                    if (request.url.encodedQuery != selected.encodedQuery) {
                                        view.loadUrl(selected.buildUpon().encodedFragment(request.url.encodedFragment).build().toString())
                                        return true
                                    }
                                    return false
                                }
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                                    provider.intercept(request.url, request.method, request.isForMainFrame, lessonId)

                                override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                                    if (!holder.released) loading = true
                                }
                                override fun onPageFinished(view: WebView, pageUrl: String?) {
                                    if (!holder.released) loading = false
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, webError: WebResourceError) {
                                    if (request.isForMainFrame && !holder.released) {
                                        loading = false
                                        error = "Не удалось открыть локальный материал. Повторите открытие."
                                    }
                                }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (response.statusCode >= 400 && !holder.released) {
                                        loading = false
                                        error = "Локальный материал недоступен или повреждён. Повторите открытие."
                                    }
                                }
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
                                    handler.cancel()
                                    if (!holder.released) {
                                        loading = false
                                        error = "Локальный просмотрщик недоступен. Повторите открытие."
                                    }
                                }
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    if (!holder.released) {
                                        loading = false
                                        error = "Просмотрщик остановился. Повторите открытие материала."
                                        holder.release()
                                    }
                                    return true
                                }
                            }
                            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onResume() else onPause()
                            loadUrl(selected.toString())
                        }
                    } catch (_: RuntimeException) {
                        holder.release()
                        loading = false
                        error = "Android WebView недоступен. Обновите компонент и повторите открытие материала."
                        FrameLayout(context)
                    }
                },
                onRelease = { holder.release() },
            )
            if (error != null) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error.orEmpty())
                        TextButton(onClick = { attempt++ }) { Text("Повторить открытие") }
                    }
                }
            } else if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
