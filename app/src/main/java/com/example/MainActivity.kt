package com.example

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var splashShown = false

    // Register Notification Permission Launcher for Android 13+ (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result handled
    }

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

        // Splash screen with 3 seconds delay (only on fresh launch)
        if (savedInstanceState == null && !splashShown) {
            setupSplashScreen()
        } else {
            splashView.visibility = View.GONE
        }

        // Ask for Notification Permission on initial launch
        askNotificationPermission()

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Clean User Agent:
            // Razorpay Checkout JS checks for '; wv' and 'Version/X.X' in navigator.userAgent.
            // If detected as WebView, Razorpay suppresses UPI Intent options entirely.
            // Stripping '; wv' and 'Version/X.X' allows Razorpay to render all UPI payment options.
            val rawUa = userAgentString
            val cleanedUa = rawUa
                .replace("; wv", "")
                .replace("; wv;", ";")
                .replace(Regex("Version/\\d+(\\.\\d+)*\\s*"), "")
            userAgentString = cleanedUa
        }

        // Enable third-party cookies for payment gateway iframes & 3DS authorization
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Make offline view clickable so user can tap anywhere on it to retry
        offlineView.setOnClickListener {
            if (networkMonitor.isCurrentlyConnected()) {
                offlineView.visibility = View.GONE
                reloadWebView()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return handleUrlLoading(view, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrlLoading(view, url)
            }

            private fun handleUrlLoading(view: WebView, url: String): Boolean {
                // Direct UPI and payment app schemes (GPay, PhonePe, Paytm, BHIM, CRED, Tez, etc.)
                val upiSchemes = listOf("upi://", "tez://", "phonepe://", "paytmmp://", "credpay://", "bhim://", "gpay://", "mobikwik://", "freecharge://", "payzapp://")
                if (upiSchemes.any { url.startsWith(it, ignoreCase = true) }) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                // Razorpay and custom app intent URLs
                if (url.startsWith("intent://", ignoreCase = true)) {
                    return try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                        } else {
                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                            if (!fallbackUrl.isNullOrEmpty()) {
                                view.loadUrl(fallbackUrl)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                if (url.startsWith("market://", ignoreCase = true)) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

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
                url?.let {
                    if (it != "about:blank" && !it.startsWith("data:")) {
                        lastUrl = it
                    }
                }
                if (networkMonitor.isCurrentlyConnected()) {
                    offlineView.visibility = View.GONE
                    if (splashView.visibility != View.VISIBLE) {
                        pageLoader.visibility = View.VISIBLE
                    }
                } else {
                    pageLoader.visibility = View.GONE
                    offlineView.visibility = View.VISIBLE
                    wasOffline = true
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
                    showOfflineScreen()
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
                showOfflineScreen()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 1) {
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

        // Check initial connectivity immediately
        if (!networkMonitor.isCurrentlyConnected()) {
            showOfflineScreen()
        }

        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }

    private fun showOfflineScreen() {
        wasOffline = true
        pageLoader.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
        offlineView.bringToFront()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupSplashScreen() {
        splashShown = true
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
                    showOfflineScreen()
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
        val target = if (lastUrl.isNotEmpty() && lastUrl != "about:blank" && !lastUrl.startsWith("data:")) {
            lastUrl
        } else {
            "https://www.aakasi.com/"
        }
        webView.loadUrl(target)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Routing rule:
     *  - "ref=app" present or payment/checkout redirect or active in-app session ->
     *    load straight into the in-app WebView.
     *  - Plain external link opened when app is not active -> hand off to browser.
     */
    private fun handleIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data

        if (data != null) {
            val referrerTag = data.getQueryParameter("ref")
            val path = data.path?.lowercase() ?: ""
            val query = data.query?.lowercase() ?: ""

            val isPaymentOrOrder = path.contains("order") ||
                    path.contains("checkout") ||
                    path.contains("payment") ||
                    path.contains("cart") ||
                    path.contains("success") ||
                    query.contains("payment") ||
                    query.contains("razorpay") ||
                    query.contains("status") ||
                    query.contains("order")

            if (referrerTag == "app" || isPaymentOrOrder || webView.url != null) {
                val targetUrl = data.toString()
                lastUrl = targetUrl
                if (networkMonitor.isCurrentlyConnected()) {
                    webView.loadUrl(targetUrl)
                } else {
                    showOfflineScreen()
                }
            } else {
                openInExternalBrowser(data)
                finish()
            }
        } else if (webView.url == null) {
            if (networkMonitor.isCurrentlyConnected()) {
                webView.loadUrl(lastUrl)
            } else {
                showOfflineScreen()
            }
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
