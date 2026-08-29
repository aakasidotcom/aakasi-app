package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
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
        }

        webView.addJavascriptInterface(AndroidShareBridge(this), "AndroidShare")

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * No more "forward to browser" branch here. If this Activity received
     * the intent at all, it's because Android already decided (via App
     * Links) that the app should handle it. Just load it. Never hand it
     * back out — that back-and-forth is what caused the multi-open loop.
     */
    private fun handleIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data
        val urlToLoad = data?.toString() ?: "https://aakasi.com/"
        webView.loadUrl(urlToLoad)
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
    }
}
