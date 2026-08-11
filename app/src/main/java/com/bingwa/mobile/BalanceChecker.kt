package com.bingwa.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.NumberFormat
import java.util.*

class BalanceChecker : Service() {
    private var foregroundReady = false

    // ─── Public Data Classes ──────────────────────────────────────────────
    data class BalanceResult(
        val display: String,               // e.g. "KSh 100.50"
        val rawAmount: Double,             // 100.50
        val currency: String,              // "KSh", "KES", etc.
        val rawText: String,               // full USSD response
        val timestamp: Long,               // when parsed
        val sourceSimSlot: Int?,           // which SIM slot (0,1) or null if unknown
        val success: Boolean,
        val errorMessage: String? = null,
        val selectionOverride: Int? = null,
        val persistResult: Boolean = true
    )

    companion object {
        private const val TAG = "BalanceChecker"
        private const val DEFAULT_BALANCE_USSD = "*144#"
        private const val AIRTEL_BALANCE_USSD = "*131#"
        private val ROTATING_CHECK_INTERVALS_MS = longArrayOf(
            10 * 60_000L,
            11 * 60_000L,
            13 * 60_000L,
            12 * 60_000L,
            14 * 60_000L
        )
        @Volatile private var rotatingIntervalIndex = 0
        private val rotatingIntervalLock = Any()

        private fun nextRotatingInterval(): Long {
            val idx = synchronized(rotatingIntervalLock) {
                val i = rotatingIntervalIndex % ROTATING_CHECK_INTERVALS_MS.size
                rotatingIntervalIndex++
                i
            }
            return ROTATING_CHECK_INTERVALS_MS[idx]
        }
        private const val BALANCE_TIMEOUT_MS = 25_000L
        private const val EVENT_REFRESH_DELAY_MS = 4_000L
        private const val FOREGROUND_REFRESH_COOLDOWN_MS = 3_000L
        private const val SUCCESS_COOLDOWN_MS = 30_000L   // don't check again within 30s if success
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 2_000L
        private const val CHANNEL_ID = "balance_checker"
        private const val NOTIFICATION_ID = 2013
        private const val KEY_LAST_AIRTIME_DISPLAY = "last_airtime_display"
        private const val KEY_LAST_BALANCE_AMOUNT = "last_balance_amount"
        private const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"

        // Listeners
        @Volatile var balanceResultListener: ((BalanceResult) -> Unit)? = null
        @Volatile var balanceCallback: ((String) -> Unit)? = null  // legacy simple callback

        // State
        @Volatile private var lastKnownDisplay: String = ""
        @Volatile private var lastKnownAmount: Double = -1.0
        @Volatile private var lastCheckTimestamp: Long = 0L
        @Volatile private var checking = false
        private val checkingLock = Any()
        @Volatile private var lastCheckStartedAt = 0L
        @Volatile private var lastSuccessfulCheckAt = 0L
        @Volatile private var appContextRef: Context? = null

        private val timeoutHandler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null
        private var pendingRefreshRunnable: Runnable? = null
        private var activeRequestContext: BalanceRequestContext? = null
        private var retryCount = 0
        private var retryRunnable: Runnable? = null

        private data class BalanceRequestContext(
            val selectionOverride: Int? = null,
            val persistResult: Boolean = true,
            val isRetry: Boolean = false
        )

        private data class BalanceCandidate(
            val amount: Double,
            val currency: String,
            val score: Int
        )

        // ─── Public API ──────────────────────────────────────────────────

        /**
         * Request a balance check. Returns true if started successfully.
         * If a check is already in progress and specialHandling is true, it will be queued.
         */
        fun requestBalanceCheck(
            context: Context,
            selectionOverride: Int? = null,
            persistResult: Boolean = selectionOverride == null,
            ignoreCooldown: Boolean = false,
            specialHandling: Boolean = false
        ): Boolean {
            val appContext = context.applicationContext
            appContextRef = appContext
            val now = System.currentTimeMillis()

            // If we have a recent successful check and cooldown applies, skip
            if (!ignoreCooldown && selectionOverride == null) {
                if (now - lastSuccessfulCheckAt < SUCCESS_COOLDOWN_MS && lastKnownAmount > 0) {
                    Log.d(TAG, "Skipping balance check – successful check within cooldown")
                    return false
                }
                if (now - lastCheckStartedAt < FOREGROUND_REFRESH_COOLDOWN_MS) {
                    Log.d(TAG, "Skipping balance check – cooldown active")
                    return false
                }
            }

            // If we're already checking, queue if special
            synchronized(checkingLock) {
                if (checking) {
                    if (specialHandling) {
                        queueBalanceCheck(appContext, selectionOverride, persistResult)
                        Log.d(TAG, "Balance check already in flight, queued for later")
                        return@synchronized true
                    }
                    Log.d(TAG, "Balance check already in flight – skipping duplicate")
                    return@synchronized false
                }
            }

            // Check if another USSD session is busy
            if (selectionOverride == null && isUssdSessionBusy()) {
                if (specialHandling) {
                    queueBalanceCheck(appContext, selectionOverride, persistResult)
                    Log.d(TAG, "USSD session busy – balance check queued")
                    return true
                }
                Log.d(TAG, "USSD session busy – skipping balance check")
                return false
            }

            // Reset retry counter when starting fresh
            retryCount = 0
            startBalanceCheck(appContext, selectionOverride, persistResult)
            return true
        }

        /**
         * Schedule a balance refresh after a delay. Useful after USSD actions.
         */
        fun scheduleAirtimeRefresh(
            context: Context,
            reason: String,
            delayMs: Long = EVENT_REFRESH_DELAY_MS
        ): Boolean {
            val appContext = context.applicationContext
            appContextRef = appContext
            val automationEnabled = appContext
                .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getBoolean("automation_enabled", true)
            if (!automationEnabled) {
                Log.d(TAG, "Skipping scheduled refresh for $reason – automation disabled")
                return false
            }

            // Ensure service is running
            ServiceLauncher.startBalanceChecker(appContext)

            // Cancel any pending refresh
            pendingRefreshRunnable?.let { timeoutHandler.removeCallbacks(it) }
            val runnable = Runnable {
                pendingRefreshRunnable = null
                val started = requestBalanceCheck(
                    context = appContext,
                    ignoreCooldown = true
                )
                Log.d(TAG, "Scheduled refresh reason=$reason started=$started")
            }
            pendingRefreshRunnable = runnable
            timeoutHandler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
            return true
        }

        /**
         * Parse balance from raw USSD response (static, for external use).
         */
        fun parseBalanceDisplay(raw: String): String =
            extractBalanceCandidate(raw)?.let { formatAmount(it.amount, it.currency) } ?: ""

        fun parseBalanceInt(raw: String): Int =
            extractBalanceCandidate(raw)?.amount?.toInt() ?: -1

        /**
         * Get last known balance display (from cache).
         */
        fun getLastKnownBalanceDisplay(context: Context): String {
            if (lastKnownDisplay.isNotEmpty()) return lastKnownDisplay
            val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            return prefs.getString(KEY_LAST_AIRTIME_DISPLAY, "")?.trim().orEmpty()
                .also { if (it.isNotEmpty()) lastKnownDisplay = it }
        }

        fun getLastKnownBalanceAmount(): Double = lastKnownAmount

        var currentBalanceStr: String
            get() = lastKnownDisplay
            set(value) {
                lastKnownDisplay = value.trim()
            }

        var currentBalance: Int
            get() = if (lastKnownAmount >= 0) lastKnownAmount.toInt() else -1
            set(value) {
                lastKnownAmount = value.toDouble()
            }

        /**
         * Persist balance to shared preferences.
         */
        fun persistLastKnownBalance(context: Context, display: String) {
            appContextRef = context.applicationContext
            val clean = display.trim()
            if (clean.isEmpty()) return
            lastKnownDisplay = clean
            val amount = extractBalanceCandidate(clean)?.amount ?: -1.0
            if (amount > 0) {
                lastKnownAmount = amount
                lastSuccessfulCheckAt = System.currentTimeMillis()
                context.applicationContext
                    .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_AIRTIME_DISPLAY, clean)
                    .putFloat(KEY_LAST_BALANCE_AMOUNT, amount.toFloat())
                    .putLong(KEY_LAST_CHECK_TIMESTAMP, System.currentTimeMillis())
                    .apply()
            }
        }

        // ─── Internal Core ──────────────────────────────────────────────

        private fun startBalanceCheck(
            context: Context,
            selectionOverride: Int?,
            persistResult: Boolean
        ) {
            checking = true
            lastCheckStartedAt = System.currentTimeMillis()
            activeRequestContext = BalanceRequestContext(selectionOverride, persistResult)
            armTimeout()

            val balanceUssd = resolveBalanceUssdCode(context, selectionOverride)
            Log.d(TAG, "Starting balance check via $balanceUssd (slot=${selectionOverride ?: "default"})")

            // Try UssdHelper (silent) first
            val helperSuccess = UssdHelper.dialUssd(
                context,
                balanceUssd,
                silentOnly = true,
                subIdOverride = selectionOverride,
                onSuccess = { response ->
                    Log.d(TAG, "UssdHelper success: '$response'")
                    handleBalanceResponse(context, response, activeRequestContext)
                },
                onFailure = { error ->
                    Log.e(TAG, "UssdHelper failed: $error, trying fallback")
                    if (!startBalanceFallback(context, balanceUssd, selectionOverride, persistResult)) {
                        Log.w(TAG, "Fallback unavailable, checking retry")
                        scheduleRetry(context, selectionOverride, persistResult)
                    }
                }
            )
            if (!helperSuccess) {
                if (!startBalanceFallback(context, balanceUssd, selectionOverride, persistResult)) {
                    scheduleRetry(context, selectionOverride, persistResult)
                }
            }
        }

        private fun handleBalanceResponse(
            context: Context,
            raw: String,
            requestContext: BalanceRequestContext?
        ) {
            cancelTimeout()
            checking = false
            val ctx = requestContext ?: BalanceRequestContext()
            activeRequestContext = null
            retryCount = 0 // reset on success

            val candidate = extractBalanceCandidate(raw)
            val display = candidate?.let { formatAmount(it.amount, it.currency) } ?: ""
            val amount = candidate?.amount ?: -1.0

            // Update cache
            if (ctx.persistResult && display.isNotEmpty()) {
                persistLastKnownBalance(context, display)
                // Sync to relay
                RelayManager.syncPrimaryAirtimeBalance(context, display)
                // Trigger alerts
                MpesaReceiver.checkAndSendAlerts(context)
            }

            val result = BalanceResult(
                display = display,
                rawAmount = amount,
                currency = candidate?.currency ?: "",
                rawText = raw,
                timestamp = System.currentTimeMillis(),
                sourceSimSlot = ctx.selectionOverride?.let { slotFromOverride(it) },
                success = display.isNotEmpty(),
                errorMessage = if (display.isEmpty()) "Could not parse balance" else null,
                selectionOverride = ctx.selectionOverride,
                persistResult = ctx.persistResult
            )

            // Notify listeners on main thread
            Handler(Looper.getMainLooper()).post {
                balanceCallback?.invoke(display)
                balanceResultListener?.invoke(result)
                // Also broadcast
                context.sendBroadcast(
                    Intent("com.bingwa.mobile.BALANCE_UPDATED")
                        .setPackage(context.packageName)
                        .putExtra("display", display)
                        .putExtra("amount", amount)
                        .putExtra("timestamp", result.timestamp)
                )
            }

            // If failed, schedule retry if we have retries left
            if (display.isEmpty() && retryCount < MAX_RETRY_ATTEMPTS) {
                scheduleRetry(context, ctx.selectionOverride, ctx.persistResult)
            }
        }

        private fun scheduleRetry(context: Context, selectionOverride: Int?, persistResult: Boolean) {
            if (retryCount >= MAX_RETRY_ATTEMPTS) {
                Log.w(TAG, "Max retries reached, giving up")
                notifyFailure(context, selectionOverride, persistResult)
                return
            }
            retryCount++
            val delay = RETRY_BACKOFF_MS * retryCount
            Log.d(TAG, "Scheduling retry #$retryCount in ${delay}ms")
            retryRunnable?.let { timeoutHandler.removeCallbacks(it) }
            val runnable = Runnable {
                retryRunnable = null
                startBalanceCheck(context, selectionOverride, persistResult)
            }
            retryRunnable = runnable
            timeoutHandler.postDelayed(runnable, delay)
        }

        private fun notifyFailure(context: Context, selectionOverride: Int?, persistResult: Boolean) {
            checking = false
            activeRequestContext = null
            val result = BalanceResult(
                display = "",
                rawAmount = -1.0,
                currency = "",
                rawText = "",
                timestamp = System.currentTimeMillis(),
                sourceSimSlot = selectionOverride?.let { slotFromOverride(it) },
                success = false,
                errorMessage = "Balance check failed after $MAX_RETRY_ATTEMPTS attempts",
                selectionOverride = selectionOverride,
                persistResult = persistResult
            )
            Handler(Looper.getMainLooper()).post {
                balanceCallback?.invoke("")
                balanceResultListener?.invoke(result)
            }
        }

        private fun startBalanceFallback(
            context: Context,
            balanceUssd: String,
            selectionOverride: Int?,
            persistResult: Boolean
        ): Boolean {
            if (isUssdSessionBusy()) return false
            val intent = Intent(context, AutomationService::class.java).apply {
                putExtra("mode", "SIMPLE")
                putExtra("code", balanceUssd)
                putExtra("phoneNumber", "")
                putExtra("simSelection", selectionOverride ?: OFFER_SIM_USE_GENERAL)
                putExtra("executionPriority", USSD_EXECUTION_PRIORITY_SPECIAL)
            }
            return ServiceLauncher.startAutomationService(context, intent)
        }

        private fun queueBalanceCheck(context: Context, selectionOverride: Int?, persistResult: Boolean) {
            UssdQueue.enqueue(
                task = Runnable {
                    requestBalanceCheck(
                        context = context,
                        selectionOverride = selectionOverride,
                        persistResult = persistResult,
                        ignoreCooldown = true,
                        specialHandling = true
                    )
                },
                priority = USSD_EXECUTION_PRIORITY_SPECIAL
            )
        }

        // ─── Parsing ──────────────────────────────────────────────────────

        private fun extractBalanceCandidate(raw: String): BalanceCandidate? {
            val normalized = normalizeBalanceText(raw)

            val safaricomBalRe = Regex("""airtime\s*bal[:\s]+(?:ksh[s]?|kes)?\s*([\d,]+(?:\.\d{1,2})?)\s*(ksh[s]?|kes)?|airtime\s*bal[:\s]+(ksh[s]?|kes)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
            safaricomBalRe.find(normalized)?.let { m ->
                val amountStr = m.groupValues[1].ifBlank { m.groupValues[3] }.replace(",", "")
                val currency = m.groupValues[2].ifBlank { m.groupValues[4] }.uppercase().ifBlank { "KSh" }
                val amount = amountStr.toDoubleOrNull() ?: return@let
                return BalanceCandidate(amount, currency, 10000)
            }

            val yourBalRe = Regex("""your\s+balance\s+is\s+(?:ksh[s]?\.?|kes\.?)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
            yourBalRe.find(normalized)?.let { m ->
                val amountStr = m.groupValues[1].replace(",", "")
                val amount = amountStr.toDoubleOrNull() ?: return@let
                return BalanceCandidate(amount, "KSh", 10000)
            }

            val candidates = mutableListOf<BalanceCandidate>()

            // Patterns ordered by priority
            val patterns = listOf(
                // "Your balance is KSh 100.50"
                Regex("""your\s+balance\s+is\s+(ksh[s]?|kes|usd|eur)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                // "Balance: KSh 100.50"
                Regex("""(?:balance|airtime\s*bal(?:ance)?|salio|umbea|account\s+balance)[:\s-]*(?:is\s*)?(?:ksh[s]?|kes|usd|eur)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                // "KSh 100.50"
                Regex("""(ksh[s]?|kes|usd|eur)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                // "100.50 KSh"
                Regex("""([\d,]+(?:\.\d{1,2})?)\s*(ksh[s]?|kes|usd|eur)""", RegexOption.IGNORE_CASE),
                // "100.50 /="
                Regex("""([\d,]+(?:\.\d{1,2})?)\s*/="""),
                // Plain digits with comma
                Regex("""([\d,]+(?:\.\d{1,2})?)""")
            )

            patterns.forEachIndexed { idx, pattern ->
                pattern.findAll(normalized).forEach { match ->
                    val groups = match.groupValues
                    var amountStr = ""
                    var currency = ""
                    when {
                        // Pattern with currency first
                        groups.size == 3 && groups[1].matches(Regex("[a-zA-Z]+")) -> {
                            currency = groups[1].uppercase()
                            amountStr = groups[2]
                        }
                        // Pattern with currency second
                        groups.size == 3 && groups[2].matches(Regex("[a-zA-Z]+")) -> {
                            currency = groups[2].uppercase()
                            amountStr = groups[1]
                        }
                        // Currency only pattern
                        groups.size == 2 && groups[1].matches(Regex("[a-zA-Z]+")) -> {
                            // If only currency, look for amount separately
                            val nextDigit = Regex("""\b([\d,]+(?:\.\d{1,2})?)\b""").find(normalized, match.range.last + 1)
                            if (nextDigit != null) {
                                currency = groups[1].uppercase()
                                amountStr = nextDigit.value
                            }
                        }
                        // Plain number
                        else -> {
                            amountStr = groups[1]
                            currency = "KSh" // default
                        }
                    }
                    val amount = amountStr.replace(",", "").toDoubleOrNull() ?: return@forEach
                    if (amount > 0 && amount < 1_000_000) {
                        // Score based on pattern index (earlier patterns = higher confidence)
                        val score = (patterns.size - idx) * 100 + if (currency.isNotBlank()) 20 else 0
                        candidates += BalanceCandidate(amount, currency, score)
                    }
                }
            }

            // If no candidate, try the fallback for M-PESA style messages
            if (candidates.isEmpty() &&
                Regex("""balance|airtime|salio|umbea|tariff|mpesa""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized)
            ) {
                Regex("""\b([\d,]+(?:\.\d{1,2})?)\b""")
                    .findAll(normalized)
                    .forEach { match ->
                        val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@forEach
                        if (amount > 0 && amount < 1_000_000) {
                            candidates += BalanceCandidate(amount, "KSh", 10)
                        }
                    }
            }

            return candidates.maxWithOrNull(
                compareBy<BalanceCandidate> { it.score }
                    .thenBy { it.amount }
            )
        }

        private fun normalizeBalanceText(raw: String): String =
            raw.replace(Regex("""[_=~`|•]+"""), " ")
                .replace(Regex("""[^\S\r\n]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

        internal fun formatAmount(amount: Double, currency: String = "KSh"): String {
            val formatted = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
                minimumFractionDigits = if (amount == kotlin.math.floor(amount)) 0 else 2
                maximumFractionDigits = 2
            }.format(amount)
            return "$currency $formatted"
        }

        fun resolveBalanceUssdCode(context: Context, selectionOverride: Int?): String {
            return if (isAirtelBalanceTarget(context, selectionOverride)) {
                AIRTEL_BALANCE_USSD
            } else {
                DEFAULT_BALANCE_USSD
            }
        }

        private fun isAirtelBalanceTarget(context: Context, selectionOverride: Int?): Boolean {
            val targetSubId = resolvePreferredUssdSubId(context, selectionOverride) ?: return false
            val targetSim = getAvailableSims(context).firstOrNull { it.subscriptionId == targetSubId } ?: return false
            val labels = buildList {
                add(targetSim.displayName?.toString().orEmpty())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(targetSim.carrierName?.toString().orEmpty())
                }
            }.joinToString(" ").trim()
            return labels.contains("airtel", ignoreCase = true)
        }

        private fun slotFromOverride(override: Int): Int? = when (override) {
            USSD_SIM_SELECTION_SLOT_1 -> 0
            USSD_SIM_SELECTION_SLOT_2 -> 1
            else -> null
        }

        // ─── Timeout ──────────────────────────────────────────────────────

        private fun armTimeout() {
            cancelTimeout()
            val timeout = Runnable {
                Log.w(TAG, "Balance check timed out")
                onBalanceCheckFailed()
            }
            timeoutRunnable = timeout
            timeoutHandler.postDelayed(timeout, BALANCE_TIMEOUT_MS)
        }

        private fun cancelTimeout() {
            timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
            timeoutRunnable = null
        }

        private fun onBalanceCheckFailed() {
            Log.w(TAG, "Balance check failed – resetting")
            cancelTimeout()
            checking = false
            val ctx = activeRequestContext ?: BalanceRequestContext()
            activeRequestContext = null
            val appContext = appContextRef ?: return
            // If we have retries left, schedule one
            if (retryCount < MAX_RETRY_ATTEMPTS) {
                scheduleRetry(appContext, ctx.selectionOverride, ctx.persistResult)
            } else {
                notifyFailure(appContext, ctx.selectionOverride, ctx.persistResult)
            }
        }

        // ─── Busy detection ──────────────────────────────────────────────

        private fun isUssdSessionBusy(): Boolean =
            UssdNavigationService.isBusyForBalanceCheck() ||
                SilentUssd.isExecutionInProgress() ||
                SilentUssdOptimized.isExecutionInProgress()

        // ─── Cache / Persistence ──────────────────────────────────────────

        fun getLastCheckTimestamp(): Long {
            if (lastCheckTimestamp > 0) return lastCheckTimestamp
            val prefs = appContextRef?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            return prefs?.getLong(KEY_LAST_CHECK_TIMESTAMP, 0L) ?: 0L
        }

        fun primeCachedState(context: Context) {
            val appContext = context.applicationContext
            appContextRef = appContext
            if (lastKnownDisplay.isEmpty()) {
                lastKnownDisplay = appContext
                    .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    .getString(KEY_LAST_AIRTIME_DISPLAY, "")
                    ?.trim()
                    .orEmpty()
            }
            if (lastKnownAmount < 0) {
                lastKnownAmount = appContext
                    .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    .getFloat(KEY_LAST_BALANCE_AMOUNT, -1f)
                    .toDouble()
            }
            if (lastCheckTimestamp <= 0L) {
                lastCheckTimestamp = appContext
                    .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_CHECK_TIMESTAMP, 0L)
            }
        }
    }

    // ─── Service Lifecycle ────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private val periodicCheck = object : Runnable {
        override fun run() {
            val prefs = applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("automation_enabled", true)) {
                handler.removeCallbacks(this)
                stopSelf()
                return
            }
            val interval = nextRotatingInterval()
            rotatingIntervalIndex++
            // Check balance only if we have a stale cache or long time since last check
            val lastCheck = getLastCheckTimestamp()
            if (System.currentTimeMillis() - lastCheck > interval) {
                requestBalanceCheck(applicationContext)
            }
            // Also check battery alerts
            MpesaReceiver.checkAndSendAlerts(applicationContext)
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Warm up cache
        primeCachedState(applicationContext)
        getLastKnownBalanceDisplay(applicationContext)
        getLastKnownBalanceAmount()
        createNotificationChannel()
        foregroundReady = tryStartForegroundCompat(
            notificationId = NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync,
            serviceLabel = "Background balance monitoring"
        )
        if (!foregroundReady) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf()
            return START_NOT_STICKY
        }
        val automationEnabled = applicationContext
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("automation_enabled", true)
        if (!automationEnabled) {
            handler.removeCallbacks(periodicCheck)
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(periodicCheck)
        handler.post(periodicCheck)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicCheck)
        checking = false
        cancelTimeout()
        timeoutRunnable = null
        pendingRefreshRunnable = null
        retryRunnable = null
        stopForegroundCompat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Foreground Helpers ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Balance Monitoring", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bingwa Mobile")
            .setContentText("Monitoring airtime balance")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .build()

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
