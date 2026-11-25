package com.disastersafety.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Native Android Foreground Service for the Disaster Safety BLE Mesh Network.
 *
 * Maintains mesh operation, GATT client/server links, multi-hop packet forwarding,
 * and emergency SOS propagation when the app is backgrounded or the screen is locked.
 *
 * Uses android:foregroundServiceType="connectedDevice" in compliance with Android 14+ (API 34/35/36).
 */
class BleMeshForegroundService : Service() {

    companion object {
        private const val TAG = "BleMeshService"
        const val CHANNEL_ID = "disaster_safety_ble_mesh_channel"
        const val CHANNEL_NAME = "Offline Mesh Network"
        const val NOTIFICATION_ID = 9110

        const val ACTION_START = "com.disastersafety.app.action.START_MESH"
        const val ACTION_STOP = "com.disastersafety.app.action.STOP_MESH"
        const val ACTION_UPDATE_STATUS = "com.disastersafety.app.action.UPDATE_STATUS"

        const val EXTRA_CONNECTED_COUNT = "extra_connected_count"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        // Native Source of Truth for background service state
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentStatus: String = "Stopped"
            private set

        @Volatile
        var currentConnectedCount: Int = 0
            private set

        // Callback to notify BleChatPlugin when user clicks "Stop Mesh" directly in the Android notification
        var onStopRequested: (() -> Unit)? = null

        fun startService(context: Context) {
            val intent = Intent(context, BleMeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BleMeshForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateStatus(context: Context, connectedCount: Int, statusText: String) {
            if (!isRunning) return
            val intent = Intent(context, BleMeshForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_CONNECTED_COUNT, connectedCount)
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send status update to service: ${e.message}")
            }
        }
    }

    private var notificationManager: NotificationManager? = null
    private val queueHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val queueRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                try {
                    BleChatPlugin.activeInstance?.processPendingQueue()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in background queue processing: ${e.message}")
                }
                queueHandler.postDelayed(this, 15000L) // Check every 15s in background
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()
        Log.i(TAG, "BleMeshForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received: stopping foreground service")
                handleStopCommand()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                val count = intent?.getIntExtra(EXTRA_CONNECTED_COUNT, currentConnectedCount) ?: currentConnectedCount
                val status = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: currentStatus
                currentConnectedCount = count
                currentStatus = status
                refreshNotification()
                return START_STICKY
            }
            ACTION_START -> {
                handleStartCommand()
                return START_STICKY
            }
            else -> {
                handleStartCommand()
                return START_STICKY
            }
        }
    }

    private fun handleStartCommand() {
        isRunning = true
        currentStatus = if (currentConnectedCount > 0) "Mesh Active" else "Searching for nearby devices..."

        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "Foreground service started with connectedDevice type")
            queueHandler.removeCallbacks(queueRunnable)
            queueHandler.postDelayed(queueRunnable, 2000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
            isRunning = false
            currentStatus = "Error starting service"
        }
    }

    private fun handleStopCommand() {
        isRunning = false
        currentStatus = "Stopped"
        currentConnectedCount = 0
        queueHandler.removeCallbacks(queueRunnable)

        // Trigger notification callback to release BLE scanning & advertising in BleChatPlugin
        try {
            onStopRequested?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Error in onStopRequested callback: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.i(TAG, "BleMeshForegroundService stopped and notification removed")
    }

    private fun refreshNotification() {
        if (!isRunning) return
        val notification = buildNotification()
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        // 1. PendingIntent to reopen MainActivity when notification body is clicked
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)

        // 2. PendingIntent for [Stop Mesh] notification action
        val stopIntent = Intent(this, BleMeshForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop Mesh",
            stopPendingIntent
        ).build()

        // 3. Status text determination
        val contentText = when {
            currentConnectedCount > 0 -> "Nearby devices: $currentConnectedCount | Mesh status: Active"
            currentStatus.contains("Bluetooth disabled", ignoreCase = true) -> "Bluetooth is disabled. Please enable Bluetooth."
            else -> "Searching for nearby devices..."
        }

        val iconRes = try {
            val resId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
            if (resId != 0) resId else android.R.drawable.stat_sys_data_bluetooth
        } catch (_: Exception) {
            android.R.drawable.stat_sys_data_bluetooth
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline Mesh Active")
            .setContentText(contentText)
            .setSubText("Disaster Safety Network")
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(stopAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains offline BLE mesh communication in the background during emergencies"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        currentStatus = "Stopped"
        queueHandler.removeCallbacks(queueRunnable)
        Log.i(TAG, "BleMeshForegroundService destroyed")
        super.onDestroy()
    }
}
