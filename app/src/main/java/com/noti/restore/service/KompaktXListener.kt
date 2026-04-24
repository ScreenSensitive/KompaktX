package com.noti.restore.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.noti.restore.overlay.OverlayPanelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "KompaktXListener"

data class NotificationPopupData(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val sbn: StatusBarNotification,
    val actions: List<NotificationAction> = emptyList(),
    val replyAction: NotificationAction? = null
)

data class NotificationAction(
    val title: String,
    val action: Notification.Action,
    val isReply: Boolean = false
)

data class ExternalMediaInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val isPlaying: Boolean,
    val packageName: String
)

class KompaktXListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var prefJob: Job? = null

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            try {
                updateExternalMediaInfo()
                OverlayPanelManager.refreshPanelIfShowing()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onPlaybackStateChanged", e)
            }
        }
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            try {
                updateExternalMediaInfo()
                OverlayPanelManager.refreshPanelIfShowing()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onMetadataChanged", e)
            }
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        try { updateActiveMediaController(controllers) } catch (e: Exception) {
            Log.e(TAG, "Error in sessionListener", e)
        }
    }

    override fun onListenerConnected() {
        try {
            super.onListenerConnected()
            Log.d(TAG, "Listener connected")
            instance = this
            updateNotifications()
            mainHandler.postDelayed({ setupMediaSessionManager() }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onListenerConnected", e)
        }
    }

    override fun onListenerDisconnected() {
        try {
            super.onListenerDisconnected()
            Log.d(TAG, "Listener disconnected")
            instance = null
            prefJob?.cancel()
            cleanupMediaSession()
            _notifications.value = emptyList()
            _newNotificationForPopup.value = null
            _notificationCount.value = 0
        } catch (e: Exception) {
            Log.e(TAG, "Error in onListenerDisconnected", e)
        }
    }

    private fun cleanupMediaSession() {
        try {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = null
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            mediaSessionManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up media session", e)
        }
    }

    private fun setupMediaSessionManager() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val componentName = ComponentName(this, KompaktXListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            updateActiveMediaController(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException setting up MediaSessionManager", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaSessionManager", e)
        }
    }

    private fun updateActiveMediaController(controllers: List<MediaController>?) {
        try {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = controllers?.firstOrNull { controller ->
                try { controller.playbackState?.state == PlaybackState.STATE_PLAYING } catch (_: Exception) { false }
            } ?: controllers?.firstOrNull()
            activeMediaController?.registerCallback(mediaControllerCallback)
            updateExternalMediaInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating active media controller", e)
        }
    }

    private var mediaVanishRunnable: Runnable? = null

    private fun updateExternalMediaInfo() {
        val controller = activeMediaController
        if (controller == null) { _externalMediaInfo.value = null; return }
        try {
            val metadata = controller.metadata
            val playbackState = controller.playbackState
            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
            _externalMediaInfo.value = ExternalMediaInfo(
                title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
                isPlaying = isPlaying,
                packageName = controller.packageName ?: ""
            )

            // Auto-vanish: clear media info 2 minutes after playback stops
            mediaVanishRunnable?.let { mainHandler.removeCallbacks(it) }
            if (!isPlaying) {
                val vanish = Runnable {
                    val current = _externalMediaInfo.value
                    if (current != null && !current.isPlaying) {
                        _externalMediaInfo.value = null
                        OverlayPanelManager.refreshPanelIfShowing()
                    }
                }
                mediaVanishRunnable = vanish
                mainHandler.postDelayed(vanish, 30_000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating external media info", e)
            _externalMediaInfo.value = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            super.onNotificationPosted(sbn)
            sbn?.let { notification ->
                if (!notification.isOngoing && notification.packageName !in _hiddenNotiApps.value) {
                    val popupData = createPopupData(notification)
                    _newNotificationForPopup.value = popupData
                    _overlayNotificationEvent.tryEmit(popupData)

                    // Show heads-up overlay popup (suppressed when panel is open)
                    OverlayPanelManager.showHeadsUpNotification(this, popupData)
                    // Live-update panel if it's currently showing
                    OverlayPanelManager.refreshPanelIfShowing()
                }
            }
            updateNotifications()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onNotificationPosted", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            super.onNotificationRemoved(sbn)
            updateNotifications()
            OverlayPanelManager.refreshPanelIfShowing()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onNotificationRemoved", e)
        }
    }

    private fun createPopupData(sbn: StatusBarNotification): NotificationPopupData {
        val notification = sbn.notification
        val extras = notification.extras
        val title = try {
            extras.getString(Notification.EXTRA_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        } catch (_: Exception) { "" }
        val text = try {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getString(Notification.EXTRA_TEXT) ?: ""
        } catch (_: Exception) { "" }
        val actions = try {
            notification.actions?.mapNotNull { action ->
                try {
                    NotificationAction(
                        title = action.title?.toString() ?: "",
                        action = action,
                        isReply = action.remoteInputs?.isNotEmpty() == true
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val replyAction = actions.firstOrNull { it.isReply }
        return NotificationPopupData(
            key = sbn.key, packageName = sbn.packageName, title = title, text = text,
            postTime = sbn.postTime, sbn = sbn, actions = actions, replyAction = replyAction
        )
    }

    private fun updateNotifications() {
        try {
            val activeNotifs = activeNotifications?.toList() ?: emptyList()
            _notifications.value = activeNotifs
            _notificationCount.value = activeNotifs.count { it.packageName !in _hiddenNotiApps.value }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notifications", e)
        }
    }

    fun dismissNotification(key: String) {
        try { cancelNotification(key) } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification", e)
        }
    }

    fun clearAllNotifications() {
        try { cancelAllNotifications() } catch (e: Exception) {
            Log.e(TAG, "Error clearing all notifications", e)
        }
    }

    companion object {
        private var instance: KompaktXListener? = null

        private val _notifications = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        val notifications: StateFlow<List<StatusBarNotification>> = _notifications.asStateFlow()

        private val _notificationCount = MutableStateFlow(0)
        val notificationCount: StateFlow<Int> = _notificationCount.asStateFlow()

        private val _newNotificationForPopup = MutableStateFlow<NotificationPopupData?>(null)
        val newNotificationForPopup: StateFlow<NotificationPopupData?> = _newNotificationForPopup.asStateFlow()

        private val _overlayNotificationEvent = MutableSharedFlow<NotificationPopupData>(extraBufferCapacity = 5)
        val overlayNotificationEvent: SharedFlow<NotificationPopupData> = _overlayNotificationEvent.asSharedFlow()

        private val _externalMediaInfo = MutableStateFlow<ExternalMediaInfo?>(null)
        val externalMediaInfo: StateFlow<ExternalMediaInfo?> = _externalMediaInfo.asStateFlow()

        private val _hiddenNotiApps = MutableStateFlow<Set<String>>(emptySet())

        fun getInstance(): KompaktXListener? = instance
        fun isEnabled(): Boolean = instance != null

        fun dismiss(key: String) { instance?.dismissNotification(key) }
        fun clearAll() { instance?.clearAllNotifications() }
        fun refresh() { instance?.updateNotifications() }
        fun clearPopupNotification() { _newNotificationForPopup.value = null }

        fun sendReply(action: NotificationAction, replyText: String): Boolean {
            return try {
                val remoteInput = action.action.remoteInputs?.firstOrNull() ?: return false
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, replyText)
                RemoteInput.addResultsToIntent(action.action.remoteInputs, intent, bundle)
                action.action.actionIntent?.send(instance, 0, intent)
                true
            } catch (e: Exception) { e.printStackTrace(); false }
        }

        fun executeAction(action: NotificationAction): Boolean {
            return try { action.action.actionIntent?.send(); true } catch (_: Exception) { false }
        }

        fun playPauseExternalMedia() {
            instance?.activeMediaController?.transportControls?.let { controls ->
                if (instance?.activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING)
                    controls.pause() else controls.play()
            }
        }

        fun nextExternalMedia() { instance?.activeMediaController?.transportControls?.skipToNext() }
        fun previousExternalMedia() { instance?.activeMediaController?.transportControls?.skipToPrevious() }

        fun setHiddenNotiApps(apps: Set<String>) {
            _hiddenNotiApps.value = apps
            instance?.updateNotifications()
        }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            val componentName = ComponentName(context, KompaktXListener::class.java)
            return enabledListeners?.contains(componentName.flattenToString()) == true
        }

        fun getNotifications(): List<StatusBarNotification> = _notifications.value

        fun sendPendingIntentWithBackgroundSupport(context: Context, pendingIntent: PendingIntent?): Boolean {
            if (pendingIntent == null) return false
            return try {
                val fillInIntent = Intent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val options = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.app.ActivityOptions.makeBasic().apply {
                        setPendingIntentBackgroundActivityStartMode(
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }.toBundle()
                } else null
                pendingIntent.send(context, 0, fillInIntent, null, null, null, options)
                true
            } catch (e: Exception) { Log.e(TAG, "Failed to send PendingIntent", e); false }
        }

        fun openNotification(context: Context, notificationKey: String?, fallbackIntent: PendingIntent? = null, packageName: String? = null): Boolean {
            if (notificationKey != null) {
                val fresh = getNotifications().find { it.key == notificationKey }
                if (fresh?.notification?.contentIntent != null) {
                    if (sendPendingIntentWithBackgroundSupport(context, fresh.notification.contentIntent)) return true
                }
            }
            if (fallbackIntent != null && sendPendingIntentWithBackgroundSupport(context, fallbackIntent)) return true
            if (packageName != null) {
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return true
                    }
                } catch (e: Exception) { Log.e(TAG, "Failed to launch app: $packageName", e) }
            }
            return false
        }
    }
}
