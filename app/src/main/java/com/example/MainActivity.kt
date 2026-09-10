package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
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
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.util.NetworkMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private var splashDismissed = false
    }

    private lateinit var webView: WebView
    private lateinit var pageLoader: ImageView
    private lateinit var offlineView: View
    private lateinit var splashView: View
    private lateinit var networkMonitor: NetworkMonitor

    private var wasOffline = false
    private var lastUrl: String = "https://www.aakasi.com/"
    private var currentProgress: Int = 0

    // File Chooser state for photo, video, and camera uploads
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    // Register Notification Permission Launcher for Android 13+ (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result handled
    }

    // Register Launcher for File Chooser (Photos, Videos, and Camera Capture)
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uriResult: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val clipData = data.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else if (data.data != null) {
                    arrayOf(data.data!!)
                } else if (cameraPhotoUri != null) {
                    arrayOf(cameraPhotoUri!!)
                } else {
                    null
                }
            } else if (cameraPhotoUri != null) {
                arrayOf(cameraPhotoUri!!)
            } else {
                null
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(uriResult)
        filePathCallback = null
    }

    // Register Launcher for HTML5 WebRTC / Camera & Microphone web permission requests
    private val webMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val request = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (request != null) {
            val grantedResources = mutableListOf<String>()
            for (resource in request.resources) {
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                        if (permissions[Manifest.permission.CAMERA] == true ||
                            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            grantedResources.add(resource)
                        }
                    }
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        if (permissions[Manifest.permission.RECORD_AUDIO] == true ||
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            grantedResources.add(resource)
                        }
                    }
                    else -> grantedResources.add(resource)
                }
            }
            if (grantedResources.isNotEmpty()) {
                request.grant(grantedResources.toTypedArray())
            } else {
                request.deny()
            }
        }
    }

    // Register Launcher for Camera, Microphone, Photos and Videos runtime permissions
    private val appMediaPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Media permissions updated
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // Instantly remove system splash icon so only the exact custom splash layout is displayed
        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            splashScreenViewProvider.remove()
        }
        splashScreen.setKeepOnScreenCondition { false }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        pageLoader = findViewById(R.id.page_loader)
        offlineView = findViewById(R.id.offline_view)
        splashView = findViewById(R.id.splash_view)

        // Remove entire WebView scrollbars
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        networkMonitor = NetworkMonitor(this)

        // Setup animated GIF loader
        setupPageLoader()

        // Display exact uploaded splash screen on cold launch
        if (savedInstanceState == null && !splashDismissed) {
            splashView.visibility = View.VISIBLE
            splashView.bringToFront()
            lifecycleScope.launch {
                delay(3000)
                splashDismissed = true
                splashView.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction {
                        splashView.visibility = View.GONE
                        if (networkMonitor.isCurrentlyConnected() && currentProgress < 100 && webView.visibility == View.VISIBLE) {
                            pageLoader.visibility = View.VISIBLE
                        }
                    }
                    .start()
            }
        } else {
            splashView.visibility = View.GONE
        }

        // Ask for Notification Permission on initial launch
        askNotificationPermission()

        // Ask for Camera, Microphone, Photos and Videos access
        requestMediaPermissions()

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
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

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
            reloadWebView()
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentProgress = 0
                url?.let {
                    if (it != "about:blank" && !it.startsWith("data:")) {
                        lastUrl = it
                    }
                }
                hideOfflineScreen()
                if (splashView.visibility != View.VISIBLE) {
                    pageLoader.visibility = View.VISIBLE
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                hideWebScrollbars(view)
                if (currentProgress >= 100) {
                    pageLoader.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideWebScrollbars(view)
                currentProgress = 100
                pageLoader.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showOfflineScreen(view)
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
                showOfflineScreen(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                currentProgress = newProgress
                if (newProgress >= 100) {
                    pageLoader.visibility = View.GONE
                } else if (newProgress < 100 && networkMonitor.isCurrentlyConnected() && splashView.visibility != View.VISIBLE && offlineView.visibility != View.VISIBLE) {
                    pageLoader.visibility = View.VISIBLE
                }
            }

            // HTML5 Camera & Microphone permission requests (WebRTC / getUserMedia)
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    val requestedResources = request.resources
                    val neededPermissions = mutableListOf<String>()

                    if (requestedResources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            neededPermissions.add(Manifest.permission.CAMERA)
                        }
                    }
                    if (requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
                        }
                    }

                    if (neededPermissions.isEmpty()) {
                        request.grant(requestedResources)
                    } else {
                        pendingWebPermissionRequest = request
                        webMediaPermissionLauncher.launch(neededPermissions.toTypedArray())
                    }
                }
            }

            // File Chooser for Photos, Videos, and Camera Capture
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intentList = mutableListOf<Intent>()

                // Camera photo capture
                try {
                    val photoFile = File.createTempFile("IMG_${System.currentTimeMillis()}_", ".jpg", cacheDir)
                    cameraPhotoUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        photoFile
                    )
                    val takePhotoIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    intentList.add(takePhotoIntent)
                } catch (_: Exception) {}

                // Camera video capture
                try {
                    val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    intentList.add(takeVideoIntent)
                } catch (_: Exception) {}

                // Photo and Video gallery chooser
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select Photo or Video")
                    if (intentList.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, intentList.toTypedArray())
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.addJavascriptInterface(AndroidShareBridge(this), "AndroidShare")

        // Handle Back button to navigate back in WebView history
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (offlineView.visibility == View.VISIBLE) {
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
            showOfflineScreen(webView)
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

    private fun showOfflineScreen(view: WebView? = null) {
        wasOffline = true
        pageLoader.visibility = View.GONE
        try {
            (view ?: webView).stopLoading()
        } catch (_: Exception) {}
        offlineView.visibility = View.VISIBLE
        offlineView.bringToFront()
    }

    private fun hideOfflineScreen() {
        wasOffline = false
        offlineView.visibility = View.GONE
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
                        handleUrlLoading(view, fallbackUrl)
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

        if (url.startsWith("tel:", ignoreCase = true)) {
            return try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                true
            } catch (e: Exception) {
                true
            }
        }

        if (url.startsWith("mailto:", ignoreCase = true) || url.startsWith("sms:", ignoreCase = true)) {
            return try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                true
            } catch (e: Exception) {
                true
            }
        }

        // Keep javascript/data/about:blank schemes in WebView
        if (url.startsWith("javascript:", ignoreCase = true) ||
            url.startsWith("data:", ignoreCase = true) ||
            url == "about:blank") {
            return false
        }

        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
        val host = uri?.host?.lowercase() ?: ""

        // Strictly keep ONLY https://www.aakasi.com (and aakasi subdomains) inside the app
        val isAakasiHost = host == "www.aakasi.com" || host == "aakasi.com" || host.endsWith(".aakasi.com")
        if (isAakasiHost) {
            return false
        }

        // Payment gateway & 3DS authorization URLs during checkout stay in WebView so checkout succeeds
        val isPaymentGateway = host.contains("razorpay") ||
                host.contains("cashfree") ||
                host.contains("payu") ||
                host.contains("billdesk") ||
                host.contains("juspay") ||
                host.contains("ccavenue") ||
                url.contains("3dsecure") ||
                url.contains("otp") ||
                url.contains("acs")
        if (isPaymentGateway) {
            return false
        }

        // All social media links and all remaining external links open in respective apps
        return openInRespectiveApp(url)
    }

    private fun openInRespectiveApp(url: String): Boolean {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase() ?: ""

        val socialAppPackages = mapOf(
            "instagram.com" to "com.instagram.android",
            "instagr.am" to "com.instagram.android",
            "facebook.com" to "com.facebook.katana",
            "fb.me" to "com.facebook.katana",
            "fb.watch" to "com.facebook.katana",
            "fb.com" to "com.facebook.katana",
            "youtube.com" to "com.google.android.youtube",
            "youtu.be" to "com.google.android.youtube",
            "twitter.com" to "com.twitter.android",
            "x.com" to "com.twitter.android",
            "t.co" to "com.twitter.android",
            "wa.me" to "com.whatsapp",
            "api.whatsapp.com" to "com.whatsapp",
            "chat.whatsapp.com" to "com.whatsapp",
            "whatsapp.com" to "com.whatsapp",
            "linkedin.com" to "com.linkedin.android",
            "lnkd.in" to "com.linkedin.android",
            "pinterest.com" to "com.pinterest",
            "pin.it" to "com.pinterest",
            "t.me" to "org.telegram.messenger",
            "telegram.me" to "org.telegram.messenger",
            "threads.net" to "com.instagram.barcelona",
            "reddit.com" to "com.reddit.frontpage",
            "snapchat.com" to "com.snapchat.android"
        )

        val targetPackage = socialAppPackages.entries.firstOrNull { (domain, _) ->
            host == domain || host.endsWith(".$domain")
        }?.value

        if (targetPackage != null) {
            try {
                val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (appIntent.resolveActivity(packageManager) != null) {
                    startActivity(appIntent)
                    return true
                }
            } catch (_: Exception) {}
        }

        return try {
            val generalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(generalIntent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hideWebScrollbars(view: WebView?) {
        val css = "javascript:(function() { " +
                "var s = document.createElement('style'); " +
                "s.innerHTML = '::-webkit-scrollbar { display: none !important; width: 0 !important; height: 0 !important; } * { -ms-overflow-style: none !important; scrollbar-width: none !important; } html, body { scrollbar-width: none !important; -ms-overflow-style: none !important; }'; " +
                "document.head.appendChild(s); " +
                "})()"
        view?.evaluateJavascript(css, null)
    }

    private fun requestMediaPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            appMediaPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
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
                    showOfflineScreen(webView)
                } else {
                    if (wasOffline || offlineView.visibility == View.VISIBLE) {
                        reloadWebView()
                    }
                }
            }
        }
    }

    private fun reloadWebView() {
        hideOfflineScreen()
        pageLoader.visibility = View.VISIBLE
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
            val host = data.host?.lowercase() ?: ""
            val isAakasi = host == "www.aakasi.com" || host == "aakasi.com" || host.endsWith(".aakasi.com")

            // If not an aakasi.com link, open in respective app
            if (!isAakasi) {
                openInRespectiveApp(data.toString())
                finish()
                return
            }

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

            if (referrerTag == "app" || isPaymentOrOrder || (webView.url != null && webView.url != "about:blank")) {
                val targetUrl = data.toString()
                lastUrl = targetUrl
                if (networkMonitor.isCurrentlyConnected()) {
                    hideOfflineScreen()
                    webView.loadUrl(targetUrl)
                } else {
                    showOfflineScreen(webView)
                }
            } else {
                openInExternalBrowser(data)
                finish()
            }
        } else if (webView.url == null || webView.url == "about:blank") {
            if (networkMonitor.isCurrentlyConnected()) {
                hideOfflineScreen()
                webView.loadUrl(lastUrl)
            } else {
                showOfflineScreen(webView)
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
