package com.example.droidnode.service

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.droidnode.MainActivity
import com.example.droidnode.runtime.PRootRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service ensuring Node.js process stays alive when app goes to background
 * or device enters Doze mode.
 */
class NodeServerService : Service() {

    companion object {
        private const val TAG = "NodeServerService"
        private const val CHANNEL_ID = "droidnode_server_channel"
        private const val NOTIFICATION_ID = 4040

        const val ACTION_START = "ACTION_START_SERVER"
        const val ACTION_STOP = "ACTION_STOP_SERVER"

        // Public observable state
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _serverLogs = MutableStateFlow<List<String>>(emptyList())
        val serverLogs = _serverLogs.asStateFlow()

        fun log(message: String) {
            _serverLogs.value = (_serverLogs.value + message).takeLast(500)
        }

        fun clearLogs() {
            _serverLogs.value = emptyList()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prootRunner: PRootRunner
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        prootRunner = PRootRunner(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNodeServer()
            ACTION_STOP -> stopNodeServer()
            else -> {
                if (!_isRunning.value) {
                    startNodeServer()
                }
            }
        }
        return START_STICKY
    }

    private fun startNodeServer() {
        if (_isRunning.value) return

        // 1. Promote to Foreground Service immediately to prevent ANR or OS killing
        val notification = buildNotification("Node.js server starting on port 8080...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isRunning.value = true
        log("[Service] Starting Node.js runtime inside PRoot...")

        // 2. Launch in IO Scope
        serviceScope.launch {
            try {
                prootRunner.startServerProcess(
                    onStdout = { line ->
                        Log.d(TAG, "[STDOUT] $line")
                        log(line)
                        updateNotification("Running: http://127.0.0.1:8080")
                    },
                    onStderr = { line ->
                        Log.e(TAG, "[STDERR] $line")
                        log("[ERROR] $line")
                    },
                    onExit = { code ->
                        log("[Service] Node.js exited with code $code")
                        _isRunning.value = false
                        stopSelf()
                    }
                )
            } catch (e: Exception) {
                log("[Service] Execution failed: ${e.message}")
                _isRunning.value = false
                stopSelf()
            }
        }
    }

    private fun stopNodeServer() {
        log("[Service] Stopping Node.js server...")
        prootRunner.stopServerProcess()
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DroidNode::ServerWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // Safe 12 hour maximum
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Node.js Runtime Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the embedded Node.js process active in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidNode Server Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNodeServer()
        releaseWakeLock()
        serviceScope.cancel()
        _isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
