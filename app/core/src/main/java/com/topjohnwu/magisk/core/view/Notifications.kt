package com.topjohnwu.magisk.view

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.app.NotificationManagerCompat
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.selfLaunchIntent
import com.topjohnwu.magisk.core.ktx.toast
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
object Notifications {

    val mgr by lazy { AppContext.getSystemService<NotificationManager>()!! }

    private const val PROGRESS_CHANNEL = "progress"
    private const val SU_CHANNEL = "su_notification"

    private val nextId = AtomicInteger(5)

    fun setup() {
        AppContext.apply {
            if (SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(PROGRESS_CHANNEL,
                    getString(R.string.progress_channel), NotificationManager.IMPORTANCE_LOW)
                mgr.createNotificationChannels(listOf(channel))
            }
        }
    }

    fun suNotificationOrToast(context: android.content.Context, granted: Boolean, appName: String) {
        // Disabled
    }

    fun startProgress(title: CharSequence): Notification.Builder {
        val builder = if (SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(AppContext, PROGRESS_CHANNEL)
        } else {
            Notification.Builder(AppContext).setPriority(Notification.PRIORITY_LOW)
        }
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setProgress(0, 0, true)
            .setOngoing(true)
        if (SDK_INT >= Build.VERSION_CODES.S)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder
    }

    @SuppressLint("InlinedApi")
    fun suNotification(granted: Boolean, appName: String) {
        // Disabled
    }

    fun nextId() = nextId.incrementAndGet()
}
