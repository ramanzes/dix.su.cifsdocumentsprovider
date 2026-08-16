package com.wa2c.android.cifsdocumentsprovider.presentation.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.wa2c.android.cifsdocumentsprovider.common.values.NOTIFICATION_CHANNEL_ID_DIXSU_PROXY
import com.wa2c.android.cifsdocumentsprovider.common.values.NOTIFICATION_ID_DIXSU_PROXY
import com.wa2c.android.cifsdocumentsprovider.presentation.R
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.MainActivity

/**
 * Dixsu Proxy Notification (keeps the standalone local SFTP proxy alive in the background)
 */
class DixsuProxyNotification(
    private val context: Context,
) {
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID_DIXSU_PROXY) != null) return
        NotificationChannel(
            NOTIFICATION_CHANNEL_ID_DIXSU_PROXY,
            context.getString(R.string.notification_channel_name_dixsu_proxy),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }.let {
            notificationManager.createNotificationChannel(it)
        }
    }

    private val startActivityIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP },
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createNotification(port: Int): Notification {
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID_DIXSU_PROXY)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(startActivityIntent)
            .setContentTitle(context.getString(R.string.notification_title_dixsu_proxy))
            .setContentText(context.getString(R.string.notification_text_dixsu_proxy, port))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Create CoroutineWorker foreground info
     */
    fun getNotificationInfo(port: Int): ForegroundInfo {
        val notification = createNotification(port)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID_DIXSU_PROXY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID_DIXSU_PROXY, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_REQUEST_CODE = 2
    }

}
