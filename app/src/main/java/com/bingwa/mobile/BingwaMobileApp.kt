package com.bingwa.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

class BingwaMobileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(Tracker)
    }

    companion object Tracker : Application.ActivityLifecycleCallbacks {
        private val startedCount = AtomicInteger(0)
        @Volatile private var lastForegroundElapsed: Long = 0L

        fun isInForeground(): Boolean = startedCount.get() > 0

        fun wasInForegroundRecently(withinMs: Long = 2_500L): Boolean {
            val last = lastForegroundElapsed
            if (last <= 0L) return false
            return SystemClock.elapsedRealtime() - last <= withinMs
        }

        override fun onActivityStarted(activity: Activity) {
            startedCount.incrementAndGet()
            lastForegroundElapsed = SystemClock.elapsedRealtime()
        }

        override fun onActivityStopped(activity: Activity) {
            val next = (startedCount.decrementAndGet()).coerceAtLeast(0)
            startedCount.set(next)
            if (next > 0) lastForegroundElapsed = SystemClock.elapsedRealtime()
        }

        override fun onActivityResumed(activity: Activity) {
            lastForegroundElapsed = SystemClock.elapsedRealtime()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

