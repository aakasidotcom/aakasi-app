package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
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

                // Optimize rendering for low to high-end devices
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

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

                        // Handle external HTTP/HTTPS links in external browser / apps
                        val host = uri.host?.lowercase() ?: ""
                        val isInternalHost = host == "aakasi.com" || host.endsWith(".aakasi.com") || host == "www.aakasi.com"

                        val isExternalAppDomain = host.contains("play.google.com") ||
                                host.contains("youtube.com") ||
                                host.contains("youtu.be") ||
                                host.contains("facebook.com") ||
                                host.contains("instagram.com") ||
                                host.contains("twitter.com") ||
                                host.contains("x.com") ||
                                host.contains("t.me") ||
                                host.contains("telegram.me") ||
                                host.contains("whatsapp.com") ||
                                host.contains("maps.google.com")

                        if (!isInternalHost || isExternalAppDomain) {
                            return try {
                                val externalIntent = Intent(Intent.ACTION_VIEW, uri)
                                externalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(externalIntent)
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }

                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        canGoBackState = view?.canGoBack() == true
                        url?.let { onPageStarted(it) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        canGoBackState = view?.canGoBack() == true
                        url?.let { onPageFinished(it) }

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

