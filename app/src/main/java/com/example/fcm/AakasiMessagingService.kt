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

        val isOrderNotification = remoteMessage.data["type"] == "woocommerce_order" ||
                remoteMessage.data.containsKey("order_id") ||
                remoteMessage.data["channel"] == "orders" ||
                remoteMessage.from?.contains("orders") == true ||
                remoteMessage.from?.contains("admin_orders") == true

        val defaultTitle = if (isOrderNotification) "🛍️ Order Update" else "Aakasi Update"
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: remoteMessage.data["heading"]
            ?: defaultTitle

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: remoteMessage.data["content"]
            ?: remoteMessage.data["text"]
            ?: if (isOrderNotification) "You have an order update on Aakasi" else "New content available on Aakasi"

        val targetUrl = remoteMessage.data["url"]
            ?: remoteMessage.data["link"]
            ?: remoteMessage.data["target_url"]
            ?: remoteMessage.data["click_action"]
            ?: if (isOrderNotification && remoteMessage.data.containsKey("order_id")) {
                "https://www.aakasi.com/my-account/view-order/${remoteMessage.data["order_id"]}"
            } else {
                "https://www.aakasi.com"
            }

        // Save to Room DB
        saveNotificationToDb(title, body, targetUrl)

        // Display Notification in System Bar
        showNotification(title, body, targetUrl, isOrderNotification)
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

    private fun showNotification(title: String, body: String, targetUrl: String, isOrderNotification: Boolean = false) {
        val channelId = if (isOrderNotification) "aakasi_orders" else "aakasi_notifications"
        val channelName = if (isOrderNotification) "WooCommerce Order Alerts" else "Aakasi News & Updates"
        val channelDescription = if (isOrderNotification) {
            "Real-time notifications for new orders, status changes, and customer purchases"
        } else {
            "Notifications for latest posts, news and updates from Aakasi"
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = channelDescription
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
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
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(if (isOrderNotification) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
