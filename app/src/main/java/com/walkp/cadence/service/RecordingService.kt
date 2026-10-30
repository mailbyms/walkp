package com.walkp.cadence.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.walkp.cadence.CadenceServiceManager
import com.walkp.cadence.MainActivity
import com.walkp.cadence.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 记录期间的前台服务：唯一职责是"保活 + 通知"。
 * 测量逻辑在 CadenceServiceManager 持有的 engine 里，这里不碰算法。
 *
 * 服务类型 health（Android 14+ 要求声明），加速度计采样属于健康/健身类用途。
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var updater: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "步频记录",
                    NotificationManager.IMPORTANCE_LOW   // 不响铃不震动，只在状态栏留图标
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())

        // 服务启动时确保测量在跑（极端情况下服务先于 UI 被系统拉起）
        CadenceServiceManager.ensureStarted(this)

        // 每 5 秒刷新一次通知上的读数；IMPORTANCE_LOW + onlyAlertOnce，不打扰用户
        updater = scope.launch {
            while (isActive) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification())
                delay(5_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        updater?.cancel()
        CadenceServiceManager.onServiceDestroyed()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= 34 ->
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            Build.VERSION.SDK_INT >= 29 ->
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val snap = CadenceServiceManager.snapshot.value
        val text = snap.instantSpm?.let { "当前 ${it.roundToInt()} 步/分" } ?: "等待步行信号…"

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle("步频记录中")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
