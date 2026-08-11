package com.bingwa.mobile

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log

internal fun Service.startForegroundCompat(
    notificationId: Int,
    notification: Notification,
    foregroundServiceType: Int
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(notificationId, notification, foregroundServiceType)
    } else {
        @Suppress("DEPRECATION")
        startForeground(notificationId, notification)
    }
}

internal fun Service.tryStartForegroundCompat(
    notificationId: Int,
    notification: Notification,
    foregroundServiceType: Int,
    serviceLabel: String
): Boolean {
    return runCatching {
        startForegroundCompat(notificationId, notification, foregroundServiceType)
        true
    }.getOrElse { error ->
        Log.e("ForegroundServiceCompat", "Unable to start $serviceLabel in the foreground", error)
        runCatching {
            OfferNotifications.notify(
                applicationContext,
                "Open Bingwa Mobile",
                "$serviceLabel could not stay active in the background. Open Bingwa Mobile and remove battery restrictions on this phone."
            )
        }
        false
    }
}

internal object ForegroundServiceTypes {
    val dataSync: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    val phoneCall: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
    val combinedPhoneCall: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
}
