package com.bingwa.mobile

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bingwa.mobile.ForegroundServiceTypes
import java.util.Calendar

// ============================================================
// AUTOMATION SERVICE – FIXED
// ============================================================

class AutomationService : Service() {

    // region Delegates (separate responsibilities)
    private val dispatcher = UssdDispatcher(this)
    private val responseAnalyzer = ResponseAnalyzer(this)
    private val retryManager = RetryManager(this)
    private val transactionHelper = TransactionHelper(this)
    private val notificationHelper = NotificationHelper(this)
    private val foregroundHelper = ForegroundServiceHelper(this)

    private var foregroundReady = false

    // region Lifecycle
    override fun onCreate() {
        super.onCreate()
        foregroundHelper.createNotificationChannel()
        foregroundReady = foregroundHelper.startForeground()
        if (!foregroundReady) {
            Log.w(TAG, "startForeground failed; service will continue and retry on next request")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            foregroundReady = foregroundHelper.startForeground()
            if (!foregroundReady) {
                Log.w(TAG, "Service cannot promote to foreground; re-enqueueing request for later")
                val request = UssdRequest.fromIntent(intent)
                if (request != null) {
                    UssdQueue.enqueue(
                        Runnable { executeRequest(request) },
                        priority = request.executionPriority
                    )
                }
                return START_REDELIVER_INTENT
            }
        }

        val request = UssdRequest.fromIntent(intent) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Received request txId=${request.txId} mode=${request.mode}")

        // Global blacklist check
        if (request.phoneNumber.isNotBlank() &&
            BlacklistedContactStore.isBlacklisted(this, request.phoneNumber)
        ) {
            transactionHelper.finishWithError(request, "Blocked: blacklisted phone number")
            return START_NOT_STICKY
        }

        // Enqueue for background execution
        UssdQueue.enqueue(
            Runnable { executeRequest(request) },
            priority = request.executionPriority
        )

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        try {
            UssdQueue.cancelAllPending()
            UssdNavigationService.advancedActive = false
            UssdNavigationService.advancedInProgress = false
            UssdNavigationService.onDispatchComplete = null
            UssdNavigationService.isUsdExecutionLocked = false
            UssdNavigationService.tokenPurchaseCallback = null
            UssdNavigationService.balanceCallback = null
        } catch (_: Exception) {
            Log.e(TAG, "onDestroy cleanup failed", _)
        } finally {
            foregroundHelper.stopForeground()
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    // endregion

    // region Request Execution
    private fun executeRequest(request: UssdRequest) {
        Log.d(TAG, "Executing txId=${request.txId} mode=${request.mode}")

        // If this is a retry alarm, we arm the active window
        if (request.action == ACTION_RETRY_RETRIABLE_RESPONSE) {
            retryManager.armRetryWindow(request.txId)
        }

        // If this is a scheduled run, mark it as executed
        if (request.action == ACTION_RUN_SCHEDULED) {
            ScheduledOfferDispatchStore.markExecuted(this, request.txId)
            transactionHelper.saveAndBroadcast(
                request.txId,
                TransactionStatus.PROCESSING.value,
                "Scheduled dispatch started."
            )
        }

        // Delegate to the appropriate handler
        if (request.isAdvancedFlow) {
            dispatcher.startAdvanced(request) { result ->
                handleAdvancedResult(request, result)
            }
        } else {
            dispatcher.startSimple(request) { response, status ->
                handleSimpleResponse(request, response, status)
            }
        }
    }

    private fun handleSimpleResponse(request: UssdRequest, response: String, status: String) {
        Log.d(TAG, "Simple response txId=${request.txId} status=$status response='${response.take(80)}'")
        processFinalResponse(request, response, status, null)
    }

    private fun handleAdvancedResult(request: UssdRequest, result: AdvancedDispatchResult) {
        Log.d(TAG, "Advanced result txId=${request.txId} changeDetected=${result.changeDetected}")
        if (request.signatureLearning) {
            handleSignatureLearning(request, result)
            return
        }

        val finalResponse = if (result.changeDetected) {
            buildSignatureChangeMessage(request, result)
        } else {
            result.finalResponse
        }

        val status = responseAnalyzer.determineStatus(result.finalResponse)

        processFinalResponse(request, finalResponse, status, result.popupTranscript)
    }
    // endregion

    // region Response Processing (unified)
    private fun processFinalResponse(
        request: UssdRequest,
        response: String,
        status: String,
        transcript: List<String>?
    ) {
        // Handle token purchase / balance callbacks first
        if (handleCallback(request, response)) {
            finishExecution(scheduleAirtimeRefresh = true)
            return
        }

        if (request.txId < 0) {
            finishExecution(scheduleAirtimeRefresh = true)
            return
        }

        // Check if we should retry (retriable final response)
        if (responseAnalyzer.shouldRetry(status, response)) {
            retryManager.scheduleRetry(request, response, status, transcript)
            return
        }

        // Clear any retry state for this transaction
        retryManager.clearState(request.txId)

        // Save and broadcast
        transactionHelper.saveAndBroadcast(request.txId, status, response, transcript)

        // Send completion notification
        sendCompletionNotification(request, status, response)

        // Handle special statuses
        when (status) {
            TransactionStatus.PENDING.value -> handlePending(request, response)
            TransactionStatus.FAILED.value -> handleFailed(request, response)
        }

        if (request.action == ACTION_RUN_SCHEDULED && status == TransactionStatus.SUCCESS.value) {
            sendScheduledDispatchNotification(request)
        }

        finishExecution(scheduleAirtimeRefresh = true)
    }

    private fun sendCompletionNotification(request: UssdRequest, status: String, response: String) {
        val offerLabel = request.offerName.ifBlank { "offer #${request.offerId}" }
        val phone = request.phoneNumber.ifBlank { "unknown" }
        when (status) {
            TransactionStatus.SUCCESS.value -> {
                OfferNotifications.notify(
                    this,
                    "Bundle Dispatched Successfully",
                    "$offerLabel sent to $phone. ${response.take(160)}"
                )
            }
            TransactionStatus.FAILED.value -> {
                OfferNotifications.notify(
                    this,
                    "Bundle Dispatch Failed",
                    "$offerLabel failed for $phone. ${response.take(160)}"
                )
            }
            TransactionStatus.PENDING.value -> {
                OfferNotifications.notify(
                    this,
                    "Bundle Pending",
                    "$offerLabel for $phone is pending. ${response.take(160)}"
                )
            }
            TransactionStatus.RETRYING.value -> {
                OfferNotifications.notify(
                    this,
                    "Retrying Dispatch",
                    "$offerLabel for $phone - retrying... ${response.take(160)}"
                )
            }
        }
    }

    private fun handleCallback(request: UssdRequest, response: String): Boolean {
        UssdNavigationService.tokenPurchaseCallback?.let { cb ->
            val success = responseAnalyzer.isSuccess(response)
            cb(success)
            UssdNavigationService.tokenPurchaseCallback = null
            return true
        }

        UssdNavigationService.balanceCallback?.let { cb ->
            cb(response)
            UssdNavigationService.balanceCallback = null
            return true
        }

        return false
    }

    private fun handlePending(request: UssdRequest, response: String) {
        val config = DailyLimitPolicy.load(this)
        if (config.fallbackEnabled &&
            DailyLimitPolicy.ruleIncludesAlreadyRecommended(config.fallbackRuleMode)
        ) {
            // Try fallback
            val fallbackStarted = dispatcher.startFallback(request, response, "daily limit")
            if (fallbackStarted) {
                notificationHelper.notifyFallback(request, response)
                return
            }
        }

        if (config.mode == DailyLimitPolicy.MODE_NOTICE_ONLY) {
            val note = if (config.repeatNoticeEnabled) {
                "Reply 1 to send another number today, or reply 2 to confirm tomorrow morning dispatch."
            } else {
                "Waiting for an alternative number or a manual retry tomorrow."
            }
            val message = "$response\n\n$note"
            transactionHelper.saveAndBroadcast(request.txId, TransactionStatus.PENDING.value, message)
            if (config.repeatNoticeEnabled) {
                request.phoneNumber.takeIf { it.isNotBlank() }?.let {
                    DailyLimitPolicy.beginReplyMenu(this, it, request.txId)
                }
            }
            notificationHelper.notifyPending(request)
        } else {
            retryManager.scheduleRetryTomorrow(request)
        }
    }

    private fun handleFailed(request: UssdRequest, response: String) {
        val config = DailyLimitPolicy.load(this)
        if (config.fallbackEnabled &&
            (DailyLimitPolicy.ruleIncludesOfferNotFound(config.fallbackRuleMode) ||
                    DailyLimitPolicy.ruleIncludesAlreadyRecommended(config.fallbackRuleMode))
        ) {
            val fallbackStarted = dispatcher.startFallback(request, response, "failure")
            if (fallbackStarted) {
                notificationHelper.notifyFallback(request, response)
                return
            }
        }
        transactionHelper.refundTokenIfNeeded(request)
        MpesaReceiver.checkAndSendAlerts(this, "Failed", response.take(100))
    }
    // endregion

    // region Signature Learning
    private fun handleSignatureLearning(request: UssdRequest, result: AdvancedDispatchResult) {
        if (request.offerId < 0) {
            notificationHelper.notifyLearningNoOffer(request)
            finishExecution(scheduleAirtimeRefresh = false)
            return
        }

        if (result.learnedSignature.isEmpty() && result.learningCaptures.isEmpty()) {
            notificationHelper.notifyLearningFailed(request)
            finishExecution(scheduleAirtimeRefresh = false)
            return
        }

        // Save the learned signature for approval
        OfferRepository.stageSignatureReview(
            this,
            request.offerId,
            result.learnedSignature,
            result.learningCaptures
        )

        notificationHelper.notifyLearningSuccess(request, result)
        sendBroadcast(Intent("com.bingwa.mobile.OFFER_SIGNATURE_LEARNED")
            .setPackage(packageName)
            .putExtra("offerId", request.offerId))

        finishExecution(scheduleAirtimeRefresh = false)
    }
    // endregion

    // region Helpers
    private fun buildSignatureChangeMessage(request: UssdRequest, result: AdvancedDispatchResult): String {
        val offerLabel = request.offerName.ifBlank { "this offer" }
        val suggestion = result.suggestedCode.takeIf { it.isNotBlank() }?.let {
            " Suggested updated code: $it."
        }.orEmpty()
        return if (result.autoAdjusted) {
            "The system detected a change in the USSD menu for $offerLabel and automatically matched the correct option.${result.changeSummary.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""} Open the offer, update the saved USSD code, then run Save & Learn again to relearn the signature for future dispatches.$suggestion"
        } else {
            "The system detected changes in the USSD menu for $offerLabel and stopped the dispatch to avoid selecting the wrong bundle.${result.changeSummary.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""} Review and update the offer, then run Save & Learn again to relearn the signature before dispatching again.$suggestion"
        }
    }

    private fun finishExecution(scheduleAirtimeRefresh: Boolean) {
        if (scheduleAirtimeRefresh) {
            BalanceChecker.scheduleAirtimeRefresh(this, "USSD execution")
        }
        stopSelf()
    }
    // endregion

    // region Constants
    companion object {
        const val TAG = "AutomationService"
        const val ACTION_RETRY_PENDING = "com.bingwa.mobile.ACTION_RETRY_PENDING"
        const val ACTION_RETRY_MAINTENANCE = "com.bingwa.mobile.ACTION_RETRY_MAINTENANCE"
        const val ACTION_RETRY_RETRIABLE_RESPONSE = "com.bingwa.mobile.ACTION_RETRY_RETRIABLE_RESPONSE"
        const val ACTION_RUN_SCHEDULED = "com.bingwa.mobile.ACTION_RUN_SCHEDULED"
        private const val CHANNEL_ID = "automation_service"
        private const val NOTIFICATION_ID = 2014
        private const val RETRIABLE_RETRY_PREFS_NAME = "retriable_ussd_response_retry"
        private const val ACTIVE_RETRY_WINDOW_MS = 90_000L
        private const val ACTIVE_RETRY_INTERVAL_MS = 7_000L
        private const val FIRST_BACKOFF_MS = 10 * 60_000L
        private const val REPEATED_BACKOFF_MS = 10 * 60_000L

        fun cancelRetriableResponseRetry(context: Context, txId: Int) {
            if (txId < 0) return
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, AutomationService::class.java).apply {
                    action = ACTION_RETRY_RETRIABLE_RESPONSE
                }
                val pi = PendingIntent.getService(
                    appContext,
                    txId,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                (appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
                pi.cancel()
                appContext.getSharedPreferences(RETRIABLE_RETRY_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove("tx_${txId}_windowStart")
                    .remove("tx_${txId}_completedWindows")
                    .remove("tx_${txId}_nextWindow")
                    .remove("tx_${txId}_attempts")
                    .apply()
            }
        }
    }
    // endregion

    // ============================================================
    // INNER HELPER CLASSES
    // ============================================================

    // region UssdRequest – data class for request parameters
    private data class UssdRequest(
        val action: String?,
        val code: String,
        val phoneNumber: String,
        val txId: Int,
        val mode: String,
        val offerId: Int,
        val offerName: String,
        val simSelection: Int,
        val signatureEnabled: Boolean,
        val signatureMode: String,
        val signatureLearning: Boolean,
        val executionPriority: String,
        val returnToAppAggressively: Boolean
    ) {
        val isAdvancedFlow: Boolean
            get() = signatureEnabled || signatureLearning ||
                    mode.equals(OFFER_EXECUTION_MODE_ADVANCED, ignoreCase = true)

        companion object {
            fun fromIntent(intent: Intent?): UssdRequest? {
                intent ?: return null
                val code = intent.getStringExtra("code") ?: return null
                val rawPhone = intent.getStringExtra("phoneNumber") ?: ""
                val phone = rawPhone.takeIf { it.isBlank() }
                    ?: UssdHelper.normalizeRecipientForUssdInput(rawPhone)
                val priority = intent.getStringExtra("executionPriority") ?: when (intent.action) {
                    ACTION_RUN_SCHEDULED, ACTION_RETRY_PENDING,
                    ACTION_RETRY_MAINTENANCE, ACTION_RETRY_RETRIABLE_RESPONSE ->
                        USSD_EXECUTION_PRIORITY_SPECIAL
                    else -> USSD_EXECUTION_PRIORITY_NORMAL
                }
                return UssdRequest(
                    action = intent.action,
                    code = code,
                    phoneNumber = phone,
                    txId = intent.getIntExtra("txId", -1),
                    mode = intent.getStringExtra("mode") ?: OFFER_EXECUTION_MODE_SIMPLE,
                    offerId = intent.getIntExtra("offerId", -1),
                    offerName = intent.getStringExtra("offerName") ?: "",
                    simSelection = normalizeOfferSimSelection(
                        intent.getIntExtra("simSelection", OFFER_SIM_USE_GENERAL)
                    ),
                    signatureEnabled = intent.getBooleanExtra("signatureEnabled", false),
                    signatureMode = (intent.getStringExtra("signatureMode") ?: "STOP").uppercase(),
                    signatureLearning = intent.getBooleanExtra("signatureLearning", false),
                    executionPriority = priority,
                    returnToAppAggressively = intent.getBooleanExtra("returnToAppAggressively", true)
                )
            }
        }
    }
    // endregion

    private data class RetryState(
        val windowStartAtMillis: Long,
        val completedWindows: Int,
        val nextAttemptStartsNewWindow: Boolean,
        val totalAttempts: Int
    )

    // region UssdDispatcher – handles USSD execution (simple & advanced)
    private inner class UssdDispatcher(private val context: Context) {
        private val simResolver = SimResolver(context)

        fun startSimple(request: UssdRequest, onComplete: (String, String) -> Unit) {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm == null) {
                onComplete("Telephony unavailable", TransactionStatus.FAILED.value)
                return
            }

            val targets = simResolver.getTargets(request.simSelection)
            if (targets.isEmpty()) {
                onComplete("No available SIM", TransactionStatus.FAILED.value)
                return
            }

            // Try each SIM in order
            trySimSimple(request, tm, targets, 0, onComplete)
        }

        private fun trySimSimple(
            request: UssdRequest,
            baseTm: TelephonyManager,
            targets: List<UssdSimTarget>,
            index: Int,
            onComplete: (String, String) -> Unit
        ) {
            if (index >= targets.size) {
                onComplete("All SIMs failed", TransactionStatus.FAILED.value)
                return
            }

            val target = targets[index]
            val tm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                baseTm.createForSubscriptionId(target.subId)
            } else baseTm

            val slotLabel = "slot ${target.slotIndex + 1}"
            Log.d(TAG, "Trying simple USSD on $slotLabel for txId=${request.txId}")

            // Try optimized first, fallback to legacy
            val started = SilentUssdOptimized.execute(
                telephonyManager = tm,
                ussdCode = request.code,
                onSuccess = { response ->
                    Log.d(TAG, "Simple success on $slotLabel")
                    onComplete(response, responseAnalyzer.determineStatus(response))
                },
                onFailure = { error ->
                    Log.w(TAG, "Optimized USSD failed on $slotLabel: $error")
                    // Try legacy
                    if (!SilentUssd.execute(tm, request.code,
                            onSuccess = { response ->
                                Log.d(TAG, "Legacy success on $slotLabel")
                                onComplete(response, responseAnalyzer.determineStatus(response))
                            },
                            onFailure = { legacyError ->
                                Log.w(TAG, "Legacy USSD failed on $slotLabel: $legacyError")
                                trySimSimple(request, baseTm, targets, index + 1, onComplete)
                            }
                        )
                    ) {
                        trySimSimple(request, baseTm, targets, index + 1, onComplete)
                    }
                }
            )

            if (!started) {
                // Fallback to legacy
                if (!SilentUssd.execute(tm, request.code,
                        onSuccess = { response ->
                            Log.d(TAG, "Legacy success on $slotLabel")
                            onComplete(response, responseAnalyzer.determineStatus(response))
                        },
                        onFailure = { error ->
                            Log.w(TAG, "Legacy USSD failed on $slotLabel: $error")
                            trySimSimple(request, baseTm, targets, index + 1, onComplete)
                        }
                    )
                ) {
                    trySimSimple(request, baseTm, targets, index + 1, onComplete)
                }
            }
        }

        fun startAdvanced(request: UssdRequest, onComplete: (AdvancedDispatchResult) -> Unit) {
            val rawCode = request.code
            val phoneNumber = request.phoneNumber
            val steps = extractSteps(rawCode, phoneNumber)
            val dialCode = extractDialCode(rawCode)
            if (steps.isEmpty() || dialCode.isBlank()) {
                onComplete(AdvancedDispatchResult(
                    finalResponse = "Invalid USSD code",
                    changeDetected = false,
                    autoAdjusted = false,
                    learningCompleted = false,
                    suggestedCode = "",
                    changeSummary = "",
                    learnedSignature = emptyList(),
                    learningCaptures = emptyList(),
                    popupTranscript = emptyList()
                ))
                return
            }

            // Set up navigation service
            if (UssdNavigationService.isUsdExecutionLocked) {
                UssdNavigationService.advancedActive = false
                UssdNavigationService.advancedInProgress = false
                UssdNavigationService.onDispatchComplete = null
                UssdNavigationService.isUsdExecutionLocked = false
                onComplete(AdvancedDispatchResult(
                    finalResponse = "Another USSD task is already running. Please wait.",
                    changeDetected = false,
                    autoAdjusted = false,
                    learningCompleted = false,
                    suggestedCode = "",
                    changeSummary = "",
                    learnedSignature = emptyList(),
                    learningCaptures = emptyList(),
                    popupTranscript = emptyList()
                ))
                return
            }
            UssdNavigationService.isUsdExecutionLocked = true
            val keepVisible = request.returnToAppAggressively && BingwaMobileApp.wasInForegroundRecently()
            UssdNavigationService.configureUiReturn(keepVisible)
            UssdNavigationService.onDispatchComplete = { result ->
                UssdNavigationService.isUsdExecutionLocked = false
                onComplete(result)
                UssdNavigationService.onDispatchComplete = null
            }

            UssdNavigationService.advancedSteps = steps
            UssdNavigationService.advancedPhoneNumber = request.phoneNumber
            UssdNavigationService.advancedDialCode = dialCode
            UssdNavigationService.retryCount = 0
            UssdNavigationService.retryWindowStartedAt = 0L
            UssdNavigationService.advancedActive = true
            UssdNavigationService.advancedInProgress = true
            UssdNavigationService.currentStep = 0
            UssdNavigationService.advancedOfferId = request.offerId
            UssdNavigationService.advancedOfferName = request.offerName.takeIf { it.isNotBlank() } ?: "Offer #${request.offerId}"
            UssdNavigationService.signatureGuardEnabled = request.signatureEnabled && !request.signatureLearning
            UssdNavigationService.signatureAction = request.signatureMode
            UssdNavigationService.signatureLearningMode = request.signatureLearning
            UssdNavigationService.loadedSignatureSteps = request.offerId.takeIf { it >= 0 }
                ?.let { OfferRepository.findById(context, it)?.learnedSignature }
                .orEmpty()
            UssdNavigationService.resetSignatureTracking()
            UssdNavigationService.beginAdvancedSessionMonitoring()
            UssdNavigationService.refreshRunningOverlay()

            // Initiate dialing
            val targets = simResolver.getTargets(request.simSelection)
            if (targets.isEmpty()) {
                UssdNavigationService.configureDialPreferences(null, null)
                UssdNavigationService.advancedActive = false
                UssdNavigationService.advancedInProgress = false
                UssdNavigationService.onDispatchComplete = null
                UssdNavigationService.isUsdExecutionLocked = false
                onComplete(AdvancedDispatchResult(
                    finalResponse = "No available SIM",
                    changeDetected = false,
                    autoAdjusted = false,
                    learningCompleted = false,
                    suggestedCode = "",
                    changeSummary = "",
                    learnedSignature = emptyList(),
                    learningCaptures = emptyList(),
                    popupTranscript = emptyList()
                ))
                return
            }

            if (!dialAdvanced(request, dialCode, targets, 0)) {
                UssdNavigationService.advancedActive = false
                UssdNavigationService.advancedInProgress = false
                UssdNavigationService.onDispatchComplete = null
                UssdNavigationService.isUsdExecutionLocked = false
                onComplete(AdvancedDispatchResult(
                    finalResponse = "No dialer available",
                    changeDetected = false,
                    autoAdjusted = false,
                    learningCompleted = false,
                    suggestedCode = "",
                    changeSummary = "",
                    learnedSignature = emptyList(),
                    learningCaptures = emptyList(),
                    popupTranscript = emptyList()
                ))
            }
        }

        private fun extractSteps(code: String, phoneNumber: String? = null): List<String> {
            val clean = code.trim().replace("%23", "#").trimEnd('#')
            val parts = clean.split("*").filter { it.isNotEmpty() }
            if (parts.isEmpty()) return emptyList()
            val normalizedPhone = phoneNumber?.let { SmsCommandHandler.normalizePhone(it) }
            return (1 until parts.size).map {
                if (parts[it].equals("pn", ignoreCase = true)) "INPUT_PHONE"
                else if (!normalizedPhone.isNullOrBlank() && parts[it] == normalizedPhone) "INPUT_PHONE"
                else parts[it]
            }
        }

        private fun extractDialCode(code: String): String {
            val clean = code.trim().replace("%23", "#").trimEnd('#')
            val parts = clean.split("*").filter { it.isNotEmpty() }
            return if (parts.isNotEmpty()) "*${parts[0]}#" else ""
        }

        private fun dialAdvanced(
            request: UssdRequest,
            dialCode: String,
            targets: List<UssdSimTarget>,
            index: Int
        ): Boolean {
            if (index >= targets.size) return false
            val target = targets[index]
            UssdNavigationService.configureDialPreferences(target.subId, target.slotIndex)
            val intent = UssdHelper.buildCallIntent(context, dialCode, target.subId)
            if (intent.resolveActivity(context.packageManager) == null) {
                return dialAdvanced(request, dialCode, targets, index + 1)
            }
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Dial failed on slot ${target.slotIndex + 1}", e)
                dialAdvanced(request, dialCode, targets, index + 1)
            }
        }

        fun startFallback(request: UssdRequest, response: String, reason: String): Boolean {
            val config = DailyLimitPolicy.load(context)
            if (!config.fallbackEnabled) return false

            val fallbackOffers = DailyLimitPolicy.resolveFallbackOffers(context, request.offerId)
            for (offer in fallbackOffers) {
                if (RelayManager.isPrimary(context) && offer.targetDevice.uppercase() == "RELAY") {
                    return RelayManager.forwardBuyAmount(context, request.phoneNumber, offer.price)
                }

                val finalCode = UssdHelper.normalizeUssdCode(offer.ussdCode, request.phoneNumber)
                if (finalCode.isBlank()) continue

                // Create a new transaction for fallback
                val txId = transactionHelper.createFallbackTransaction(request, offer, reason)
                if (txId < 0) continue

                // Start automation for fallback
                startOfferAutomation(
                    offer = offer,
                    phoneNumber = request.phoneNumber,
                    txId = txId,
                    finalCode = finalCode,
                    mode = offer.executionMode
                )
                return true
            }
            return false
        }
    }
    // endregion

    private inner class ResponseAnalyzer(private val context: Context) {
        private val patternManager = UssdResponsePatternManager(context)
        private val whitespaceRegex = Regex("\\s+")

        fun determineStatus(response: String): String {
            if (response.isBlank()) return TransactionStatus.FAILED.value
            val normalized = response.lowercase().trim()
            // Check failures first so they beat ambiguous success wording
            if (patternManager.matchesFailedRetryPattern(response)) return TransactionStatus.FAILED.value
            if (patternManager.matchesFailedPattern(response)) return TransactionStatus.FAILED.value
            if (patternManager.matchesMaintenancePattern(response)) return TransactionStatus.PENDING.value
            if (patternManager.matchesAlreadyRecommendedPattern(response)) return TransactionStatus.PENDING.value
            // Check success only after failures
            if (patternManager.matchesSuccessPattern(response)) return TransactionStatus.SUCCESS.value
            // Retriable patterns
            if (patternManager.matchesRetriableFinalPattern(response)) return TransactionStatus.RETRYING.value
            // Heuristic: if response has meaningful content, treat as success
            if (looksLikeSuccessResponse(normalized)) return TransactionStatus.SUCCESS.value
            // Default: check if it looks like a valid response at all
            if (looksLikeValidResponse(normalized)) return TransactionStatus.SUCCESS.value
            return TransactionStatus.FAILED.value
        }

        fun isSuccess(response: String): Boolean {
            if (response.isBlank()) return false
            val lower = response.lowercase()
            if (patternManager.matchesFailedPattern(response)) return false
            if (lower.contains("insufficient") || lower.contains("failed") || lower.contains("cancelled") || lower.contains("error")) return false
            return patternManager.matchesSuccessPattern(response) ||
                    (lower.contains("you have transferred") && !lower.contains("failed")) ||
                    response.contains("transferred successfully", ignoreCase = true) ||
                    looksLikeSuccessResponse(lower)
        }

        fun shouldRetry(status: String, response: String): Boolean {
            if (response.isBlank()) return false
            if (status in setOf(
                    TransactionStatus.SUCCESS.value,
                    TransactionStatus.PENDING.value,
                    TransactionStatus.CANCELLED.value
                )
            ) return false
            // Don't retry if it's a clear failure
            if (patternManager.matchesFailedPattern(response)) return false
            // Don't retry if it's already recommended
            if (patternManager.matchesAlreadyRecommendedPattern(response)) return false
            // Retry for retriable patterns
            if (patternManager.matchesRetriableFinalPattern(response)) return true
            // Retry for ambiguous responses that might be network issues
            return looksLikeRetriableResponse(response.lowercase().trim())
        }

        private fun looksLikeSuccessResponse(normalized: String): Boolean {
            if (normalized.length < 3) return false
            val successIndicators = listOf(
                "successful", "success", "completed", "done", "finished",
                "processed", "activated", "confirmed", "delivered", "sent",
                "purchased", "bought", "paid", "received", "approved",
                "accepted", "granted", "enabled", "subscribed"
            )
            return successIndicators.any { normalized.contains(it) }
        }

        private fun looksLikeValidResponse(normalized: String): Boolean {
            if (normalized.length < 5) return false
            val hasContent = normalized.any { it.isLetterOrDigit() }
            val hasMultipleWords = normalized.split(whitespaceRegex).size > 2
            return hasContent && hasMultipleWords
        }

        private fun looksLikeRetriableResponse(normalized: String): Boolean {
            if (normalized.length < 3) return false
            val retriableIndicators = listOf(
                "timeout", "network", "error", "failed", "busy", "unavailable",
                "try again", "retry", "later", "wait", "slow", "delayed",
                "connection", "server", "temporary", "overloaded"
            )
            return retriableIndicators.any { normalized.contains(it) }
        }
    }
    // endregion

    // region RetryManager – handles retry scheduling and state
    private inner class RetryManager(private val context: Context) {
        private val prefs by lazy {
            context.getSharedPreferences(RETRIABLE_RETRY_PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun scheduleRetry(request: UssdRequest, response: String, status: String, transcript: List<String>?) {
            val now = System.currentTimeMillis()
            val state = loadState(request.txId) ?: RetryState(
                windowStartAtMillis = now,
                completedWindows = 0,
                nextAttemptStartsNewWindow = false,
                totalAttempts = 0
            )

            val elapsedInWindow = (now - state.windowStartAtMillis).coerceAtLeast(0L)
            val withinActiveWindow = elapsedInWindow < ACTIVE_RETRY_WINDOW_MS

            val delayMs = if (withinActiveWindow) {
                ACTIVE_RETRY_INTERVAL_MS
            } else if (state.completedWindows == 0) {
                FIRST_BACKOFF_MS
            } else {
                REPEATED_BACKOFF_MS
            }

            val nextState = state.copy(
                nextAttemptStartsNewWindow = !withinActiveWindow,
                totalAttempts = state.totalAttempts + 1
            )

            if (!scheduleAlarm(request, delayMs)) {
                // Fallback: save as failed
                clearState(request.txId)
                transactionHelper.saveAndBroadcast(request.txId, status, response, transcript)
                return
            }

            saveState(request.txId, nextState)
            val message = buildRetryMessage(response, withinActiveWindow, delayMs, nextState, elapsedInWindow)
            transactionHelper.saveAndBroadcast(request.txId, TransactionStatus.RETRYING.value, message, transcript)
        }

        fun scheduleRetryTomorrow(request: UssdRequest) {
            val tomorrow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 7)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            scheduleAlarm(request, tomorrow.timeInMillis - System.currentTimeMillis())
        }

        fun armRetryWindow(txId: Int) {
            if (txId < 0) return
            val state = loadState(txId) ?: return
            if (state.nextAttemptStartsNewWindow) {
                saveState(txId, state.copy(
                    windowStartAtMillis = System.currentTimeMillis(),
                    nextAttemptStartsNewWindow = false
                ))
            }
        }

        fun clearState(txId: Int) {
            if (txId < 0) return
            cancelAlarm(txId)
            prefs.edit()
                .remove("tx_${txId}_windowStart")
                .remove("tx_${txId}_completedWindows")
                .remove("tx_${txId}_nextWindow")
                .remove("tx_${txId}_attempts")
                .apply()
        }

        private fun loadState(txId: Int): RetryState? {
            if (txId < 0) return null
            if (!prefs.contains("tx_${txId}_windowStart")) return null
            return RetryState(
                windowStartAtMillis = prefs.getLong("tx_${txId}_windowStart", 0L),
                completedWindows = prefs.getInt("tx_${txId}_completedWindows", 0),
                nextAttemptStartsNewWindow = prefs.getBoolean("tx_${txId}_nextWindow", false),
                totalAttempts = prefs.getInt("tx_${txId}_attempts", 0)
            )
        }

        private fun saveState(txId: Int, state: RetryState) {
            prefs.edit()
                .putLong("tx_${txId}_windowStart", state.windowStartAtMillis)
                .putInt("tx_${txId}_completedWindows", state.completedWindows)
                .putBoolean("tx_${txId}_nextWindow", state.nextAttemptStartsNewWindow)
                .putInt("tx_${txId}_attempts", state.totalAttempts)
                .apply()
        }

        private fun scheduleAlarm(request: UssdRequest, delayMs: Long): Boolean {
            if (delayMs <= 0) return false
            val intent = buildAutomationIntent(context, request, ACTION_RETRY_RETRIABLE_RESPONSE)
            val pi = PendingIntent.getService(
                context,
                alarmRequestCode(request.txId, ACTION_RETRY_RETRIABLE_RESPONSE),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return AlarmCompat.scheduleRtcWakeup(
                context = context,
                triggerAtMillis = System.currentTimeMillis() + delayMs,
                pendingIntent = pi,
                preferExact = false,
                allowWhileIdle = true
            )
        }

        private fun cancelAlarm(txId: Int) {
            if (txId < 0) return
            runCatching {
                val intent = Intent(context, AutomationService::class.java).apply {
                    action = ACTION_RETRY_RETRIABLE_RESPONSE
                }
                val pi = PendingIntent.getService(
                    context,
                    alarmRequestCode(txId, ACTION_RETRY_RETRIABLE_RESPONSE),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
                pi.cancel()
            }
        }

        private fun alarmRequestCode(txId: Int, action: String?): Int {
            val base = if (txId >= 0) txId else 0
            val actionHash = (action?.hashCode() ?: 0) and 0x7FFF
            return (base and 0x7FFF) shl 16 or actionHash
        }

        private fun buildRetryMessage(
            response: String,
            withinActiveWindow: Boolean,
            delayMs: Long,
            state: RetryState,
            elapsedInWindow: Long
        ): String {
            val timing = if (withinActiveWindow) {
                val remaining = ((ACTIVE_RETRY_WINDOW_MS - elapsedInWindow).coerceAtLeast(0L) + 999L) / 1000L
                "Automatic retry still active. Next retry in ${formatDelay(delayMs)}. Remaining window: ${remaining}s."
            } else {
                val backoff = "10 minutes"
                "Retry window exhausted. Waiting $backoff before starting another 1‑minute retry window."
            }
            return "$response\n\n$timing"
        }

        private fun formatDelay(delayMs: Long): String = when {
            delayMs % 60_000L == 0L -> {
                val minutes = delayMs / 60_000L
                if (minutes == 1L) "1 minute" else "$minutes minutes"
            }
            delayMs % 1000L == 0L -> {
                val seconds = delayMs / 1000L
                if (seconds == 1L) "1 second" else "$seconds seconds"
            }
            else -> "${delayMs}ms"
        }
    }
    // endregion

    // region TransactionHelper – saves and broadcasts
    private inner class TransactionHelper(private val context: Context) {
        fun saveAndBroadcast(txId: Int, status: String, response: String, transcript: List<String>? = null) {
            if (txId < 0) return
            val saved = saveTransactionOutcome(context, txId, status, response, transcript?.joinToString("\n\n"))
            if (saved) {
                broadcastUpdate(txId, status, response)
            } else {
                Log.w(TAG, "Transaction $txId not found")
            }
        }

        fun finishWithError(request: UssdRequest, message: String) {
            refundTokenIfNeeded(request)
            if (request.txId >= 0) {
                saveAndBroadcast(request.txId, TransactionStatus.FAILED.value, message)
            }
            finishExecution(scheduleAirtimeRefresh = true)
        }

        fun refundTokenIfNeeded(request: UssdRequest) {
            if (request.txId < 0) return
            val tx = loadTransactionById(context, request.txId) ?: return
            if (tx.source != TX_SOURCE_AUTOMATED) return
            if (UnlimitedManager(context).isActive()) return
            val tokenMgr = TokenManager(context)
            val balance = tokenMgr.getBalance()
            if (balance >= 1) {
                tokenMgr.addTokens(1)
                Log.d(TAG, "Refunded 1 token for failed txId=${request.txId}")
            }
        }

        fun createFallbackTransaction(request: UssdRequest, offer: OfferItem, reason: String): Int {
            val originalTx = loadTransactionById(context, request.txId)
            return createPendingTransaction(
                context,
                offer.name,
                "KSh ${offer.price}",
                request.phoneNumber,
                UssdHelper.normalizeUssdCode(offer.ussdCode, request.phoneNumber),
                originalTx?.clientName.orEmpty(),
                status = if (originalTx?.showInRecent == true) TransactionStatus.PROCESSING.value else TransactionStatus.PENDING.value,
                source = originalTx?.source ?: TX_SOURCE_AUTOMATED,
                showInRecent = originalTx?.showInRecent ?: true,
                offerId = offer.id
            )
        }

        private fun broadcastUpdate(txId: Int, status: String, response: String) {
            Handler(Looper.getMainLooper()).post {
                context.sendBroadcast(
                    Intent("com.bingwa.mobile.TX_UPDATED")
                        .setPackage(context.packageName)
                        .putExtra("txId", txId)
                        .putExtra("status", status)
                        .putExtra("response", response)
                )
            }
        }
    }
    // endregion

    // region NotificationHelper – builds notifications
    private inner class NotificationHelper(private val context: Context) {
        private val whitespaceRegex = Regex("\\s+")

        fun notifyFallback(request: UssdRequest, response: String) {
            val title = "Fallback Dispatched"
            val message = "${request.offerName.ifBlank { "Original offer" }} stopped. ${request.phoneNumber} will be sent via fallback."
            OfferNotifications.notify(context, title, message)
        }

        fun notifyPending(request: UssdRequest) {
            val title = "Daily Limit Notice"
            val message = "${request.phoneNumber} has already received today's offer. A notice was sent instead of auto-queueing."
            OfferNotifications.notify(context, title, message)
        }

        fun notifyLearningNoOffer(request: UssdRequest) {
            OfferNotifications.notify(
                context,
                "USSD Signature Learning",
                "USSD signature learning finished, but the offer could not be identified for saving."
            )
        }

        fun notifyLearningFailed(request: UssdRequest) {
            OfferNotifications.notify(
                context,
                "USSD Signature Learning",
                "The system could not learn a signature for ${request.offerName.ifBlank { "offer #${request.offerId}" }}. Open the offer and run Save & Learn again while the USSD menu is available."
            )
        }

        fun notifyLearningSuccess(request: UssdRequest, result: AdvancedDispatchResult) {
            val offerLabel = request.offerName.ifBlank { "offer #${request.offerId}" }
            val learningPhone = request.phoneNumber.ifBlank { "the provided test number" }
            val captureSummary = if (result.learningCaptures.isNotEmpty()) {
                " Captured ${result.learningCaptures.size} USSD popup(s), the selected option, and the recorded text for each step."
            } else ""
            val finalPopup = result.learningCaptures.lastOrNull()?.popupText
                ?.replace(whitespaceRegex, " ")
                ?.trim()
                ?.take(120)
                ?.takeIf { it.isNotBlank() }
                ?.let { " Last popup: $it" }
                .orEmpty()
            val learnedSummary = if (result.learnedSignature.isNotEmpty()) {
                "The system learned ${result.learnedSignature.size} USSD menu step(s) for $offerLabel using $learningPhone."
            } else {
                "The system recorded the USSD learning transcript for $offerLabel using $learningPhone."
            }
            val message = "$learnedSummary$captureSummary$finalPopup Review the learned steps in Bingwa Mobile, then approve or relearn before this signature replaces the saved one."
            OfferNotifications.notify(context, "USSD Signature Ready For Approval", message)
        }
    }
    // endregion

    private fun sendScheduledDispatchNotification(request: UssdRequest) {
        runCatching {
            val tx = TransactionStore.load(this).firstOrNull { it.id == request.txId } ?: return@runCatching
            val reason = runCatching {
                val transcript = tx.ussdTranscript.orEmpty().split("\n")
                val limitLine = transcript.firstOrNull { it.contains("already received", ignoreCase = true) || it.contains("daily limit", ignoreCase = true) || it.contains("twice", ignoreCase = true) }
                val bingwaLine = transcript.firstOrNull { it.contains("Bingwa Sokoni", ignoreCase = true) }
                when {
                    bingwaLine != null -> "It was not sent yesterday because $bingwaLine"
                    limitLine != null -> "It was not sent yesterday because $limitLine"
                    else -> "It was scheduled for today because the previous attempt could not be completed yesterday."
                }
            }.getOrElse { "It was scheduled for today because the previous attempt could not be completed yesterday." }
            val updatedNote = "${tx.description ?: "your bundle"}. $reason"
            transactionHelper.saveAndBroadcast(request.txId, TransactionStatus.SUCCESS.value, updatedNote, tx.ussdTranscript.split("\n"))
            sendCustomerOutcomeSms(this, "scheduled", tx)
        }
    }

    // region ForegroundServiceHelper – manages foreground state
    private inner class ForegroundServiceHelper(private val service: Service) {
        private val channelId = "automation_service"
        private val notificationId = 2014

        fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = service.getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(channelId, "USSD Automation", NotificationManager.IMPORTANCE_LOW)
                manager.createNotificationChannel(channel)
            }
        }

        fun startForeground(): Boolean {
            val notification = NotificationCompat.Builder(service, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Bingwa Mobile")
                .setContentText("Running a USSD automation task")
                .setContentIntent(
                    PendingIntent.getActivity(
                        service,
                        0,
                        Intent(service, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setOngoing(true)
                .build()

            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    service.startForeground(notificationId, notification, ForegroundServiceTypes.combinedPhoneCall)
                } else {
                    service.startForeground(notificationId, notification)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground", e)
                false
            }
        }

        @Suppress("DEPRECATION")
        fun stopForeground() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                service.stopForeground(true)
            }
        }
    }
    // endregion

    // region SimResolver – centralized SIM selection
    private inner class SimResolver(private val context: Context) {
        fun getTargets(selectionOverride: Int): List<UssdSimTarget> {
            val override = selectionOverride.takeUnless { it == OFFER_SIM_USE_GENERAL }
            return resolveUssdSimTargets(context, override)
        }
    }
    // endregion

    // region Utilities (kept as top-level functions for simplicity)
    private fun buildAutomationIntent(context: Context, request: UssdRequest, action: String? = null): Intent =
        Intent(context, AutomationService::class.java).apply {
            this.action = action
            putExtra("mode", request.mode)
            putExtra("code", request.code)
            putExtra("phoneNumber", request.phoneNumber)
            putExtra("txId", request.txId)
            putExtra("offerId", request.offerId)
            putExtra("offerName", request.offerName)
            putExtra("simSelection", request.simSelection)
            putExtra("signatureEnabled", request.signatureEnabled)
            putExtra("signatureMode", request.signatureMode)
            putExtra("signatureLearning", request.signatureLearning)
            putExtra("executionPriority", request.executionPriority)
            putExtra("returnToAppAggressively", request.returnToAppAggressively)
        }

    private fun startOfferAutomation(
        offer: OfferItem,
        phoneNumber: String,
        txId: Int,
        finalCode: String,
        mode: String
    ) {
        // IMPORTANT: OfferItem uses signatureDetectionEnabled and signatureAction, not signatureEnabled/signatureMode.
        val intent = Intent(this, AutomationService::class.java).apply {
            putExtra("mode", mode)
            putExtra("code", finalCode)
            putExtra("phoneNumber", phoneNumber)
            putExtra("txId", txId)
            putExtra("offerId", offer.id)
            putExtra("offerName", offer.name)
            putExtra("simSelection", offer.simSelection)
            putExtra("signatureEnabled", offer.signatureDetectionEnabled)  // fixed
            putExtra("signatureMode", offer.signatureAction)               // fixed
            putExtra("signatureLearning", false)
            putExtra("executionPriority", USSD_EXECUTION_PRIORITY_NORMAL)
            putExtra("returnToAppAggressively", true)
        }
        startService(intent)
    }
    // endregion
}
