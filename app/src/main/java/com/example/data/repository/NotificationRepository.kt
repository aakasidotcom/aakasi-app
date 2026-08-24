package com.example.data.repository

import android.util.Log
import com.example.data.db.NotificationDao
import com.example.data.db.NotificationEntity
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

class NotificationRepository(private val notificationDao: NotificationDao) {

    val allNotifications: Flow<List<NotificationEntity>> = notificationDao.getAllNotifications()
    val unreadCount: Flow<Int> = notificationDao.getUnreadCount()

    suspend fun insertNotification(title: String, body: String, url: String?): Long {
        return notificationDao.insertNotification(
            NotificationEntity(
                title = title,
                body = body,
                url = url
            )
        )
    }

    suspend fun markAsRead(id: Long) {
        notificationDao.markAsRead(id)
    }

    suspend fun markAllAsRead() {
        notificationDao.markAllAsRead()
    }

    suspend fun deleteNotification(id: Long) {
        notificationDao.deleteNotification(id)
    }

    suspend fun clearAllNotifications() {
        notificationDao.clearAll()
    }

    suspend fun getFcmToken(): String? {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d("AakasiFCM", "Fetched FCM token successfully")
            token
        } catch (e: Throwable) {
            Log.w("AakasiFCM", "FCM token not available yet: ${e.message}")
            null
        }
    }

    fun subscribeToTopic(topic: String, onResult: (Boolean) -> Unit) {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(topic)
                .addOnCompleteListener { task ->
                    onResult(task.isSuccessful)
                }
                .addOnFailureListener {
                    onResult(false)
                }
        } catch (e: Throwable) {
            onResult(false)
        }
    }

    fun unsubscribeFromTopic(topic: String, onResult: (Boolean) -> Unit) {
        try {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
                .addOnCompleteListener { task ->
                    onResult(task.isSuccessful)
                }
                .addOnFailureListener {
                    onResult(false)
                }
        } catch (e: Throwable) {
            onResult(false)
        }
    }
}
