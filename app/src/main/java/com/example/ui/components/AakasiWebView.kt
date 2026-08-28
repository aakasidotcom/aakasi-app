package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

class WebViewController {
    var webView: WebView? = null

    fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    fun reload() {
        webView?.reload()
    }

    fun goBack(): Boolean {
        return if (webView?.canGoBack() == true) {
            webView?.goBack()
            true
        } else {
            false
        }
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun clearCache() {
        webView?.clearCache(true)
        webView?.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
    }

    fun setTextZoom(zoomPercent: Int) {
        webView?.settings?.textZoom = zoomPercent
    }
}

@Composable
fun AakasiWebView(
    url: String,
    controller: WebViewController,
    onProgressChanged: (Int) -> Unit,
    onTitleReceived: (String) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageCommitVisible: (String) -> Unit = {},
    onPageFinished: (String) -> Unit,
    onErrorReceived: (String) -> Unit,
    textZoomPercent: Int = 100,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Handle system back button for WebView navigation
    var canGoBackState by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBackState) {
        if (!controller.goBack()) {
            // System back handles when WebView cannot go back
        }
    }

    // File Chooser for WebView (<input type="file">)
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultUri: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val intentData = result.data
            if (intentData?.data != null) {
                arrayOf(intentData.data!!)
            } else if (intentData?.clipData != null) {
                val clipData = intentData.clipData!!
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else {
                null
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(resultUri)
        filePathCallback = null
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // Optimize rendering and scrolling for low to high-end devices
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_OVERLAY
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isNestedScrollingEnabled = true

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    cacheMode = WebSettings.LOAD_DEFAULT
                    textZoom = textZoomPercent
                    userAgentString = userAgentString + " AakasiAndroidApp/1.0"
                    mediaPlaybackRequiresUserGesture = false
                    loadsImagesAutomatically = true
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // 2. Bridge so your page's JS can trigger Android's NATIVE share sheet
                val shareBridge = AndroidShareBridge(ctx)
                addJavascriptInterface(shareBridge, "AndroidShare")
                addJavascriptInterface(shareBridge, "Android")

                // 1. Intercept outgoing links so wa.me / t.me / twitter.com / facebook.com
                //    open the actual installed apps instead of loading inside the WebView.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        val requestUrl = uri.toString()

                        // Handle non-http/https custom schemes (e.g. mailto, tel, whatsapp, intent, tg)
                        if (!requestUrl.startsWith("http://") && !requestUrl.startsWith("https://")) {
                            return try {
                                val intent = if (requestUrl.startsWith("intent://")) {
                                    Intent.parseUri(requestUrl, Intent.URI_INTENT_SCHEME)
                                } else {
                                    Intent(Intent.ACTION_VIEW, uri)
                                }
                                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                                true
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent.parseUri(requestUrl, Intent.URI_INTENT_SCHEME)
                                    val fallbackUrl = intent?.getStringExtra("browser_fallback_url")
                                    if (!fallbackUrl.isNullOrEmpty()) {
                                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                                        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ctx.startActivity(fallbackIntent)
                                    } else {
                                        Toast.makeText(ctx, "No app found to open link", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "No app found to open link", Toast.LENGTH_SHORT).show()
                                }
                                true
                            }
                        }

                        // External hosts: wa.me, t.me, twitter.com, x.com, facebook.com, etc.
                        val externalHosts = listOf(
                            "wa.me",
                            "api.whatsapp.com",
                            "whatsapp.com",
                            "t.me",
                            "telegram.me",
                            "twitter.com",
                            "x.com",
                            "facebook.com",
                            "fb.me",
                            "instagram.com",
                            "play.google.com",
                            "youtube.com",
                            "youtu.be",
                            "maps.google.com"
                        )

                        val shouldOpenExternally = externalHosts.any { requestUrl.contains(it) }

                        if (shouldOpenExternally) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(intent)
                            } catch (e: Exception) {
                                // Target app not installed (e.g. WhatsApp missing) -
                                // fall back to loading the web version instead.
                                view?.loadUrl(requestUrl)
                            }
                            return true // tell WebView we handled it, don't load it internally
                        }

                        // Handle other external HTTP/HTTPS links
                        val host = uri.host?.lowercase() ?: ""
                        val isInternalHost = host == "aakasi.com" || host.endsWith(".aakasi.com") || host == "www.aakasi.com"

                        if (!isInternalHost) {
                            return try {
                                val externalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(externalIntent)
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }

                        // Internal page navigation -> trigger page loader immediately
                        onPageStarted(requestUrl)
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        canGoBackState = view?.canGoBack() == true
                        url?.let { onPageStarted(it) }

                        // Polyfill Web Share API (navigator.share) to hook into AndroidShare bridge
                        val sharePolyfill = """
                            (function() {
                                if (window.AndroidShare) {
                                    window.navigator.share = function(data) {
                                        return new Promise(function(resolve, reject) {
                                            try {
                                                var text = (data && data.text) || (data && data.title) || '';
                                                var url = (data && data.url) || '';
                                                window.AndroidShare.share(text, url);
                                                resolve();
                                            } catch (e) {
                                                reject(e);
                                            }
                                        });
                                    };
                                    if (window.navigator.canShare) {
                                        window.navigator.canShare = function() { return true; };
                                    }
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(sharePolyfill, null)
                    }

                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        super.onPageCommitVisible(view, url)
                        url?.let { onPageCommitVisible(it) }
                        injectSmoothScroll(view)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        canGoBackState = view?.canGoBack() == true
                        url?.let { onPageFinished(it) }
                        injectSmoothScroll(view)

                        // Inject JS helper so WordPress/WooCommerce frontend can read FCM token or post it to WP backend
                        view?.evaluateJavascript(
                            """
                            (function() {
                                window.isAakasiApp = true;
                                window.aakasiAppPlatform = 'android';
                            })();
                            """.trimIndent(),
                            null
                        )
                    }

                    private fun injectSmoothScroll(view: WebView?) {
                        val js = """
                            (function() {
                                try {
                                    if (!document.getElementById('aakasi-smooth-scroll-style')) {
                                        var style = document.createElement('style');
                                        style.id = 'aakasi-smooth-scroll-style';
                                        style.innerHTML = 'html, body { scroll-behavior: smooth !important; -webkit-overflow-scrolling: touch !important; }';
                                        (document.head || document.documentElement).appendChild(style);
                                    }
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            onErrorReceived(error?.description?.toString() ?: "Failed to load page")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        onErrorReceived(description ?: "Failed to load page")
                    }

                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onProgressChanged(newProgress)
                        canGoBackState = view?.canGoBack() == true
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        title?.let { onTitleReceived(it) }
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallbackParam: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = filePathCallbackParam

                        return try {
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "*/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                            fileChooserLauncher.launch(intent)
                            true
                        } catch (e: Exception) {
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = null
                            false
                        }
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?
                    ) {
                        callback?.invoke(origin, true, false)
                    }

                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        val result = view?.hitTestResult
                        val data = result?.extra
                        if (!data.isNullOrEmpty()) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(data))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                                return true
                            } catch (_: Exception) {}
                        } else if (resultMsg != null) {
                            val newWebView = WebView(view?.context ?: ctx)
                            newWebView.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    req: WebResourceRequest?
                                ): Boolean {
                                    req?.url?.toString()?.let { targetUrl ->
                                        controller.loadUrl(targetUrl)
                                    }
                                    return true
                                }
                            }
                            val transport = resultMsg.obj as? WebView.WebViewTransport
                            transport?.webView = newWebView
                            resultMsg.sendToTarget()
                            return true
                        }
                        return false
                    }
                }

                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, contentLength ->
                    try {
                        val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                        val request = android.app.DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                            setMimeType(mimetype)
                            addRequestHeader("User-Agent", userAgent)
                            setDescription("Downloading $fileName...")
                            setTitle(fileName)
                            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                                fileName
                            )
                        }
                        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        dm.enqueue(request)
                        Toast.makeText(ctx, "Download started: $fileName", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                            ctx.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(ctx, "Unable to start download", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                controller.webView = this
                loadUrl(url)
            }
        },
        update = { view ->
            controller.webView = view
            view.settings.textZoom = textZoomPercent
        },
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Bridge class so the page's JS can trigger Android's NATIVE share sheet
 * via window.AndroidShare.share(text, url) or window.Android.share(text, url)
 */
class AndroidShareBridge(private val context: Context) {

    private fun formatUrlWithAppRef(url: String): String {
        if (url.isBlank()) return url
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""
            if (host == "aakasi.com" || host.endsWith(".aakasi.com")) {
                if (uri.getQueryParameter("ref") == null) {
                    uri.buildUpon().appendQueryParameter("ref", "app").build().toString()
                } else {
                    url
                }
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    @JavascriptInterface
    fun share(text: String, url: String) {
        val formattedUrl = formatUrlWithAppRef(url)
        val shareBody = if (formattedUrl.isNotBlank() && text.isNotBlank()) {
            "$text\n$formattedUrl"
        } else {
            text.ifBlank { formattedUrl }
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    @JavascriptInterface
    fun share(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    @JavascriptInterface
    fun share(title: String?, text: String?, url: String?) {
        val formattedUrl = if (!url.isNullOrBlank()) formatUrlWithAppRef(url) else url
        val shareBody = buildString {
            if (!text.isNullOrBlank()) append(text)
            if (!formattedUrl.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(formattedUrl)
            }
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareBody)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

