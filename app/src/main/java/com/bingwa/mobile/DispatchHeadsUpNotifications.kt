package com.bingwa.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object DispatchHeadsUpNotifications {
    private const val CHANNEL_ID = "dispatch_heads_up"
    private const val CHANNEL_NAME = "Dispatch Alerts"
    private const val DONE_TIMEOUT_MS = 3_500L

    fun newAlertId(): Int = ((System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()).coerceAtLeast(1)

    fun showDispatching(context: Context, txId: Int, offerName: String, phone: String) {
        show(
            context = context,
            notificationId = txId,
            line = "Dispatching ${compactOffer(offerName)} -> ${compactPhone(phone)}",
            ongoing = true,
            timeoutMs = null
        )
    }

    fun showForwarded(context: Context, txId: Int, offerName: String, phone: String) {
        show(
            context = context,
            notificationId = txId,
            line = "Relay ${compactOffer(offerName)} -> ${compactPhone(phone)}",
            ongoing = false,
            timeoutMs = DONE_TIMEOUT_MS
        )
    }

    fun showSuccess(context: Context, txId: Int, offerName: String, phone: String) {
        show(
            context = context,
            notificationId = txId,
            line = "Success ${compactOffer(offerName)} -> ${compactPhone(phone)}",
            ongoing = false,
            timeoutMs = DONE_TIMEOUT_MS
        )
    }

    fun showFailed(context: Context, txId: Int, offerName: String, phone: String) {
        show(
            context = context,
            notificationId = txId,
            line = "Failed ${compactOffer(offerName)} -> ${compactPhone(phone)}",
            ongoing = false,
            timeoutMs = DONE_TIMEOUT_MS
        )
    }

    fun showPending(context: Context, txId: Int, offerName: String, phone: String) {
        show(
            context = context,
            notificationId = txId,
            line = "Pending ${compactOffer(offerName)} -> ${compactPhone(phone)}",
            ongoing = false,
            timeoutMs = DONE_TIMEOUT_MS
        )
    }

    fun showCancelled(context: Context, txId: Int, offerName: String, phone: String) {
        show(
            context = context,
            notificationId = txId,
            line = "Stopped ${compactOffer(offerName)} -> ${compactPhone(phone)}",
            ongoing = false,
            timeoutMs = DONE_TIMEOUT_MS
        )
    }

    private fun show(
        context: Context,
        notificationId: Int,
        line: String,
        ongoing: Boolean,
        timeoutMs: Long?
    ) {
        if (!canPostNotifications(context) || notificationId < 0) return
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Heads-up alerts for offer dispatch progress"
                    enableVibration(true)
                }
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(line)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
        if (timeoutMs != null && timeoutMs > 0L) {
            builder.setTimeoutAfter(timeoutMs)
        }
        NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
    }

    private fun compactOffer(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().ifBlank { "Offer" }.take(28)

    private fun compactPhone(phone: String): String =
        phone.replace(Regex("\\s+"), "").trim().ifBlank { "Unknown" }.takeLast(10)

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
