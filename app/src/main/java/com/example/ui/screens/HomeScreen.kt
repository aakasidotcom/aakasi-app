package com.example.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.R
import com.example.ui.components.AakasiWebView
import com.example.ui.components.WebViewController
import com.example.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenNotifications: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUrl by viewModel.currentUrl.collectAsState()
    val navigationEvent by viewModel.navigationEvent.collectAsState()
    val errorState by viewModel.errorState.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val reloadTrigger by viewModel.reloadTrigger.collectAsState()
    val textZoom by viewModel.textZoom.collectAsState()

    val isLoading by viewModel.isLoading.collectAsState()
    val isInitialAppLoading by viewModel.isInitialAppLoading.collectAsState()

    val webViewController = remember { WebViewController() }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    // Handle deep link / push notification URL navigation events
    LaunchedEffect(navigationEvent) {
        navigationEvent?.let { targetUrl ->
            if (targetUrl.isNotBlank()) {
                webViewController.loadUrl(targetUrl)
                viewModel.clearNavigationEvent()
            }
        }
    }

    // Automatically reload webview when internet connection connects
    LaunchedEffect(reloadTrigger) {
        if (reloadTrigger > 0) {
            val wv = webViewController.webView
            val wvUrl = wv?.url
            if (wvUrl.isNullOrEmpty() || wvUrl == "about:blank" || wvUrl.startsWith("data:")) {
                webViewController.loadUrl(currentUrl)
            } else {
                webViewController.reload()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.White)
    ) {
        AakasiWebView(
            url = currentUrl,
            controller = webViewController,
            onProgressChanged = { progress -> viewModel.updateProgress(progress) },
            onTitleReceived = { title -> viewModel.updateTitle(title) },
            onPageStarted = { url -> viewModel.onPageStarted(url) },
            onPageCommitVisible = { url -> viewModel.onPageCommitVisible(url) },
            onPageFinished = { url -> viewModel.onPageFinished(url) },
            onErrorReceived = { errorMsg -> viewModel.setError(errorMsg) },
            textZoomPercent = textZoom
        )

        // Small GIF loader centered on screen during page loading (stops when page reaches 1% loaded)
        AnimatedVisibility(
            visible = isLoading && !isInitialAppLoading && !isOffline && errorState == null,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(R.drawable.aakasi_app_loader)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "Loading...",
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Offline / No Internet Connection Page with exact no_internet_connection image and text styles
        if (isOffline || errorState != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.TopStart
            ) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 140.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Line 1: attached icon
                    Image(
                        painter = painterResource(id = R.drawable.no_internet_connection),
                        contentDescription = "No Internet Icon",
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Line 2: NO INTERNET CONNECTION
                    Text(
                        text = "NO INTERNET CONNECTION",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = Color.Black
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Line 3: You are currently offline
                    Text(
                        text = "You are currently offline",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Light,
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                }
            }
        }
    }
}




