package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class AakasiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase safely
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            Log.d("AakasiApp", "FirebaseApp initialized successfully")

            // Retrieve and log FCM registration token
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w("AakasiApp", "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                Log.d("AakasiApp", "FCM Registration Token: $token")
            }

            // Subscribe to generic updates topic
            FirebaseMessaging.getInstance().subscribeToTopic("all")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d("AakasiApp", "Subscribed to FCM topic: all")
                    }
                }
        } catch (e: Exception) {
            Log.w("AakasiApp", "FirebaseApp init: ${e.message}")
        }

        // Create Notification Channels for Android 8.0+
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return

            // Remove legacy channels so only Miscellaneous is visible in system settings
            try {
                notificationManager.deleteNotificationChannel("aakasi_orders")
            } catch (_: Exception) {}

            // Unified Channel: Miscellaneous
            val miscellaneousChannel = NotificationChannel(
                "aakasi_notifications",
                "Miscellaneous",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Miscellaneous"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(miscellaneousChannel)
        }
    }
}
