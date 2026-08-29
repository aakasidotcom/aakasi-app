package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.util.NetworkMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var pageLoader: ImageView
    private lateinit var offlineView: View
    private lateinit var splashView: View
    private lateinit var networkMonitor: NetworkMonitor

    private var wasOffline = false
    private var lastUrl: String = "https://www.aakasi.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        pageLoader = findViewById(R.id.page_loader)
        offlineView = findViewById(R.id.offline_view)
        splashView = findViewById(R.id.splash_view)

        networkMonitor = NetworkMonitor(this)

        // Setup animated GIF loader
        setupPageLoader()

        // Splash screen with 3 seconds delay
        setupSplashScreen()

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val externalHosts = listOf("wa.me", "t.me", "twitter.com", "x.com", "facebook.com")
                val isExternalApp = externalHosts.any { url.contains(it) }

                return if (isExternalApp) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        view.loadUrl(url)
                    }
                    true
                } else {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { lastUrl = it }
                if (networkMonitor.isCurrentlyConnected()) {
                    offlineView.visibility = View.GONE
                    if (splashView.visibility != View.VISIBLE) {
                        pageLoader.visibility = View.VISIBLE
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                pageLoader.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoader.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageLoader.visibility = View.GONE
                    offlineView.visibility = View.VISIBLE
                    wasOffline = true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 25) {
                    pageLoader.visibility = View.GONE
                }
            }
        }

        webView.addJavascriptInterface(AndroidShareBridge(this), "AndroidShare")

        // Handle Back button to navigate back in WebView history
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (offlineView.visibility == View.VISIBLE && networkMonitor.isCurrentlyConnected()) {
                    offlineView.visibility = View.GONE
                    reloadWebView()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Setup Network Monitoring and Auto-Reload
        setupNetworkMonitoring()

        handleIncomingIntent(intent)
    }

    private fun setupSplashScreen() {
        splashView.visibility = View.VISIBLE
        lifecycleScope.launch {
            delay(3000)
            splashView.visibility = View.GONE
        }
    }

    private fun setupPageLoader() {
        val imageLoader = ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()

        val request = ImageRequest.Builder(this)
            .data(R.drawable.aakasi_app_loader)
            .target(pageLoader)
            .build()

        imageLoader.enqueue(request)
    }

    private fun setupNetworkMonitoring() {
        lifecycleScope.launch {
            networkMonitor.isOnline.collectLatest { isOnline ->
                if (!isOnline) {
                    wasOffline = true
                    pageLoader.visibility = View.GONE
                    offlineView.visibility = View.VISIBLE
                } else {
                    if (wasOffline || offlineView.visibility == View.VISIBLE) {
                        wasOffline = false
                        offlineView.visibility = View.GONE
                        // Auto reload feature when connection returns
                        reloadWebView()
                    }
                }
            }
        }
    }

    private fun reloadWebView() {
        val currentWvUrl = webView.url
        if (currentWvUrl.isNullOrEmpty() || currentWvUrl == "about:blank" || currentWvUrl.startsWith("data:")) {
            webView.loadUrl(lastUrl)
        } else {
            webView.reload()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Routing rule:
     *  - "ref=app" present -> generated by OUR app's share/copy feature ->
     *    load it straight into the in-app WebView.
     *  - Not present -> generated by the WEBSITE's share/copy feature (or
     *    any other plain aakasi.com link) -> hand off to the real browser
     *    using an EXPLICIT package name. This is the key fix: an explicit
     *    package bypasses Android's App Links resolution completely, so
     *    it can never be routed back to this app again. That implicit-
     *    intent re-resolution was the cause of the previous loop.
     */
    private fun handleIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data

        if (data != null) {
            val referrerTag = data.getQueryParameter("ref")

            if (referrerTag == "app") {
                val targetUrl = data.toString()
                lastUrl = targetUrl
                webView.loadUrl(targetUrl)
            } else {
                openInExternalBrowser(data)
                finish()
            }
        } else if (webView.url == null) {
            webView.loadUrl(lastUrl)
        }
    }

    private fun openInExternalBrowser(uri: Uri) {
        val browserPackage = findDefaultBrowserPackage()

        val browserIntent = Intent(Intent.ACTION_VIEW, uri)
        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (browserPackage != null) {
            // Explicit package -> Android skips App Links resolution
            // entirely for this intent, so it cannot be handed back to us.
            browserIntent.setPackage(browserPackage)
        }

        try {
            startActivity(browserIntent)
        } catch (e: Exception) {
            // Fallback: if the explicit package somehow fails to resolve,
            // try once more with no package restriction at all rather than
            // leaving the user stuck.
            try {
                browserIntent.setPackage(null)
                startActivity(browserIntent)
            } catch (e2: Exception) {
                lastUrl = uri.toString()
                webView.loadUrl(lastUrl)
            }
        }
    }

    /**
     * Resolves the device's actual default browser package (e.g. Chrome),
     * explicitly excluding our own app even if we were ever set as a
     * default handler for https links.
     */
    private fun findDefaultBrowserPackage(): String? {
        val genericWebIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val resolveInfo = packageManager.resolveActivity(
            genericWebIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val candidate = resolveInfo?.activityInfo?.packageName
        return if (candidate != null && candidate != packageName) candidate else null
    }

    inner class AndroidShareBridge(private val activity: AppCompatActivity) {
        @JavascriptInterface
        fun share(text: String, url: String) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$text\n$url")
            }
            activity.startActivity(Intent.createChooser(sendIntent, "Share via"))
        }

        @JavascriptInterface
        fun copyLink(text: String, url: String) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("AAKASI link", "$text\n$url")
            clipboard.setPrimaryClip(clip)
        }
    }
}
