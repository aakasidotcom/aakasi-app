package com.example.ui.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.db.NotificationEntity
import com.example.data.repository.NotificationRepository
import com.example.util.NetworkMonitor
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: NotificationRepository
    private val networkMonitor = NetworkMonitor(application)

    val notifications: StateFlow<List<NotificationEntity>>
    val unreadCount: StateFlow<Int>

    private val _currentUrl = MutableStateFlow("https://www.aakasi.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _navigationEvent = MutableStateFlow<String?>(null)
    val navigationEvent: StateFlow<String?> = _navigationEvent.asStateFlow()

    private val _pageTitle = MutableStateFlow("Aakasi")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isInitialAppLoading = MutableStateFlow(true)
    val isInitialAppLoading: StateFlow<Boolean> = _isInitialAppLoading.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _isOffline = MutableStateFlow(!networkMonitor.isCurrentlyConnected())
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _reloadTrigger = MutableStateFlow(0)
    val reloadTrigger: StateFlow<Int> = _reloadTrigger.asStateFlow()

    private val _fcmToken = MutableStateFlow<String?>(null)
    val fcmToken: StateFlow<String?> = _fcmToken.asStateFlow()

    private val _isSubscribedToAll = MutableStateFlow(true)
    val isSubscribedToAll: StateFlow<Boolean> = _isSubscribedToAll.asStateFlow()

    private val _isSubscribedToOrders = MutableStateFlow(true)
    val isSubscribedToOrders: StateFlow<Boolean> = _isSubscribedToOrders.asStateFlow()

    private val _isSubscribedToAdminOrders = MutableStateFlow(false)
    val isSubscribedToAdminOrders: StateFlow<Boolean> = _isSubscribedToAdminOrders.asStateFlow()

    private val _textZoom = MutableStateFlow(100)
    val textZoom: StateFlow<Int> = _textZoom.asStateFlow()

    init {
        val dao = AppDatabase.getDatabase(application).notificationDao()
        repository = NotificationRepository(dao)

        notifications = repository.allNotifications.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        unreadCount = repository.unreadCount.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

        // Monitor network connection dynamically
        viewModelScope.launch {
            var wasOffline = !networkMonitor.isCurrentlyConnected()
            networkMonitor.isOnline.collect { online ->
                if (!online) {
                    _isOffline.value = true
                    _isInitialAppLoading.value = false
                    wasOffline = true
                } else {
                    val hadBeenOffline = wasOffline || _isOffline.value || _errorState.value != null
                    _isOffline.value = false
                    _errorState.value = null
                    if (hadBeenOffline) {
                        wasOffline = false
                        // Automatically reload webview when internet connects
                        _reloadTrigger.value += 1
                        fetchFcmToken()
                    }
                }
            }
        }

        // Initialize FCM asynchronously with safe delay
        viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(1000)
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
            } catch (_: Throwable) {}

            fetchFcmToken()
            subscribeToDefaultTopics()
        }
    }

    fun loadUrl(url: String) {
        _errorState.value = null
        _currentUrl.value = url
        _navigationEvent.value = url
    }

    fun onWebPageNavigated(url: String) {
        _currentUrl.value = url
    }

    fun onPageFinished(url: String) {
        _currentUrl.value = url
        _isInitialAppLoading.value = false
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    fun updateProgress(progress: Int) {
        _loadProgress.value = progress
        _isLoading.value = progress < 100
        if (progress >= 60) {
            _isInitialAppLoading.value = false
        }
    }

    fun updateTitle(title: String) {
        if (title.isNotBlank() && !title.contains("http", ignoreCase = true)) {
            _pageTitle.value = title
        }
    }

    fun setError(errorMessage: String) {
        _errorState.value = errorMessage
        _isLoading.value = false
        _isInitialAppLoading.value = false
    }

    fun clearError() {
        _errorState.value = null
    }

    fun fetchFcmToken() {
        viewModelScope.launch {
            val token = repository.getFcmToken()
            if (token != null) {
                _fcmToken.value = token
            } else if (_fcmToken.value == null) {
                _fcmToken.value = "Token registered"
            }
        }
    }

    private fun subscribeToDefaultTopics() {
        try {
            repository.subscribeToTopic("all_users") { success ->
                _isSubscribedToAll.value = success
            }
            repository.subscribeToTopic("orders") { success ->
                _isSubscribedToOrders.value = success
            }
        } catch (_: Throwable) {
            _isSubscribedToAll.value = false
        }
    }

    fun toggleTopicSubscription(subscribe: Boolean) {
        if (subscribe) {
            repository.subscribeToTopic("all_users") { success ->
                _isSubscribedToAll.value = success
            }
        } else {
            repository.unsubscribeFromTopic("all_users") { success ->
                _isSubscribedToAll.value = !success
            }
        }
    }

    fun toggleOrdersSubscription(subscribe: Boolean) {
        if (subscribe) {
            repository.subscribeToTopic("orders") { success ->
                _isSubscribedToOrders.value = success
            }
        } else {
            repository.unsubscribeFromTopic("orders") { success ->
                _isSubscribedToOrders.value = !success
            }
        }
    }

    fun toggleAdminOrdersSubscription(subscribe: Boolean) {
        if (subscribe) {
            repository.subscribeToTopic("admin_orders") { success ->
                _isSubscribedToAdminOrders.value = success
            }
        } else {
            repository.unsubscribeFromTopic("admin_orders") { success ->
                _isSubscribedToAdminOrders.value = !success
            }
        }
    }

    fun markNotificationAsRead(id: Long) {
        viewModelScope.launch {
            repository.markAsRead(id)
        }
    }

    fun markAllNotificationsAsRead() {
        viewModelScope.launch {
            repository.markAllAsRead()
        }
    }

    fun deleteNotification(id: Long) {
        viewModelScope.launch {
            repository.deleteNotification(id)
        }
    }

    fun clearAllNotifications() {
        viewModelScope.launch {
            repository.clearAllNotifications()
        }
    }

    fun setTextZoom(zoom: Int) {
        _textZoom.value = zoom
    }

    fun sendTestNotification(
        title: String = "Test",
        body: String = "Test notification received successfully",
        url: String = "https://www.aakasi.com"
    ) {
        val context = getApplication<Application>().applicationContext
        val channelId = "aakasi_notifications"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_TARGET_URL", url)
            putExtra("url", url)
            putExtra("link", url)
            putExtra("target_url", url)
            data = android.net.Uri.parse(url)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)

        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())

        viewModelScope.launch {
            repository.insertNotification(
                title = title,
                body = body,
                url = url
            )
        }
    }
}
