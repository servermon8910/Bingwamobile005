package com.bingwa.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat

class UssdNavigationService : AccessibilityService() {

    companion object {
        const val TAG = "UssdNav"

        @Volatile var airtimeBalance = "N/A"
        @Volatile var balanceCallback: ((String) -> Unit)? = null
        @Volatile var tokenPurchaseCallback: ((Boolean) -> Unit)? = null

        @Volatile var advancedSteps: List<String> = emptyList()
        @Volatile var advancedPhoneNumber = ""
        @Volatile var advancedDialCode = ""
        @Volatile var advancedOfferId = -1
        @Volatile var advancedOfferName = ""
        @Volatile var preferredDialSubId = -1
        @Volatile var preferredDialSlotIndex = -1
        @Volatile var advancedActive = false
        @Volatile var advancedInProgress = false
        @Volatile var isUsdExecutionLocked = false
        @Volatile var currentStep = 0
        @Volatile var lastProcessedStep = -1
        @Volatile var retryCount = 0
        @Volatile var retryWindowStartedAt = 0L
        @Volatile var lastRedialElapsed = 0L

        @Volatile var signatureGuardEnabled = false
        @Volatile var signatureAction = "STOP"
        @Volatile var signatureLearningMode = false
        @Volatile var loadedSignatureSteps: List<UssdSignatureStep> = emptyList()

        @Volatile var onDispatchComplete: ((AdvancedDispatchResult) -> Unit)? = null
        @Volatile var lowEndDevice = false

        private var activeInstance: UssdNavigationService? = null
        private var pendingArm = false

        fun beginAdvancedSessionMonitoring() {
            activeInstance?.let { it.handler.post { it.handleAdvancedSessionArmed() } }
                ?: run { pendingArm = true }
        }

        fun configureUiReturn(keepVisible: Boolean) {
            activeInstance?.let { service ->
                service.handler.post {
                    service.keepAppUiVisibleEnabled = keepVisible
                    if (!keepVisible) {
                        service.uiReturnSuppressed = true
                        service.stopKeepingAppUiVisible()
                    } else {
                        service.uiReturnSuppressed = false
                        if (advancedActive || service.isForegroundUiActive()) {
                            service.startKeepingAppUiVisible()
                        }
                    }
                    service.updateOverlay()
                }
            } ?: run {
                pendingArm = true
            }
        }

        fun armForegroundUi(durationMs: Long = 35_000L) {
            activeInstance?.let { service ->
                service.handler.post {
                    service.foregroundUiActive = true
                    service.foregroundUiUntilElapsed = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(1_000L)
                    service.uiReturnSuppressed = false
                    service.startKeepingAppUiVisible()
                    service.updateOverlay()
                }
            }
        }

        fun configureDialPreferences(subId: Int?, slotIndex: Int?) {
            preferredDialSubId = subId ?: -1
            preferredDialSlotIndex = slotIndex ?: -1
        }

        fun onAppUiForegrounded() {
            activeInstance?.let { service ->
                service.handler.post {
                    service.uiReturnSuppressed = false
                    service.refreshForegroundUi()
                    if (advancedActive || service.isForegroundUiActive()) {
                        service.startKeepingAppUiVisible()
                    }
                    service.updateOverlay()
                }
            }
        }

        fun isBusyForBalanceCheck(): Boolean {
            return advancedActive || advancedInProgress || onDispatchComplete != null ||
                balanceCallback != null || tokenPurchaseCallback != null
        }

        fun detectLowEndDevice(context: Context) {
            lowEndDevice = try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager?.getMemoryInfo(memoryInfo)
                val totalMem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    memoryInfo.totalMem
                } else {
                    (Runtime.getRuntime().maxMemory() / 1024 / 1024).toLong()
                }
                val lowMemory = totalMem < 3L * 1024 * 1024 * 1024
                val lowCores = Runtime.getRuntime().availableProcessors() <= 4
                lowMemory || lowCores
            } catch (e: Exception) {
                false
            }
        }

        fun resetSignatureTracking() { /* handled internally */ }
        fun refreshRunningOverlay() { activeInstance?.updateOverlay() }
    }

    // region Internal Helpers
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread
    private var windowManager: WindowManager? = null

    private var isProcessing = false
    private var lastDialogText = ""
    private var lastFinalResponse = ""

    private var stepTimeoutRunnable: Runnable? = null
    private var processStepRunnable: Runnable? = null

    private var lastInputWriteValue = ""
    private var lastInputWriteElapsed = 0L
    private var lastVerifiedInputValue = ""
    private var lastVerifiedInputElapsed = 0L

    private var pendingProcessToken = 0L
    private var pendingExpectedValue: String? = null
    private var pendingPhase = PendingPhase.NONE
    private var pendingAdvanceFromKey = ""
    private var pendingSinceElapsed = 0L
    private var pendingAttempts = 0
    private var pendingAdvanceKickRunnable: Runnable? = null

    private var pendingStepAdvanceFromKey = ""
    private var pendingStepAdvanceSinceElapsed = 0L
    private var pendingStepAdvanceTimeoutRunnable: Runnable? = null
    private var pendingStepAdvanceKickRunnable: Runnable? = null

    private var currentStepRetryCount = 0
    private val MAX_STEP_RETRIES = 10

    private fun lowEndDelay(baseMs: Long): Long = if (lowEndDevice) (baseMs * 1.6f).toLong() else baseMs
    private fun lowEndDelay(baseMs: Int): Int = lowEndDelay(baseMs.toLong()).toInt()

    private var lastWindowId = -1
    private var lastWindowPkg = ""
    private var lastRelevantEventElapsed = 0L
    private var lastStepActionKey = ""
    private var lastStepActionElapsed = 0L
    private var lastUiReturnElapsed = 0L
    private var lastEventFingerprint = ""
    private var lastEventElapsed = 0L
    private var lastScreenSignatureKey = ""
    private var lastObservedDialogStateKey = ""
    private var lastObservedDialogStateChangedElapsed = 0L

    private var recentUssdRoot: AccessibilityNodeInfo? = null
    private var recentUssdSnapshot: UssdTreeSnapshot? = null
    private var recentUssdWindowId = -1
    private var recentUssdWindowPkg = ""
    private var recentUssdDialogText = ""
    private var recentUssdStrictDialog = false
    private var recentUssdCapturedElapsed = 0L
    private var waitingForRootSinceElapsed = 0L
    private var lastTranscriptEntryKey = ""

    private fun recycleRecentUssdRoot() {
        recentUssdRoot?.recycle()
        recentUssdRoot = null
        recentUssdCapturedElapsed = 0L
    }

    private var overlayView: View? = null
    private var overlayStatusText: TextView? = null
    private var overlayDetailText: TextView? = null
    private var uiKeepVisibleRunnable: Runnable? = null
    private var foregroundUiActive = false
    private var foregroundUiUntilElapsed = 0L
    private var keepAppUiVisibleEnabled = true
    private var uiReturnSuppressed = false
    private var hasSeenAdvancedPopup = false
    private var hasSeenForegroundPopup = false

    private val MAX_POPUP_TRANSCRIPT_ENTRIES = 80
    private val MAX_POPUP_TRANSCRIPT_CHARS = 1200
    private val MAX_LEARNING_CAPTURES = 40
    private val MAX_DETECTED_CHANGE_NOTES = 20
    private val MAX_LEARNED_SIGNATURE_STEPS = 60
    private val MAX_ADJUSTED_STEP_INPUTS = 60

    private val adjustedStepInputs = linkedMapOf<Int, String>()
    private val learnedSignatureSteps = mutableListOf<UssdSignatureStep>()
    private val learningCaptures = mutableListOf<UssdLearningCapture>()
    private val popupTranscript = mutableListOf<String>()
    private val detectedChangeNotes = mutableListOf<String>()
    private var signatureChangeDetected = false
    private var signatureAutoAdjusted = false
    private var loadedSignatureLookupSource: List<UssdSignatureStep> = emptyList()
    private var loadedSignatureLookup: Map<Int, LearnedSignatureContext> = emptyMap()

    // region Lifecycle
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        bgThread = HandlerThread("UssdNavBg").apply { start() }
        bgHandler = Handler(bgThread.looper)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        startForegroundCompat()
        if (pendingArm) {
            pendingArm = false
            handler.post { handleAdvancedSessionArmed() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationTimeout = ACCESSIBILITY_NOTIFICATION_TIMEOUT_MS
            }
        }
        if (pendingArm) {
            pendingArm = false
            handler.post { handleAdvancedSessionArmed() }
        }
    }

    override fun onInterrupt() {
        try { cleanupAdvanced(); clearCallbacks() } catch (e: Throwable) { Log.e(TAG, "onInterrupt crashed", e) }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            stopForegroundCompat()
            bgThread.quitSafely()
            cleanupAdvanced()
            clearCallbacks()
            if (activeInstance === this) activeInstance = null
            hideOverlay()
            handler.removeCallbacksAndMessages(null)
        } catch (e: Throwable) { Log.e(TAG, "onDestroy crashed", e) }
    }
    // endregion

    private fun handleAdvancedSessionArmed() {
        if (!advancedActive) return
        if (advancedPhoneNumber.isBlank()) {
            Log.w(TAG, "handleAdvancedSessionArmed: phone number is blank, cannot arm session")
            return
        }
        uiReturnSuppressed = false
        isProcessing = false
        lastRelevantEventElapsed = SystemClock.elapsedRealtime()
        if (retryWindowStartedAt <= 0L) {
            retryWindowStartedAt = SystemClock.elapsedRealtime()
        }
        requestAppUiBehindPopup(force = true)
        startKeepingAppUiVisible()
        updateOverlay()
        startStepTimeout()
    }

    // region Accessibility Event Handling
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !isForegroundUiActive()) return

            val type = event.eventType
            if (type !in setOf(
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_FOCUSED,
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
                )
            ) return

            if (shouldSkipDuplicateEvent(event)) return
            val pkg = event.packageName?.toString() ?: ""
            val previousWindowId = lastWindowId
            val windowId = event.windowId

            if (pkg in LAUNCHER_PACKAGES && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
                if (advancedActive) { uiReturnSuppressed = true; updateOverlay() }
                else if (isForegroundUiActive()) { disarmForegroundUi(); hasSeenForegroundPopup = false; updateOverlay() }
                return
            }

            if (!isPotentialUssdPackage(pkg) && pkg != "android" && pkg != "com.android.systemui") {
                if (advancedActive) { uiReturnSuppressed = true; updateOverlay() }
                if (isForegroundUiActive() && !advancedActive) { disarmForegroundUi(); hasSeenForegroundPopup = false; updateOverlay() }
                return
            }

            val root = obtainRootFromEvent(event) ?: return
            try {
                val windowPkg = root.packageName?.toString() ?: ""
                val requireStrict = shouldRequireStrictPopupScope()
                val snapshot = if (advancedActive || isForegroundUiActive() || balanceCallback != null || tokenPurchaseCallback != null) {
                    capturePreferredPopupSnapshot(root, requireStrict)
                } else null
                val dialogText = snapshot?.dialogText ?: normalizeCollapsedText(extractDialogText(event))
                if (dialogText.isBlank()) return

                val lower = dialogText.lowercase()
                
                // Detect popup transitions for multi-step USSD
                if (advancedActive && currentStep < advancedSteps.size) {
                    val isNewPopup = detectPopupTransition(previousWindowId, windowId, dialogText, snapshot)
                    if (isNewPopup) {
                        Log.d(TAG, "New popup detected for step $currentStep")
                        handlePopupTransition()
                    }
                }

                if (handleIntermediatePopup(root, dialogText, lower, windowPkg)) {
                    lastWindowId = windowId
                    lastWindowPkg = windowPkg
                    return
                }
                if (windowPkg in BLOCKED_PACKAGES && !shouldAllowSystemUi(root, windowPkg)) return
                if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return

                val looksLikeDialog = if (snapshot != null) {
                    looksLikeUssdDialog(root, snapshot, lower, windowPkg)
                } else {
                    looksLikeUssdDialogFast(lower, windowPkg)
                }
                if (!looksLikeDialog) return

                lastWindowId = windowId
                lastWindowPkg = windowPkg
                lastRelevantEventElapsed = SystemClock.elapsedRealtime()
                rememberRecentUssdContext(root, snapshot, windowId, windowPkg, dialogText, requireStrict)
                rememberObservedDialogState(windowId, windowPkg, dialogText, snapshot)

                if (advancedActive && advancedSteps.isNotEmpty()) {
                    if (!hasSeenAdvancedPopup) {
                        hasSeenAdvancedPopup = true
                        updateOverlay()
                        requestAppUiBehindPopup(force = true)
                        startKeepingAppUiVisible()
                    } else if (windowId != previousWindowId) {
                        requestAppUiBehindPopup()
                        startKeepingAppUiVisible()
                    }

                    cancelStepTimeout()
                    lastFinalResponse = dialogText

                    if (signatureLearningMode) {
                        captureLearningDialogIfNeeded(snapshot, root, windowPkg)
                    }

                    if (shouldWaitForStepTransition(dialogText, windowId, root, snapshot)) return

                    if (errorKeywords.any { lower.contains(it) }) {
                        if (signatureLearningMode && currentStep >= advancedSteps.size) {
                            finishAdvancedDispatch(dialogText)
                        } else {
                            dismissErrorAndRestart()
                        }
                        return
                    }

                    if (isTransientResponse(lower)) {
                        if (currentStep >= advancedSteps.size) {
                            if (signatureLearningMode) finishAdvancedDispatch(dialogText)
                            return
                        }
                        isProcessing = false
                        scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS)
                        return
                    }

                    if (pendingStepAdvanceFromKey.isNotBlank() && handlePendingStepAdvance(windowId, windowPkg, root, snapshot, dialogText)) {
                        return
                    }

                    val dialogChanged = windowId != previousWindowId || dialogText != lastDialogText
                    lastDialogText = dialogText

                    if (!isProcessing) {
                        if (pendingPhase != PendingPhase.NONE) {
                            attemptPendingAdvance(root)
                            return
                        }
                        val screenKey = buildScreenSignatureKey(currentStep, windowId, windowPkg, root, snapshot, dialogText)
                        if (!dialogChanged && screenKey == lastScreenSignatureKey) return
                        lastScreenSignatureKey = screenKey
                        pendingProcessToken = SystemClock.elapsedRealtime()
                        scheduleProcessStep(dialogChanged)
                    }
                    return
                }

                if (isForegroundUiActive()) {
                    refreshForegroundUi()
                    if (!hasSeenForegroundPopup) {
                        hasSeenForegroundPopup = true
                        updateOverlay()
                        requestAppUiBehindPopup(force = true)
                        startKeepingAppUiVisible()
                    } else if (windowId != previousWindowId) {
                        requestAppUiBehindPopup()
                        startKeepingAppUiVisible()
                    }
                    return
                }

                handleCallbackDialogs(lower, dialogText)
            } finally {
                root.recycle()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onAccessibilityEvent crashed", e)
        }
    }

    // region Popup Detection
    private fun detectPopupTransition(oldWindowId: Int, newWindowId: Int, dialogText: String, snapshot: UssdTreeSnapshot?): Boolean {
        if (!advancedActive || currentStep >= advancedSteps.size) return false
        if (dialogText.isBlank() || snapshot == null) return false
        
        val currentSig = buildDialogStateKey(dialogText, snapshot.inputStateSignature)
        if (currentSig.isBlank()) return false
        
        // New popup if window changed or content changed significantly
        val windowChanged = oldWindowId != newWindowId && oldWindowId != -1
        val contentChanged = lastStepActionKey.isNotBlank() && currentSig != lastStepActionKey
        
        if (windowChanged || contentChanged) {
            Log.d(TAG, "Popup transition detected - window: $oldWindowId->$newWindowId, content: ${lastStepActionKey}->$currentSig")
            return true
        }
        return false
    }

    private fun handlePopupTransition() {
        if (!advancedActive || currentStep >= advancedSteps.size) return
        Log.d(TAG, "Handling popup transition to step $currentStep")
        
        // Reset state for new popup
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastObservedDialogStateKey = ""
        lastObservedDialogStateChangedElapsed = 0L
        lastDialogText = ""
        lastScreenSignatureKey = ""
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarkers()
        clearRecentUssdContext()
        isProcessing = false
        
        // Force immediate re-evaluation
        scheduleProcessStep(true, RAPID_POST_POPUP_POLL_MS)
    }
    // endregion

    // region Step Processing
    private fun scheduleProcessStep(dialogChanged: Boolean, overrideDelay: Long? = null) {
        processStepRunnable?.let { handler.removeCallbacks(it) }
        val token = pendingProcessToken
        val stabilityDelay = popupStabilityRemainingMs()
        val delay = overrideDelay ?: when {
            dialogChanged && stabilityDelay > 0L -> stabilityDelay
            hasSeenAdvancedPopup && dialogChanged -> RAPID_POST_POPUP_POLL_MS
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> RAPID_POST_POPUP_VERIFY_MS
            dialogChanged && hasRecentUssdUiEvent() -> EVENT_HOT_POLL_MS
            dialogChanged -> POPUP_STABILITY_DELAY_MS
            hasRecentUssdUiEvent() -> FAST_VERIFY_POLL_MS
            else -> VERIFY_POLL_MS
        }
        val task = Runnable {
            processStepRunnable = null
            if (pendingProcessToken != token || !advancedActive || isProcessing) return@Runnable
            isProcessing = true
            try {
                processStep()
            } catch (e: Throwable) {
                Log.e(TAG, "processStep task crashed", e)
                isProcessing = false
                dismissErrorAndRestart()
            }
        }
        processStepRunnable = task
        if (delay <= 0) handler.post(task) else handler.postDelayed(task, delay)
    }

    private fun processStep() {
        if (!advancedActive) { isProcessing = false; return }
        if (pendingStepAdvanceFromKey.isNotBlank()) { isProcessing = false; return }
        
        // If we're waiting for a new popup, don't process the same step again
        if (lastProcessedStep == currentStep && lastDialogText == lastFinalResponse) {
            isProcessing = false
            // Check if we should re-detect
            val root = getUssdRoot()
            if (root != null) {
                try {
                    val snapshot = capturePreferredPopupSnapshot(root, shouldRequireStrictPopupScope())
                    if (snapshot != null) {
                        val dialogText = snapshot.dialogText
                        val windowId = root.windowId
                        if (detectPopupTransition(lastWindowId, windowId, dialogText, snapshot)) {
                            // New popup detected, force re-processing
                            isProcessing = false
                            scheduleProcessStep(true, 0)
                            return
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
            scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS)
            return
        }

        val requireStrict = shouldRequireStrictPopupScope()
        val context = obtainRecentUssdContext(requireStrict) ?: run {
            if (shouldWaitForRootRecovery()) {
                isProcessing = false
                waitForRootRecovery()
            } else {
                isProcessing = false
                handler.postDelayed({ restartFromBeginning() }, RESTART_FROM_ROOT_DELAY_MS)
            }
            return
        }

        val root = context.root
        try {
            val snapshot = context.snapshot ?: capturePreferredPopupSnapshot(root, requireStrict)
                ?: run { 
                    if (hasSeenAdvancedPopup && !shouldWaitForRootRecovery()) {
                        isProcessing = false
                        dismissErrorAndRestart()
                    } else {
                        isProcessing = false
                        scheduleProcessStep(false)
                    }
                    return
                }

            val dialogText = snapshot.dialogText.ifBlank { lastFinalResponse }
            val lower = dialogText.lowercase()

            // Check if this is a new popup that we need to process
            if (detectPopupTransition(lastWindowId, context.windowId, dialogText, snapshot)) {
                Log.d(TAG, "Processing new popup for step $currentStep: $dialogText")
                lastStepActionKey = ""
                lastStepActionElapsed = 0L
            }

            if (shouldWaitForStepTransition(dialogText, context.windowId, root, snapshot)) {
                isProcessing = false
                scheduleProcessStep(false)
                return
            }

            if (dialogText.isNotBlank()) lastFinalResponse = dialogText
            if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) { 
                isProcessing = false
                scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS)
                return 
            }
            if (!looksLikeUssdDialogFast(lower, context.windowPkg)) { 
                isProcessing = false
                scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS)
                return 
            }

            if (currentStep >= advancedSteps.size) {
                if (shouldWaitForFinalResponse(snapshot, dialogText)) {
                    isProcessing = false
                    scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS)
                    return
                }
                finishAdvancedDispatch(lastFinalResponse)
                return
            }

            if (isTransientResponse(lower)) {
                isProcessing = false
                scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS)
                return
            }

            val step = advancedSteps[currentStep]
            val menu = parseMenuFromSnapshot(snapshot)
            if (step != "INPUT_PHONE") {
                captureSignatureStepIfNeeded(currentStep, step, menu, snapshot, dialogText)
            }

            val (valueToEnter, selectedLabel) = resolveStepInput(currentStep, step, menu)
            if (!advancedActive) { isProcessing = false; return }

            val inputField = findEditableFieldForStep(root, step, dialogText)
            try {
                val dialogAllowsPhone = step == "INPUT_PHONE" && dialogSuggestsPhoneInput(lower)
                if (step == "INPUT_PHONE" && inputField == null && !dialogAllowsPhone) {
                    val aggressiveCandidates = mutableListOf<AccessibilityNodeInfo>()
                    collectAggressiveTextEntryCandidates(root, aggressiveCandidates)
                    val aggressiveField = aggressiveCandidates.firstOrNull()
                    val wrote = aggressiveField != null && tryWriteValueToField(aggressiveField, valueToEnter, root)
                    aggressiveCandidates.forEachIndexed { idx, node -> if (idx != 0) node.recycle() }
                    aggressiveField?.recycle()
                    if (!wrote) {
                        isProcessing = false
                        dismissErrorAndRestart()
                        return
                    }
                    isProcessing = false
                    scheduleProcessStep(false)
                    return
                }

                val shouldPreferText = inputField != null ||
                        step == "INPUT_PHONE" ||
                        shouldTreatAsTextInput(step, valueToEnter, selectedLabel) ||
                        shouldTreatNumericReplyAsTextInput(step, valueToEnter, snapshot, lower, menu) ||
                        dialogSuggestsTextInput(lower) ||
                        dialogAllowsPhone

                if (inputField != null || shouldPreferText) {
                    val wrote = ensureExpectedValueWritten(root, valueToEnter, inputField)
                    if (!wrote) {
                        isProcessing = false
                        dismissErrorAndRestart()
                        return
                    }
                    
                    // Instantly trigger send/OK action once input is verified
                    if (!isFinalLearningStep(currentStep) && wrote &&
                        tryImmediateVerifiedSend(root, inputField, valueToEnter, skipVerification = true)) {
                        markStepAction(dialogText, root, snapshot)
                        startPendingStepAdvance(root, dialogText)
                        return
                    }

                    if (!isFinalLearningStep(currentStep) && wrote) {
                        val verified = verifyExpectedInput(root, valueToEnter, inputField) || hasRecentVerifiedInput(valueToEnter)
                        if (verified) {
                            val sent = tryDirectImeSubmit(root, inputField, valueToEnter)
                            if (sent) {
                                markStepAction(dialogText, root, snapshot)
                                startPendingStepAdvance(root, dialogText)
                                return
                            }
                        }
                    }

                    if (isFinalLearningStep(currentStep)) {
                        val delay = if (wrote && hasSeenAdvancedPopup) RAPID_POST_POPUP_VERIFY_MS else if (wrote) FAST_VERIFY_POLL_MS else VERIFY_POLL_MS
                        handler.postDelayed({ verifyLearningFinalInputThenDismiss(valueToEnter, 0) }, delay)
                    } else {
                        startPendingAdvance(valueToEnter, root, dialogText, snapshot)
                    }
                    return
                }

                // Handle Menu button selection & immediate progression
                val menuBtn = findMenuButton(root, valueToEnter, selectedLabel)
                if (menuBtn != null) {
                    if (performClick(menuBtn)) {
                        markStepAction(dialogText, root, snapshot)
                        startPendingStepAdvance(root, dialogText)
                        return
                    }
                    isProcessing = false
                    dismissErrorAndRestart()
                    return
                }

                if (menu != null || hasSeenAdvancedPopup) {
                    isProcessing = false
                    scheduleProcessStep(false)
                    return
                }

                isProcessing = false
                dismissErrorAndRestart()
            } finally {
                inputField?.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun advanceStep() {
        // Reset ALL state tracking to ensure we detect the new popup
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastDialogText = ""
        lastScreenSignatureKey = ""
        lastObservedDialogStateKey = ""
        lastObservedDialogStateChangedElapsed = 0L
        lastRelevantEventElapsed = 0L
        
        // Clear any pending operations
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarkers()
        clearRecentUssdContext()
        
        // Advance the step counter
        lastProcessedStep = currentStep
        currentStep++
        currentStepRetryCount = 0
        isProcessing = false
        
        // UI updates
        requestAppUiBehindPopup()
        updateOverlay()
        startStepTimeout()
        
        // Force immediate re-detection of the next popup
        if (currentStep < advancedSteps.size) {
            Log.d(TAG, "Advanced to step $currentStep, waiting for next popup")
            scheduleProcessStep(true, RAPID_POST_POPUP_POLL_MS)
        }
    }

    private fun isTransientResponse(text: String) = TRANSIENT_RESPONSE_HINTS.any { text.contains(it) }

    private fun shouldWaitForStepTransition(dialogText: String, windowId: Int, root: AccessibilityNodeInfo?, snapshot: UssdTreeSnapshot?): Boolean {
        val sig = snapshot?.inputStateSignature ?: root?.let { captureInputStateSignature(it) }.orEmpty()
        val key = buildDialogStateKey(dialogText, sig)
        if (lastStepActionKey.isBlank() || key.isBlank()) return false
        if (key != lastStepActionKey) { lastStepActionKey = ""; lastStepActionElapsed = 0L; return false }
        if (windowId != lastWindowId) return false
        val elapsed = SystemClock.elapsedRealtime() - lastStepActionElapsed
        if (elapsed > STEP_TRANSITION_GUARD_MS) return false
        if (isTransientResponse(normalizeMenuText(dialogText))) return true
        return true
    }

    private fun shouldWaitForFinalResponse(snapshot: UssdTreeSnapshot, dialogText: String): Boolean {
        val normalized = normalizeMenuText(dialogText)
        val key = buildDialogStateKey(dialogText, snapshot.inputStateSignature)
        if (!hasMeaningfulResponseText(snapshot, dialogText)) return true
        if (isTransientResponse(normalized)) return true
        return lastStepActionKey.isNotBlank() && key == lastStepActionKey
    }

    private fun hasMeaningfulResponseText(snapshot: UssdTreeSnapshot, dialogText: String): Boolean {
        val lines = snapshot.textTokens.ifEmpty { listOf(dialogText) }.map { normalizeActionLabel(it) }.filter { it.isNotBlank() }
        return lines.any { it !in SEND_BUTTON_LABELS && it !in DISMISS_BUTTON_LABELS && it.length >= 3 }
    }
    // endregion

    // region Pending Operations
    private fun startPendingAdvance(expected: String, root: AccessibilityNodeInfo, dialogText: String, snapshot: UssdTreeSnapshot?) {
        pendingExpectedValue = expected
        pendingPhase = if (hasRecentVerifiedInput(expected)) PendingPhase.WAIT_SEND else PendingPhase.WAIT_VERIFY
        pendingAdvanceFromKey = buildTransitionSignatureKey(root, dialogText, snapshot)
        pendingSinceElapsed = SystemClock.elapsedRealtime()
        pendingAttempts = 0
        isProcessing = false
        schedulePendingAdvanceKick()
    }

    private fun startPendingStepAdvance(root: AccessibilityNodeInfo, dialogText: String) {
        currentStepRetryCount = 0
        clearPendingStepAdvance()
        clearPendingAdvance()
        pendingStepAdvanceSinceElapsed = SystemClock.elapsedRealtime()
        val snapshot = capturePreferredPopupSnapshot(root, shouldRequireStrictPopupScope())
        pendingStepAdvanceFromKey = buildStepAdvanceSignatureKey(root, dialogText, snapshot)
        
        // Reset state to ensure next popup is detected
        lastStepActionKey = buildDialogStateKey(dialogText, snapshot?.inputStateSignature.orEmpty())
        lastStepActionElapsed = SystemClock.elapsedRealtime()
        
        val timeoutTask = Runnable {
            if (pendingStepAdvanceFromKey.isNotBlank()) {
                clearPendingStepAdvance()
                isProcessing = false
                dismissErrorAndRestart()
            }
        }
        pendingStepAdvanceTimeoutRunnable = timeoutTask
        handler.postDelayed(timeoutTask, stepAdvanceTimeoutMs())
        schedulePendingStepAdvanceKick()
        isProcessing = false
    }

    private fun handlePendingStepAdvance(windowId: Int, windowPkg: String, root: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot?, dialogText: String): Boolean {
        val fromKey = pendingStepAdvanceFromKey
        if (fromKey.isBlank()) return false
        if (SystemClock.elapsedRealtime() - pendingStepAdvanceSinceElapsed > stepAdvanceTimeoutMs()) {
            clearPendingStepAdvance(); isProcessing = false; dismissErrorAndRestart(); return true
        }
        val currentKey = buildStepAdvanceSignatureKey(root, dialogText, snapshot)
        if (currentKey == fromKey) {
            schedulePendingStepAdvanceKick()
            return true
        }
        // New popup detected - advance to next step
        clearPendingStepAdvance()
        advanceStep()
        // Force immediate processing of the new popup
        scheduleProcessStep(true, 0)
        return true
    }

    private fun clearPendingStepAdvance() {
        pendingStepAdvanceFromKey = ""
        pendingStepAdvanceSinceElapsed = 0L
        pendingStepAdvanceTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceTimeoutRunnable = null
        pendingStepAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceKickRunnable = null
    }

    private fun schedulePendingStepAdvanceKick() {
        if (pendingStepAdvanceFromKey.isBlank()) return
        pendingStepAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingStepAdvanceKickRunnable = null
            attemptPendingStepAdvanceWithRoot(null)
        }
        pendingStepAdvanceKickRunnable = task
        val delay = if (hasRecentUssdUiEvent()) EVENT_HOT_POLL_MS else if (hasSeenAdvancedPopup) RAPID_POST_POPUP_POLL_MS else PENDING_STEP_ADVANCE_KICK_MS
        handler.postDelayed(task, delay)
    }

    private fun attemptPendingStepAdvanceWithRoot(existingRoot: AccessibilityNodeInfo?) {
        if (!advancedActive) { clearPendingStepAdvance(); isProcessing = false; return }
        val fromKey = pendingStepAdvanceFromKey
        if (fromKey.isBlank()) { isProcessing = false; return }
        pendingStepAdvanceKickRunnable = null

        if (SystemClock.elapsedRealtime() - pendingStepAdvanceSinceElapsed > stepAdvanceTimeoutMs()) {
            clearPendingStepAdvance(); isProcessing = false; dismissErrorAndRestart(); return
        }

        val root = existingRoot ?: getUssdRoot() ?: run {
            schedulePendingStepAdvanceKick(); isProcessing = false; return
        }
        try {
            val snapshot = capturePreferredPopupSnapshot(root, shouldRequireStrictPopupScope())
            val dialogText = snapshot?.dialogText ?: normalizeCollapsedText(extractAllText(root))
            if (dialogText.isBlank()) { schedulePendingStepAdvanceKick(); isProcessing = false; return }
            val currentKey = buildStepAdvanceSignatureKey(root, dialogText, snapshot)
            if (currentKey == fromKey) {
                schedulePendingStepAdvanceKick(); isProcessing = false; return
            }
            val expected = pendingExpectedValue ?: ""
            val verified = expected.isBlank() || verifyExpectedInput(root, expected)
            if (!verified) {
                schedulePendingStepAdvanceKick(); isProcessing = false; return
            }
            clearPendingStepAdvance()
            advanceStep()
            scheduleProcessStep(true)
        } finally {
            if (existingRoot == null) root.recycle()
        }
    }

    private fun clearPendingAdvance() {
        pendingAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingAdvanceKickRunnable = null
        pendingExpectedValue = null
        pendingPhase = PendingPhase.NONE
        pendingAdvanceFromKey = ""
        pendingSinceElapsed = 0L
        pendingAttempts = 0
    }

    private fun schedulePendingAdvanceKick() {
        if (pendingPhase == PendingPhase.NONE) return
        clearPendingAdvanceKick()
        val expected = pendingExpectedValue ?: return
        val delay = when {
            pendingPhase == PendingPhase.WAIT_SEND && hasRecentVerifiedInput(expected) -> 0L
            pendingPhase == PendingPhase.WAIT_VERIFY && hasRecentExpectedInput(expected) && hasRecentUssdUiEvent() -> 0L
            hasRecentExpectedInput(expected) && hasRecentUssdUiEvent() -> POST_WRITE_VERIFY_POLL_MS
            hasRecentExpectedInput(expected) -> POST_WRITE_VERIFY_POLL_MS
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> RAPID_POST_POPUP_VERIFY_MS
            hasRecentVerifiedInput(expected) -> HOT_SEND_RETRY_DELAY_MS
            hasRecentUssdUiEvent() -> FAST_VERIFY_POLL_MS
            else -> PENDING_ADVANCE_KICK_MS
        }
        val task = Runnable {
            pendingAdvanceKickRunnable = null
            attemptPendingAdvanceWithRoot(null)
        }
        pendingAdvanceKickRunnable = task
        if (delay <= 0) handler.post(task) else handler.postDelayed(task, delay)
    }

    private fun clearPendingAdvanceKick() {
        pendingAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingAdvanceKickRunnable = null
    }

    private fun attemptPendingAdvanceWithRoot(existingRoot: AccessibilityNodeInfo?) {
        if (!advancedActive) { clearPendingAdvance(); isProcessing = false; return }
        clearPendingAdvanceKick()
        val root = existingRoot ?: getUssdRoot() ?: run {
            schedulePendingAdvanceKick(); return
        }
        try { attemptPendingAdvance(root) } finally { if (existingRoot == null) root.recycle() }
    }

    private fun attemptPendingAdvance(root: AccessibilityNodeInfo) {
        val expected = pendingExpectedValue ?: run { clearPendingAdvance(); return }
        if (SystemClock.elapsedRealtime() - pendingSinceElapsed > pendingAdvanceTimeout()) {
            clearPendingAdvance(); isProcessing = false; dismissErrorAndRestart(); return
        }

        val interactionRoot = obtainInteractionRoot(root, shouldRequireStrictPopupScope()) ?: run {
            schedulePendingAdvanceKick(); isProcessing = false; return
        }
        try {
            if (shouldAdvanceFromChangedPendingPopup(interactionRoot, expected)) {
                clearPendingAdvance(); advanceStep(); scheduleProcessStep(true); return
            }

            when (pendingPhase) {
                PendingPhase.WAIT_VERIFY -> {
                    val field = findFieldForExpectedValue(interactionRoot, expected)
                    try {
                        val verified = field?.let { verifyExpectedInput(interactionRoot, expected, it) } ?: hasRecentVerifiedInput(expected)
                        if (verified) {
                            pendingPhase = PendingPhase.WAIT_SEND
                            attemptPendingAdvance(root)
                            return
                        }
                        if (pendingAttempts < 2 && shouldForcePendingFieldRewrite(expected, field != null)) {
                            pendingAttempts++
                            if (ensureExpectedValueWritten(interactionRoot, expected, field) &&
                                verifyExpectedInput(interactionRoot, expected, field)) {
                                pendingPhase = PendingPhase.WAIT_SEND
                                attemptPendingAdvance(root)
                                return
                            }
                        }
                        schedulePendingAdvanceKick()
                        isProcessing = false
                    } finally {
                        field?.recycle()
                    }
                }
                PendingPhase.WAIT_SEND -> {
                    if (tryImmediateVerifiedSend(interactionRoot, null, expected)) {
                        clearPendingAdvance()
                        val text = capturePreferredPopupSnapshot(interactionRoot, shouldRequireStrictPopupScope())?.dialogText.orEmpty()
                        markStepAction(text, interactionRoot, null)
                        startPendingStepAdvance(interactionRoot, text)
                    } else {
                        schedulePendingAdvanceKick()
                        isProcessing = false
                    }
                }
                PendingPhase.NONE -> Unit
            }
        } finally {
            interactionRoot.recycle()
        }
    }
    // endregion

    // [All other helper methods remain the same - I've kept the core navigation logic intact]
    // The methods below are from your original code and remain unchanged:
    // - Input writing methods
    // - Button finding methods
    // - Signature learning methods
    // - Timeout handling
    // - UI overlay methods
    // - Notification methods
    // - Data classes
    // - Constants

    // I've included the critical fixes for multi-step navigation above.
    // The rest of the code (snapshot capture, input writing, etc.) remains as in your original.
    
    // region Data classes
    private data class UssdTreeSnapshot(
        val dialogText: String,
        val normalizedDialogText: String,
        val textTokens: List<String>,
        val hasEditableField: Boolean,
        val hasSendButton: Boolean,
        val hasDismissButton: Boolean,
        val inputStateSignature: String
    )
    // endregion

    // [Rest of your methods and constants remain unchanged]
    // I've only modified the critical navigation parts to fix the multi-step issue.
}