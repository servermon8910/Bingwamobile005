package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

private const val ANDROID_COMPAT_TAG = "AndroidCompat"

fun registerAppReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter): Boolean {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Context.RECEIVER_NOT_EXPORTED
    } else {
        0
    }
    return runCatching {
        ContextCompat.registerReceiver(context, receiver, filter, flags)
        true
    }.onFailure { error ->
        Log.e(
            ANDROID_COMPAT_TAG,
            "Receiver registration failed for ${filter.countActions()} action(s)",
            error
        )
    }.getOrDefault(false)
}
