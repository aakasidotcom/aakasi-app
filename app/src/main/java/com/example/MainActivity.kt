package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NotificationInboxScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.WHITE,
                android.graphics.Color.WHITE
            )
        )

        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RequestNotificationPermission()

                    val isInitialAppLoading by viewModel.isInitialAppLoading.collectAsState()

                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Top space for Android status bar filled with solid white color
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsTopHeight(WindowInsets.statusBars)
                                    .background(Color.White)
                            )

                            val navController = rememberNavController()

                            NavHost(
                                navController = navController,
                                startDestination = "home",
                                modifier = Modifier.weight(1f),
                                enterTransition = {
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                                        animationSpec = tween(300)
                                    )
                                },
                                exitTransition = {
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                                        animationSpec = tween(300)
                                    )
                                },
                                popEnterTransition = {
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(300)
                                    )
                                },
                                popExitTransition = {
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(300)
                                    )
                                }
                            ) {
                                composable("home") {
                                    HomeScreen(
                                        viewModel = viewModel,
                                        onOpenNotifications = { navController.navigate("notifications") },
                                        onOpenSettings = { navController.navigate("settings") }
                                    )
                                }

                                composable("notifications") {
                                    NotificationInboxScreen(
                                        viewModel = viewModel,
                                        onBackClick = { navController.popBackStack() },
                                        onNotificationClick = { targetUrl ->
                                            viewModel.loadUrl(targetUrl)
                                            navController.navigate("home") {
                                                popUpTo("home") { inclusive = true }
                                            }
                                        }
                                    )
                                }

                                composable("settings") {
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onBackClick = { navController.popBackStack() }
                                    )
                                }
                            }
                        }

                        // Full-screen splash overlay rendered once while WebView loads in the background
                        AnimatedVisibility(
                            visible = isInitialAppLoading,
                            enter = EnterTransition.None,
                            exit = fadeOut(animationSpec = tween(400))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.aakasi_app_splash_screen),
                                    contentDescription = "Splash Screen",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Core routing rule:
     *  - URI contains "ref=app"  -> this link was generated by OUR app's
     *    share/copy feature -> load it straight into the in-app WebView.
     *  - URI does NOT contain it -> this link was generated by the WEBSITE's
     *    share/copy feature (or is a plain link from anywhere else) ->
     *    forward it to the user's default browser and close this activity,
     *    so it never gets "stuck" opening inside the app.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val data: Uri? = intent.data

        if (data != null) {
            val referrerTag = data.getQueryParameter("ref")

            if (referrerTag == "app") {
                // Came from our own app's share feature -> open in-app
                viewModel.loadUrl(data.toString())
                return
            } else {
                // Came from the website's share feature (or any other plain
                // link to aakasi.com) -> hand off to the real browser.
                openInExternalBrowser(data)
                finish()
                return
            }
        }

        // Notification extras or other custom URLs from push notifications
        val extraUrl = intent.getStringExtra("EXTRA_TARGET_URL")
            ?: intent.getStringExtra("url")
            ?: intent.getStringExtra("link")
            ?: intent.getStringExtra("target_url")
            ?: intent.getStringExtra("click_action")
            ?: intent.getStringExtra("gcm.notification.url")
        if (!extraUrl.isNullOrBlank() && (extraUrl.startsWith("http://") || extraUrl.startsWith("https://"))) {
            viewModel.loadUrl(extraUrl)
        }
    }

    private fun openInExternalBrowser(uri: Uri) {
        val browserIntent = Intent(Intent.ACTION_VIEW, uri)
        // FLAG_ACTIVITY_NEW_TASK needed since we may call this before our
        // own activity is fully resumed (e.g. straight from onCreate).
        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(browserIntent)
        } catch (e: Exception) {
            // No browser available -> last resort, load in WebView
            viewModel.loadUrl(uri.toString())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "aakasi_notifications"
            val channelName = "Aakasi News & Updates"
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for latest posts, news and updates from Aakasi"
                enableVibration(true)
            }
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                // Permission result handled
            }
        )

        val context = androidx.compose.ui.platform.LocalContext.current

        LaunchedEffect(Unit) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}


