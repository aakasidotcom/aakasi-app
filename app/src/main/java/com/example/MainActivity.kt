package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.util.Log
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
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

    // SoundPool for reliable device touch clicks
    private var soundPool: SoundPool? = null
    private var clickSoundId: Int = 0

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

    // Registers a photo picker activity launcher in single-select mode
    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // Callback is invoked after the user selects media items or closes the photo picker
        if (uri != null) {
            Log.d("PhotoPicker", "Selected media URI: $uri")
            filePathCallback?.onReceiveValue(arrayOf(uri))
        } else {
            Log.d("PhotoPicker", "No media selected")
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    // Registers a photo picker activity launcher in multi-select mode (unlimited / max supported by gallery).
    private val pickMultipleVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        // Callback is invoked after the user selects media items or closes the
        // photo picker.
        if (uris.isNotEmpty()) {
            Log.d("PhotoPicker", "Number of items selected: ${uris.size}")
            filePathCallback?.onReceiveValue(uris.toTypedArray())
        } else {
            Log.d("PhotoPicker", "No media selected")
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    // Register Launcher for System Document/Content Pickers and Camera Capture
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
        cameraPhotoUri = null
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
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        pageLoader = findViewById(R.id.page_loader)
        offlineView = findViewById(R.id.offline_view)
        splashView = findViewById(R.id.splash_view)

        // Enable smooth scrolling and responsive overscroll
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

        networkMonitor = NetworkMonitor(this)

        // Setup animated GIF loader
        setupPageLoader()

        // Display exact uploaded splash screen on cold launch dynamically based on page load
        if (savedInstanceState == null && !splashDismissed) {
            splashView.visibility = View.VISIBLE
            splashView.alpha = 1f
            splashView.bringToFront()
            lifecycleScope.launch {
                delay(8000) // Fallback safety timeout if network is completely stalled
                dismissSplash()
            }
        } else {
            splashView.visibility = View.GONE
        }

        // Ask for Notification Permission on initial launch
        askNotificationPermission()

        // Configure WebView settings with high on-device cache storage and responsive layout
        val initialIsOnline = networkMonitor.isCurrentlyConnected()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // High cache configuration: LOAD_DEFAULT caches aggressively when online;
            // LOAD_CACHE_ELSE_NETWORK loads cached content offline on user device
            cacheMode = if (initialIsOnline) {
                android.webkit.WebSettings.LOAD_DEFAULT
            } else {
                android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            }

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

        // Configure ServiceWorker cache mode
        try {
            android.webkit.ServiceWorkerController.getInstance().serviceWorkerWebSettings.cacheMode =
                if (initialIsOnline) {
                    android.webkit.WebSettings.LOAD_DEFAULT
                } else {
                    android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
        } catch (_: Exception) {}

        // Enable third-party cookies for payment gateway iframes & 3DS authorization
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Initialize SoundPool for instant device touch click playback
        initSoundPool()

        // Disable uncoordinated View system sound effects on WebView so that only
        // important clicks (links, buttons, form controls) trigger touch sounds via bridge
        webView.isSoundEffectsEnabled = false

        // Make offline view clickable so user can tap anywhere on it to retry
        offlineView.isSoundEffectsEnabled = false
        offlineView.setOnClickListener {
            playDefaultTouchSound()
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
                injectAppEnhancements(view)
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
                injectAppEnhancements(view)
                dismissSplash()
                if (currentProgress >= 100) {
                    pageLoader.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAppEnhancements(view)
                currentProgress = 100
                dismissSplash()
                pageLoader.visibility = View.GONE
                try {
                    android.webkit.CookieManager.getInstance().flush()
                    android.net.http.HttpResponseCache.getInstalled()?.flush()
                } catch (_: Exception) {}
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val code = error?.errorCode ?: 0
                    if (code == WebViewClient.ERROR_HOST_LOOKUP ||
                        code == WebViewClient.ERROR_CONNECT ||
                        code == WebViewClient.ERROR_TIMEOUT) {
                        showOfflineScreen(view)
                    }
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
                if (failingUrl == null || failingUrl == view?.url || failingUrl == lastUrl) {
                    if (errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                        errorCode == WebViewClient.ERROR_CONNECT ||
                        errorCode == WebViewClient.ERROR_TIMEOUT) {
                        showOfflineScreen(view)
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                currentProgress = newProgress
                if (newProgress >= 15) {
                    injectAppEnhancements(view)
                }
                if (newProgress >= 75) {
                    dismissSplash()
                }
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

            // File Chooser utilizing Android Photo Picker and system pickers (zero storage permissions)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val rawAcceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() } ?: emptyList()
                val acceptTypes = rawAcceptTypes.flatMap { it.split(",") }
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                val isMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val isCapture = fileChooserParams?.isCaptureEnabled == true

                // Direct camera capture if requested explicitly (<input type="file" capture>)
                if (isCapture) {
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
                        fileChooserLauncher.launch(takePhotoIntent)
                        return true
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Direct camera capture failed, proceeding to gallery", e)
                    }
                }

                // Identify if the request accepts images and/or videos (or general file upload)
                val hasImages = acceptTypes.isEmpty() || acceptTypes.any { type ->
                    type == "*/*" || type == "*" || type.startsWith("image/") ||
                    listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".svg").any { ext ->
                        type.endsWith(ext) || type.contains(ext)
                    }
                }
                val hasVideos = acceptTypes.isEmpty() || acceptTypes.any { type ->
                    type == "*/*" || type == "*" || type.startsWith("video/") ||
                    listOf(".mp4", ".mov", ".mkv", ".avi", ".webm", ".3gp", ".m4v").any { ext ->
                        type.endsWith(ext) || type.contains(ext)
                    }
                }
                val isNonMediaOnly = acceptTypes.isNotEmpty() && !hasImages && !hasVideos

                // 1. Try modern Android Photo Picker first for zero-permission gallery access
                if (!isNonMediaOnly && ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this@MainActivity)) {
                    val mediaType = when {
                        hasImages && !hasVideos -> ActivityResultContracts.PickVisualMedia.ImageOnly
                        !hasImages && hasVideos -> ActivityResultContracts.PickVisualMedia.VideoOnly
                        else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    }

                    try {
                        val request = PickVisualMediaRequest(mediaType)
                        if (isMultiple) {
                            pickMultipleVisualMediaLauncher.launch(request)
                        } else {
                            pickVisualMediaLauncher.launch(request)
                        }
                        return true
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Photo picker launch error, falling back to device gallery", e)
                    }
                }

                // 2. Open device Gallery (Google Photos / System Gallery)
                if (!isNonMediaOnly) {
                    return try {
                        val galleryIntent = Intent(Intent.ACTION_PICK).apply {
                            if (hasVideos && !hasImages) {
                                setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                            } else if (hasImages && !hasVideos) {
                                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                            } else {
                                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                            }
                            if (isMultiple) {
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                        }

                        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = if (hasVideos && !hasImages) "video/*" else "image/*"
                            if (hasImages && hasVideos) {
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                            }
                            if (isMultiple) {
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                        }

                        val chooserTitle = when {
                            hasVideos && !hasImages -> "Select Video from Gallery"
                            hasImages && !hasVideos -> "Select Photo from Gallery"
                            else -> "Select from Gallery"
                        }

                        val primaryIntent = if (galleryIntent.resolveActivity(packageManager) != null) galleryIntent else getContentIntent
                        val chooserIntent = Intent.createChooser(primaryIntent, chooserTitle)

                        val extraIntents = mutableListOf<Intent>()
                        if (primaryIntent != galleryIntent && galleryIntent.resolveActivity(packageManager) != null) {
                            extraIntents.add(galleryIntent)
                        }
                        if (primaryIntent != getContentIntent && getContentIntent.resolveActivity(packageManager) != null) {
                            extraIntents.add(getContentIntent)
                        }

                        // Add camera option so user can also snap a photo directly
                        try {
                            val photoFile = File.createTempFile("IMG_${System.currentTimeMillis()}_", ".jpg", cacheDir)
                            cameraPhotoUri = FileProvider.getUriForFile(
                                this@MainActivity,
                                "${applicationContext.packageName}.fileprovider",
                                photoFile
                            )
                            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }
                            if (cameraIntent.resolveActivity(packageManager) != null) {
                                extraIntents.add(cameraIntent)
                            }
                        } catch (_: Exception) {}

                        if (extraIntents.isNotEmpty()) {
                            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                        }

                        fileChooserLauncher.launch(chooserIntent)
                        true
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to launch gallery intent", e)
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }

                // 3. Fallback for explicit non-media documents (e.g. PDF)
                return try {
                    val systemPickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (acceptTypes.size == 1 && !acceptTypes[0].contains("*")) acceptTypes[0] else "*/*"
                        if (acceptTypes.isNotEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                        }
                        if (isMultiple) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    fileChooserLauncher.launch(systemPickerIntent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.addJavascriptInterface(AndroidShareBridge(this), "AndroidShare")
        val soundBridge = AndroidSoundBridge()
        webView.addJavascriptInterface(soundBridge, "AndroidSound")
        webView.addJavascriptInterface(soundBridge, "AndroidTouchSound")

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
        try {
            android.webkit.CookieManager.getInstance().flush()
            android.net.http.HttpResponseCache.getInstalled()?.flush()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            soundPool?.release()
            soundPool = null
        } catch (_: Exception) {}
        webView.destroy()
    }

    private fun dismissSplash() {
        if (!splashDismissed) {
            splashDismissed = true
            splashView.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    splashView.visibility = View.GONE
                }
                .start()
        }
    }

    private fun showOfflineScreen(view: WebView? = null) {
        wasOffline = true
        dismissSplash()
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

    private fun initSoundPool() {
        try {
            if (soundPool == null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                soundPool = SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(audioAttributes)
                    .build().apply {
                        clickSoundId = load(this@MainActivity, R.raw.touch_click, 1)
                    }
            }
        } catch (_: Exception) {}
    }

    private var lastSoundTimestamp = 0L

    fun playDefaultTouchSound() {
        val now = SystemClock.uptimeMillis()
        if (now - lastSoundTimestamp < 350L) {
            return
        }
        lastSoundTimestamp = now

        runOnUiThread {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val systemSoundEffectsEnabled = try {
                Settings.System.getInt(
                    contentResolver,
                    Settings.System.SOUND_EFFECTS_ENABLED,
                    0
                ) != 0
            } catch (_: Exception) {
                false
            }

            // Play exactly ONE sound source:
            // If device default touch sounds are enabled in system settings, trigger native FX_KEY_CLICK.
            // Otherwise, play the bundled touch click sound from SoundPool so feedback is audible.
            if (systemSoundEffectsEnabled && audioManager != null) {
                try {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
                } catch (_: Exception) {
                    try {
                        if (clickSoundId != 0 && soundPool != null) {
                            soundPool?.play(clickSoundId, 0.7f, 0.7f, 1, 0, 1.0f)
                        }
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    if (clickSoundId != 0 && soundPool != null) {
                        soundPool?.play(clickSoundId, 0.7f, 0.7f, 1, 0, 1.0f)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun injectAppEnhancements(view: WebView?) {
        if (view == null) return
        val js = """
            (function() {
                try {
                    // Inject lightweight CSS once for app styling and smooth scrolling
                    var styleId = 'aakasi-app-custom-style';
                    if (!document.getElementById(styleId)) {
                        var css = '::-webkit-scrollbar { display: none !important; width: 0 !important; height: 0 !important; } ' +
                            '* { -ms-overflow-style: none !important; scrollbar-width: none !important; } ' +
                            'html, body { scrollbar-width: none !important; -ms-overflow-style: none !important; -webkit-overflow-scrolling: touch !important; overflow-y: auto !important; } ' +
                            '.hide-on-app, [class*="hide-on-app"], .elementor-element.hide-on-app, .elementor-widget.hide-on-app, div.hide-on-app, section.hide-on-app { ' +
                            'display: none !important; visibility: hidden !important; opacity: 0 !important; height: 0px !important; ' +
                            'min-height: 0px !important; max-height: 0px !important; width: 0px !important; min-width: 0px !important; ' +
                            'max-width: 0px !important; margin: 0px !important; padding: 0px !important; border: none !important; ' +
                            'overflow: hidden !important; pointer-events: none !important; position: absolute !important; }';
                        var style = document.createElement('style');
                        style.id = styleId;
                        style.type = 'text/css';
                        style.appendChild(docText(css));
                        (document.head || document.documentElement).appendChild(style);
                    }

                    function docText(t) {
                        return document.createTextNode(t);
                    }

                    // Fast touch click feedback without heavy DOM traversal or getComputedStyle
                    if (!window._aakasiTouchSoundAttached) {
                        window._aakasiTouchSoundAttached = true;
                        var lastClickTime = 0;
                        document.addEventListener('click', function(e) {
                            if (!e || !e.target) return;
                            var t = e.target;
                            var isClickable = false;
                            try {
                                if (t.closest && t.closest('a[href], button, input, select, textarea, label, [role="button"], [role="link"], .btn, [class*="btn-"], [class*="button"], .woocommerce-loop-product__link, .add_to_cart_button')) {
                                    isClickable = true;
                                }
                            } catch (_) {}
                            if (!isClickable) return;

                            var now = Date.now();
                            if (now - lastClickTime > 350) {
                                lastClickTime = now;
                                try {
                                    if (window.AndroidSound && typeof window.AndroidSound.playClickSound === 'function') {
                                        window.AndroidSound.playClickSound();
                                    } else if (window.AndroidTouchSound && typeof window.AndroidTouchSound.playClickSound === 'function') {
                                        window.AndroidTouchSound.playClickSound();
                                    }
                                } catch (_) {}
                            }
                        }, true);
                    }
                } catch (e) {}
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)
    }

    private fun hideWebScrollbars(view: WebView?) {
        injectAppEnhancements(view)
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
                // Adjust cache mode dynamically:
                // When online: LOAD_DEFAULT caches responses while validating freshness
                // When offline: LOAD_CACHE_ELSE_NETWORK serves stored content directly from on-device cache
                webView.settings.cacheMode = if (isOnline) {
                    android.webkit.WebSettings.LOAD_DEFAULT
                } else {
                    android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
                try {
                    android.webkit.ServiceWorkerController.getInstance().serviceWorkerWebSettings.cacheMode =
                        webView.settings.cacheMode
                } catch (_: Exception) {}

                if (!isOnline) {
                    if (webView.url.isNullOrEmpty() || webView.url == "about:blank") {
                        showOfflineScreen(webView)
                    }
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

            val targetUrl = data.toString()
            lastUrl = targetUrl
            if (networkMonitor.isCurrentlyConnected()) {
                hideOfflineScreen()
                webView.loadUrl(targetUrl)
            } else {
                showOfflineScreen(webView)
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

    inner class AndroidSoundBridge {
        @JavascriptInterface
        fun playClickSound() {
            playDefaultTouchSound()
        }

        @JavascriptInterface
        fun playTouchSound() {
            playDefaultTouchSound()
        }
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

        @JavascriptInterface
        fun playClickSound() {
            playDefaultTouchSound()
        }

        @JavascriptInterface
        fun playTouchSound() {
            playDefaultTouchSound()
        }
    }
}
