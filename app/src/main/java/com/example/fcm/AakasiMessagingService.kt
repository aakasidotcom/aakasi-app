package com.example.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.db.NotificationEntity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AakasiMessagingService : FirebaseMessagingService() {

    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("AakasiFCM", "New FCM Token received: $token")
        // Token updated
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("AakasiFCM", "From: ${remoteMessage.from}")

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: remoteMessage.data["heading"]
            ?: "Aakasi Update"

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: remoteMessage.data["content"]
            ?: remoteMessage.data["text"]
            ?: "New content available on Aakasi"

        val targetUrl = remoteMessage.data["url"]
            ?: remoteMessage.data["link"]
            ?: remoteMessage.data["target_url"]
            ?: remoteMessage.data["click_action"]
            ?: "https://www.aakasi.com"

        // Save to Room DB
        saveNotificationToDb(title, body, targetUrl)

        // Display Notification in System Bar
        showNotification(title, body, targetUrl)
    }

    private fun saveNotificationToDb(title: String, body: String, url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.notificationDao().insertNotification(
                    NotificationEntity(
                        title = title,
                        body = body,
                        url = url
                    )
                )
            } catch (e: Exception) {
                Log.e("AakasiFCM", "Error saving notification to DB", e)
            }
        }
    }

    private fun showNotification(title: String, body: String, targetUrl: String) {
        val channelId = "aakasi_notifications"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Aakasi News & Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for latest posts, news and updates from Aakasi"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_TARGET_URL", targetUrl)
            putExtra("url", targetUrl)
            putExtra("link", targetUrl)
            putExtra("target_url", targetUrl)
            data = android.net.Uri.parse(targetUrl)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
