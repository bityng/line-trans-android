package com.linetrans.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linetrans.app.R
import com.linetrans.app.data.SettingsRepository

/** 局域网网页翻译台的前台服务：监听端口，把网页界面与 API 提供给局域网设备。 */
class WebServerService : Service() {

    private var server: WebServer? = null

    companion object {
        private const val CHANNEL_ID = "web_server_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        fun start(context: Context) {
            val intent = Intent(context, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebServerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundEx()
        val port = SettingsRepository.settings.webServerPort
        val s = WebServer(applicationContext, port)
        s.startServer()
        server = s
        running = true
        return START_STICKY
    }

    private fun startForegroundEx() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val port = SettingsRepository.settings.webServerPort
        val text = "网页翻译台已在局域网运行，端口 " + port
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("网页翻译台")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "网页翻译台服务", NotificationManager.IMPORTANCE_LOW)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running = false
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
