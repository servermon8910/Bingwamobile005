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

        // Public state (volatile for cross‑thread visibility)
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
        @Volatile var signatureAction = "STOP"          // "STOP" or "ADJUST"
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

    // region Internal Helpers (all functionality is inside this service – no external dependencies)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread
    private var windowManager: WindowManager? = null

    private var isProcessing = false
    private var lastDialogText = ""
    private var lastFinalResponse = ""

    // Step timeouts
    private var stepTimeoutRunnable: Runnable? = null
    private var processStepRunnable: Runnable? = null

    // Input write markers
    private var lastInputWriteValue = ""
    private var lastInputWriteElapsed = 0L
    private var lastVerifiedInputValue = ""
    private var lastVerifiedInputElapsed = 0L

    // Pending operations
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

    // Window state
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

    // Recent USSD context cache (to avoid repeated scans)
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

    // Overlay & foreground state
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

    // Signature tracking
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
        } catch (e: Throwable) { Log.e(TAG, "onDestroy crashed", e) }
    }
    // endregion

    // region Accessibility Event Handling (core logic)
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

            val windowId = event.windowId
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

    // region Event Dedup
    private fun shouldSkipDuplicateEvent(event: AccessibilityEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        val fingerprint = buildEventFingerprint(event)
        val duplicate = fingerprint.isNotBlank() && fingerprint == lastEventFingerprint && now - lastEventElapsed <= DUPLICATE_EVENT_WINDOW_MS
        lastEventFingerprint = fingerprint
        lastEventElapsed = now
        return duplicate
    }

    private fun buildEventFingerprint(event: AccessibilityEvent): String {
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val text = event.text?.joinToString(" ") { it.toString() }.orEmpty()
        val desc = event.contentDescription?.toString().orEmpty()
        val contentType = runCatching { event.contentChangeTypes }.getOrDefault(0)
        return "${event.eventType}|${event.windowId}|$contentType|$pkg|$cls|$text|$desc"
    }

    private fun extractDialogText(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        val text = event.text
        if (text != null && text.isNotEmpty()) {
            for (cs in text) {
                val s = cs?.toString()?.trim() ?: continue
                if (s.isNotBlank()) parts += s
            }
        }
        val desc = event.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && !parts.any { it.contains(desc, ignoreCase = true) }) parts += desc
        val pkg = event.packageName?.toString()?.trim()
        if (!pkg.isNullOrBlank() && !parts.any { it.contains(pkg, ignoreCase = true) }) parts += pkg
        val cls = event.className?.toString()?.trim()
        if (!cls.isNullOrBlank() && !parts.any { it.contains(cls, ignoreCase = true) }) parts += cls
        return normalizeCollapsedText(parts.distinct().joinToString(" "))
    }

    private fun normalizeCollapsedText(value: String?): String {
        return value.orEmpty()
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun normalizeActionLabel(value: String?): String {
        return normalizeCollapsedText(value).lowercase()
    }

    private fun normalizeMenuText(value: String?): String {
        val normalized = normalizeActionLabel(value)
            .replace(LEADING_DIGIT_REGEX, "")
            .replace(NON_ALPHANUMERIC_REGEX, " ")
        return normalizeCollapsedText(normalized)
    }

    private fun tokenizeMenuLabel(value: String?): Set<String> {
        return normalizeMenuText(value)
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun isLikelyPromptText(value: String?): Boolean {
        val normalized = normalizeActionLabel(value)
        if (normalized.isBlank()) return false
        if (PHONE_INPUT_HINTS.any { normalized.contains(it) }) return true
        return INPUT_FIELD_HINTS.any { normalized.contains(it) }
    }
    // endregion

    // region Root & Window Acquisition (official APIs)
    private fun getUssdRoot(): AccessibilityNodeInfo? {
        val now = SystemClock.elapsedRealtime()
        val cachedRoot = recentUssdRoot
        if (cachedRoot != null && now - recentUssdCapturedElapsed < 120) {
            try {
                val pkg = cachedRoot.packageName?.toString() ?: ""
                if (isPotentialUssdPackage(pkg) || shouldAllowSystemUi(cachedRoot, pkg) || pkg == "android") {
                    return AccessibilityNodeInfo.obtain(cachedRoot)
                }
            } catch (_: Exception) {}
        }

        // 1) Try active window
        val active = rootInActiveWindow
        if (active != null) {
            val pkg = active.packageName?.toString() ?: ""
            if (isPotentialUssdPackage(pkg) || shouldAllowSystemUi(active, pkg) || pkg == "android") {
                recycleRecentUssdRoot()
                recentUssdRoot = AccessibilityNodeInfo.obtain(active)
                recentUssdCapturedElapsed = now
                return AccessibilityNodeInfo.obtain(active)
            }
            active.recycle()
        }

        // 2) Use getWindows() (official API, works on all Android versions with accessibility)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = try { windows } catch (_: Exception) { return null }
            for (win in windows) {
                // Skip overlays if not needed
                if (win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = try { win.root } catch (_: Exception) { null } ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (isPotentialUssdPackage(pkg) || shouldAllowSystemUi(root, pkg) || pkg == "android") {
                    recycleRecentUssdRoot()
                    recentUssdRoot = AccessibilityNodeInfo.obtain(root)
                    recentUssdCapturedElapsed = now
                    return root
                }
                root.recycle()
            }
        }
        return null
    }

    private fun obtainRootFromEvent(event: AccessibilityEvent): AccessibilityNodeInfo? {
        val source = try { event.source } catch (_: Exception) { null } ?: return null
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(source)
        var result: AccessibilityNodeInfo? = null
        var depth = 0
        try {
            while (current != null && depth < 24) {
                val parent = try { current.parent } catch (_: Exception) { null }
                if (parent == null) {
                    result = AccessibilityNodeInfo.obtain(current)
                    break
                }
                val next = try { AccessibilityNodeInfo.obtain(parent) } catch (_: Exception) { null }
                current.recycle()
                current = next
                depth++
            }
        } finally {
            current?.recycle()
            source.recycle()
        }
        val candidate = result ?: return null
        val pkg = candidate.packageName?.toString() ?: ""
        if (!isPotentialUssdPackage(pkg) && !shouldAllowSystemUi(candidate, pkg) && pkg != "android") {
            candidate.recycle()
            return null
        }
        return candidate
    }

    private fun shouldAllowSystemUi(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (pkg != "com.android.systemui") return false
        if (!advancedActive && !isForegroundUiActive() && balanceCallback == null && tokenPurchaseCallback == null && !signatureLearningMode) return false
        if (!hasDialogLayout(root)) return false
        val text = normalizeCollapsedText(extractAllText(root))
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        val hasAction = hasSendOrOkButton(root) || hasDismissButton(root) || hasEditableField(root)
        if (!hasAction) return false
        val menuLike = Regex("""\b\d+\s*[\)\].:\-]""").containsMatchIn(lower)
        val hasUssdLanguage = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        return hasUssdLanguage || menuLike
    }

    private fun isPotentialUssdPackage(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == "android") return false
        if (pkg in USSD_PACKAGES) return true
        val lower = pkg.lowercase()
        return USSD_PACKAGE_HINTS.any { lower.contains(it) }
    }

    private fun hasDialogLayout(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return cls.contains("Dialog", ignoreCase = true) ||
                cls.contains("AlertDialog", ignoreCase = true) ||
                cls.contains("BottomSheet", ignoreCase = true)
    }
    // endregion

    // region Snapshot & Parsing
    private data class UssdTreeSnapshot(
        val dialogText: String,
        val normalizedDialogText: String,
        val textTokens: List<String>,
        val hasEditableField: Boolean,
        val hasSendButton: Boolean,
        val hasDismissButton: Boolean,
        val inputStateSignature: String
    )

    private fun capturePreferredPopupSnapshot(root: AccessibilityNodeInfo, requireStrict: Boolean): UssdTreeSnapshot? {
        val strict = captureStrictSnapshot(root)
        if (strict != null) return strict
        if (requireStrict && !shouldAllowRelaxedFallback(root)) return null
        return captureSnapshot(root)
    }

    private fun captureStrictSnapshot(root: AccessibilityNodeInfo): UssdTreeSnapshot? {
        val captureRoot = findDialogCaptureRoot(root) ?: return null
        return try { buildSnapshot(captureRoot) } finally { captureRoot.recycle() }
    }

    private fun captureSnapshot(root: AccessibilityNodeInfo): UssdTreeSnapshot {
        val captureRoot = findDialogCaptureRoot(root) ?: AccessibilityNodeInfo.obtain(root)
        return try { buildSnapshot(captureRoot) } finally { captureRoot.recycle() }
    }

    private fun buildSnapshot(node: AccessibilityNodeInfo): UssdTreeSnapshot {
        val acc = TreeScanAccumulator()
        collectTreeSnapshot(node, acc)
        val dialogText = normalizeCollapsedText(acc.textTokens.joinToString(" "))
        return UssdTreeSnapshot(
            dialogText = dialogText,
            normalizedDialogText = dialogText,
            textTokens = acc.textTokens.toList(),
            hasEditableField = acc.hasEditableField,
            hasSendButton = acc.hasSendButton,
            hasDismissButton = acc.hasDismissButton,
            inputStateSignature = acc.bestInputSignature
        )
    }

    private class TreeScanAccumulator {
        val textTokens = mutableListOf<String>()
        var hasEditableField = false
        var hasSendButton = false
        var hasDismissButton = false
        var bestInputSignature = ""
        var bestInputScore = Int.MIN_VALUE
    }

    private fun collectTreeSnapshot(node: AccessibilityNodeInfo, acc: TreeScanAccumulator) {
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { acc.textTokens += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { acc.textTokens += it }
            if (supportsDirectInput(node) || isLooseInputCandidate(node)) {
                acc.hasEditableField = true
                val score = scoreTextEntryCandidate(node)
                if (score >= acc.bestInputScore) {
                    acc.bestInputScore = score
                    acc.bestInputSignature = buildInputNodeSignature(node)
                }
            }
            if (isSendActionNode(node)) acc.hasSendButton = true
            if (isDismissActionNode(node)) acc.hasDismissButton = true
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectTreeSnapshot(child, acc)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun findDialogCaptureRoot(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect().also { runCatching { root.getBoundsInScreen(it) } }
        val candidates = mutableListOf<DialogCaptureCandidate>()
        collectDialogCandidates(root, rootBounds, candidates, 0)
        return candidates.maxByOrNull { it.score }?.node?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private data class DialogCaptureCandidate(val node: AccessibilityNodeInfo, val score: Int)

    private fun collectDialogCandidates(node: AccessibilityNodeInfo, rootBounds: Rect, into: MutableList<DialogCaptureCandidate>, depth: Int) {
        val score = scoreDialogCandidate(node, rootBounds, depth)
        if (score > 0) into += DialogCaptureCandidate(AccessibilityNodeInfo.obtain(node), score)
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            collectDialogCandidates(child, rootBounds, into, depth + 1)
            child.recycle()
        }
    }

    private fun scoreDialogCandidate(node: AccessibilityNodeInfo, rootBounds: Rect, depth: Int): Int {
        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        if (childCount == 0) return 0
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        val area = bounds.width().toLong() * bounds.height().toLong()
        val rootArea = rootBounds.width().toLong() * rootBounds.height().toLong()
        if (area <= 0 || rootArea <= 0) return 0
        val ratio = area.toFloat() / rootArea.toFloat()
        if (ratio < 0.03f) return 0

        val acc = TreeScanAccumulator()
        collectTreeSnapshot(node, acc)
        val text = acc.textTokens.joinToString(" ").lowercase()
        if (text.isBlank()) return 0
        if (NON_USSD_DIALOG_HINTS.any { text.contains(it) }) return 0

        val hasAction = acc.hasSendButton || acc.hasDismissButton || acc.hasEditableField
        val hasMenu = MENU_ITEM_REGEX.containsMatchIn(text)
        val hasUssdLang = USSD_HINTS.any { text.contains(it) } || errorKeywords.any { text.contains(it) }
        if (!hasAction && !hasMenu) return 0
        if (!hasUssdLang && !hasMenu && !advancedActive && !isForegroundUiActive()) return 0

        var score = 0
        if (acc.hasEditableField) score += 380
        if (acc.hasSendButton) score += 260
        if (acc.hasDismissButton) score += 120
        if (hasMenu) score += 260 + (parseMenuOptions(acc.textTokens)?.size ?: 0) * 45
        if (hasUssdLang) score += 220
        if (node.className?.toString().orEmpty().contains("Dialog", ignoreCase = true)) score += 180
        if (childCount in 1..8) score += 140
        else if (childCount in 1..14) score += 70
        else score -= minOf(childCount, 32) * 18
        if (ratio in 0.08f..0.86f) score += 180
        else if (ratio > 0.96f) score -= 220
        if (bounds.left > rootBounds.left || bounds.top > rootBounds.top || bounds.right < rootBounds.right || bounds.bottom < rootBounds.bottom) score += 80
        score += maxOf(0, 42 - depth * 4)
        return score.takeIf { it > 0 } ?: 0
    }

    private fun parseMenuOptions(tokens: List<String>): LinkedHashMap<String, String>? {
        val lines = tokens.map { normalizeCollapsedText(it) }.filter { it.isNotBlank() }
        for (i in lines.indices) {
            val match = MENU_OPTION_REGEX.find(lines[i])
            if (match != null) {
                val opts = linkedMapOf<String, String>()
                var idx = i
                while (idx < lines.size) {
                    val line = lines[idx]
                    val m = MENU_OPTION_REGEX.find(line)
                    if (m != null) {
                        val key = m.groupValues[1]
                        val label = normalizeCollapsedText(m.groupValues[2])
                        if (label.isBlank()) break
                        opts[key] = label
                        idx++
                    } else {
                        break
                    }
                }
                if (opts.size >= 2 && opts.keys.mapNotNull { it.toIntOrNull() }.sorted() == opts.keys.map { it.toInt() }.sorted()) {
                    return opts
                }
            }
        }
        return null
    }

    private fun shouldAllowRelaxedFallback(root: AccessibilityNodeInfo): Boolean {
        if (!advancedActive && !isForegroundUiActive() && balanceCallback == null && tokenPurchaseCallback == null && !signatureLearningMode) return false
        val pkg = root.packageName?.toString().orEmpty()
        if (!isPotentialUssdPackage(pkg) && pkg != "android" && pkg != "com.android.systemui") return false
        val snapshot = captureSnapshot(root)
        val lower = snapshot.dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        return looksLikeUssdDialog(root, snapshot, lower, pkg) || hasDialogLayout(root)
    }

    private fun looksLikeUssdDialog(root: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot, lower: String, pkg: String): Boolean {
        val hasUssdLang = USSD_HINTS.any { lower.contains(it) }
        val hasMenu = parseMenuOptions(snapshot.textTokens) != null
        val likelyPkg = isPotentialUssdPackage(pkg) || (pkg == "android" && (hasUssdLang || hasMenu))
        return (snapshot.hasEditableField && (snapshot.hasSendButton || hasUssdLang || hasMenu)) ||
                ((snapshot.hasSendButton || snapshot.hasDismissButton) && (hasUssdLang || hasMenu)) ||
                (hasMenu && hasUssdLang) ||
                (hasDialogLayout(root) && likelyPkg && (snapshot.hasEditableField || snapshot.hasSendButton || snapshot.hasDismissButton))
    }

    private fun looksLikeUssdDialogFast(lower: String, pkg: String): Boolean {
        val hasUssdLang = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        val menuLike = MENU_ITEM_REGEX.containsMatchIn(lower) || MENU_OPTION_REGEX.containsMatchIn(lower)
        if (pkg == "android" || pkg.isBlank()) return advancedActive || isForegroundUiActive() || (hasUssdLang || menuLike)
        return isPotentialUssdPackage(pkg) && (hasUssdLang || menuLike)
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
        if (lastProcessedStep == currentStep) {
            isProcessing = false
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

            if (shouldWaitForStepTransition(dialogText, context.windowId, root, snapshot)) {
                isProcessing = false
                scheduleProcessStep(false)
                return
            }

            if (dialogText.isNotBlank()) lastFinalResponse = dialogText
            if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) { isProcessing = false; scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS); return }
            if (!looksLikeUssdDialogFast(lower, context.windowPkg)) { isProcessing = false; scheduleProcessStep(false, RAPID_POST_POPUP_POLL_MS); return }

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

                if (!shouldPreferText && step.all(Char::isDigit) && menu != null && menu.isNotEmpty()) {
                    if (!menu.containsKey(valueToEnter)) {
                        isProcessing = false
                        dismissErrorAndRestart()
                        return
                    }
                }

                if (inputField == null && shouldPreferText && !dialogSuggestsTextInput(lower) && !dialogAllowsPhone && step != "INPUT_PHONE" && !shouldTreatNumericReplyAsTextInput(step, valueToEnter, snapshot, lower, menu)) {
                    if (!snapshot.hasEditableField) {
                        if (!writeValueToField(root, valueToEnter)) {
                            isProcessing = false
                            dismissErrorAndRestart()
                            return
                        }
                    }
                }

                if (inputField != null || shouldPreferText) {
                    val wrote = ensureExpectedValueWritten(root, valueToEnter, inputField)
                    if (!wrote) {
                        isProcessing = false
                        dismissErrorAndRestart()
                        return
                    }
                    val trusted = shouldTrustFreshWrite(wrote, valueToEnter, inputField, snapshot, lower)
                    val recentVerified = trusted || hasRecentVerifiedInput(valueToEnter)

                    if (!isFinalLearningStep(currentStep) && wrote &&
                        tryImmediateVerifiedSend(root, inputField, valueToEnter, skipVerification = true)) {
                        markStepAction(dialogText, root, snapshot)
                        startPendingStepAdvance(root, dialogText)
                        return
                    }

                    if (!isFinalLearningStep(currentStep) && wrote &&
                        shouldAttemptAggressiveImmediateSubmit(snapshot, lower, step, valueToEnter, inputField) &&
                        tryAggressiveImmediateSubmit(root, inputField, valueToEnter, skipVerification = true)) {
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

                // Menu button click
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

    private fun parseMenuFromSnapshot(snapshot: UssdTreeSnapshot): LinkedHashMap<String, String>? {
        val lines = snapshot.textTokens.map { normalizeCollapsedText(it) }.filter { it.isNotBlank() }
        return parseMenuOptions(lines)
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

    private fun buildDialogStateKey(dialogText: String, inputSig: String): String {
        val normalized = normalizeMenuText(dialogText)
        return if (normalized.isBlank()) "" else "$normalized|${normalizeCollapsedText(inputSig)}"
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

    private fun shouldAdvanceFromChangedPendingPopup(root: AccessibilityNodeInfo, expected: String): Boolean {
        val fromKey = pendingAdvanceFromKey
        if (fromKey.isBlank()) return false
        val snapshot = capturePreferredPopupSnapshot(root, shouldRequireStrictPopupScope())
        val dialogText = snapshot?.dialogText ?: normalizeCollapsedText(extractAllText(root))
        if (dialogText.isBlank()) return false
        val currentKey = buildTransitionSignatureKey(root, dialogText, snapshot)
        if (currentKey == fromKey) return false
        pendingAdvanceFromKey = currentKey
        return true
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
    // endregion

    // region Step Advance (after a successful action)
    private fun startPendingStepAdvance(root: AccessibilityNodeInfo, dialogText: String) {
        currentStepRetryCount = 0
        clearPendingStepAdvance()
        clearPendingAdvance()
        pendingStepAdvanceSinceElapsed = SystemClock.elapsedRealtime()
        val snapshot = capturePreferredPopupSnapshot(root, shouldRequireStrictPopupScope())
        pendingStepAdvanceFromKey = buildStepAdvanceSignatureKey(root, dialogText, snapshot)
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
        clearPendingStepAdvance()
        advanceStep()
        scheduleProcessStep(true)
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

    private fun advanceStep() {
        lastProcessedStep = currentStep
        currentStep++
        currentStepRetryCount = 0
        isProcessing = false
        lastDialogText = ""
        lastScreenSignatureKey = ""
        lastObservedDialogStateKey = ""
        lastObservedDialogStateChangedElapsed = 0L
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarkers()
        requestAppUiBehindPopup()
        updateOverlay()
        startStepTimeout()
    }

    private fun stepAdvanceTimeoutMs(): Long {
        return if (shouldUseExtendedTimeout()) NETWORK_DELAY_STEP_ADVANCE_TIMEOUT_MS else PENDING_STEP_ADVANCE_TIMEOUT_MS
    }

    private fun pendingAdvanceTimeout(): Long {
        return if (shouldUseExtendedTimeout()) NETWORK_DELAY_PENDING_ADVANCE_TIMEOUT_MS else PENDING_ADVANCE_TIMEOUT_MS
    }
    // endregion

    // region Input Writing (official ACTION_SET_TEXT and fallbacks)
    private fun writeValueToField(root: AccessibilityNodeInfo, value: String): Boolean {
        val fields = mutableListOf<AccessibilityNodeInfo>()
        collectTextEntryCandidates(root, fields)
        if (fields.isEmpty()) collectAggressiveTextEntryCandidates(root, fields)
        fields.sortByDescending { scoreTextEntryCandidate(it) }
        return fields.any { tryWriteValueToField(it, value, root) }
    }

    private fun ensureExpectedValueWritten(root: AccessibilityNodeInfo, expected: String, preferredField: AccessibilityNodeInfo?): Boolean {
        if (verifyExpectedInput(root, expected, preferredField)) return true
        if (preferredField != null && tryWriteValueToField(preferredField, expected, root)) return true

        val field = findFieldForExpectedValue(root, expected)
        try {
            if (field != null && field !== preferredField) {
                if (verifyExpectedInput(root, expected, field)) return true
                if (tryWriteValueToField(field, expected, root)) return true
            }
        } finally {
            field?.recycle()
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectTextEntryCandidates(root, candidates)
        if (candidates.isEmpty()) collectAggressiveTextEntryCandidates(root, candidates)
        candidates.sortByDescending { scoreTextEntryCandidate(it) + if (matchesExpectedInput(readFieldText(it), expected)) 700 else 0 }
        return candidates.any { tryWriteValueToField(it, expected, root) }
    }

    private fun tryWriteValueToField(field: AccessibilityNodeInfo, value: String, verificationRoot: AccessibilityNodeInfo? = null): Boolean {
        var wrote = false
        for (pass in 0 until FORCEFUL_WRITE_PASSES) {
            val targets = obtainInputTargets(field)
            try {
                targets.forEach { target ->
                    val result = writeValueUsingStrategies(target, value)
                    if (result.wroteValue) {
                        wrote = true
                        rememberInputWrite(value)
                        if (result.likelyVerified || verifyWrittenValueWithRetries(verificationRoot, target, value)) {
                            rememberVerifiedInput(value)
                            return true
                        }
                    }
                }
                if (wrote && verifyWrittenValueWithRetries(verificationRoot, field, value)) {
                    rememberVerifiedInput(value)
                    return true
                }
            } finally {
                targets.forEach { it.recycle() }
            }
            primeInputTarget(field, true)
            refreshInputTarget(field)
            verificationRoot?.refresh()
        }
        return wrote && verifyWrittenValueWithRetries(verificationRoot, field, value)
    }

    private fun writeValueUsingStrategies(node: AccessibilityNodeInfo, value: String): InputWriteResult {
        primeInputTarget(node)
        attemptSetTextBurst(node, value)?.let { return it }
        primeInputTarget(node, true)
        attemptSetTextBurst(node, value, true)?.let { return it }
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) && primeInputTarget(node, true)) {
            refreshInputTarget(node)
            attemptSetTextBurst(node, value, true)?.let { return it }
        }
        primeInputTarget(node, true)
        attemptPasteBurst(node, value)?.let { return it }
        return InputWriteResult(false, false)
    }

    private fun attemptSetTextBurst(node: AccessibilityNodeInfo, value: String, aggressive: Boolean = false): InputWriteResult? {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return null
        repeat(SET_TEXT_BURST_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                if (aggressive) primeInputTarget(node, true) else refreshInputTarget(node)
                SystemClock.sleep(lowEndDelay(SETTLE_BETWEEN_WRITE_PASSES_MS))
            }
            if (setTextOnNode(node, value)) {
                val likely = isLikelyDirectWriteVerified(node, value)
                if (likely || attempt == SET_TEXT_BURST_ATTEMPTS - 1) {
                    return InputWriteResult(true, likely)
                }
            }
        }
        return null
    }

    private fun attemptPasteBurst(node: AccessibilityNodeInfo, value: String, aggressive: Boolean = false): InputWriteResult? {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) return null
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val previousClip = runCatching { clipboard.primaryClip }.getOrNull()
        val hadClip = runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false)
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("ussd_reply", value))
            repeat(PASTE_BURST_ATTEMPTS) { attempt ->
                if (attempt > 0) {
                    if (aggressive) primeInputTarget(node, true) else refreshInputTarget(node)
                    SystemClock.sleep(lowEndDelay(SETTLE_BETWEEN_WRITE_PASSES_MS))
                }
                if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)) {
                    val likely = isLikelyDirectWriteVerified(node, value)
                    if (likely || attempt == PASTE_BURST_ATTEMPTS - 1) {
                        return InputWriteResult(true, likely)
                    }
                }
            }
            null
        } finally {
            runCatching {
                if (hadClip && previousClip != null) clipboard.setPrimaryClip(previousClip)
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, value: String): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return false
        if (runCatching {
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                )
            }.getOrDefault(false)
        ) {
            collapseInputSelection(node, value)
            if (isLikelyDirectWriteVerified(node, value)) return true
        }
        // fallback: clear and retry
        runCatching {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
            )
        }
        return reinforceTextWrite(node, value)
    }

    private fun reinforceTextWrite(node: AccessibilityNodeInfo, value: String): Boolean {
        repeat(2) { attempt ->
            if (attempt > 0) {
                primeInputTarget(node, true)
                refreshInputTarget(node)
                runCatching {
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
                    )
                }
            }
            if (runCatching {
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                    )
                }.getOrDefault(false)
            ) {
                collapseInputSelection(node, value)
                if (isLikelyDirectWriteVerified(node, value)) return true
            }
        }
        return false
    }

    private fun isLikelyDirectWriteVerified(node: AccessibilityNodeInfo, expected: String): Boolean {
        refreshInputTarget(node)
        val read = readFieldText(node)
        return matchesExpectedInput(read, expected)
    }

    private fun collapseInputSelection(node: AccessibilityNodeInfo, value: String) {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION)) return
        val pos = value.length
        runCatching {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
                }
            )
        }
    }

    private fun verifyWrittenValueWithRetries(verificationRoot: AccessibilityNodeInfo?, field: AccessibilityNodeInfo, expected: String): Boolean {
        repeat(WRITE_VERIFICATION_PASSES) { attempt ->
            if (attempt > 0) {
                refreshInputTarget(field)
                verificationRoot?.refresh()
                SystemClock.sleep(WRITE_VERIFICATION_SETTLE_MS)
            }
            if (verifyExpectedInput(verificationRoot, expected, field)) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attempt == WRITE_VERIFICATION_PASSES - 2) {
                dispatchGestureInput(field, expected)
            }
        }
        return false
    }

    private fun dispatchGestureInput(field: AccessibilityNodeInfo, value: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val bounds = Rect().also { runCatching { field.getBoundsInScreen(it) } }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val clickStart = SystemClock.uptimeMillis()
        val clickEnd = clickStart + TAP_GESTURE_DURATION_MS
        val clickPath = Path().apply { moveTo(centerX.toFloat(), centerY.toFloat()) }
        val clickStroke = GestureDescription.StrokeDescription(clickPath, clickStart, clickEnd - clickStart, false)
        val clickDesc = GestureDescription.Builder().addStroke(clickStroke).build()
        var clicked = false
        runCatching { dispatchGesture(clickDesc, null, null) }.onSuccess { clicked = true }.onFailure { Log.w(TAG, "dispatchGesture tap failed", it) }
        if (!clicked) return false
        SystemClock.sleep(POST_GESTURE_WAIT_MS + TAP_GESTURE_RETRY_SETTLE_MS)
        val chars = value.toCharArray()
        if (chars.isEmpty()) return true
        var gestureOk = true
        chars.forEachIndexed { idx, ch ->
            val keyStart = SystemClock.uptimeMillis() + idx * CHAR_GESTURE_STAGGER_MS
            val keyEnd = keyStart + CHAR_GESTURE_DURATION_MS
            val keyPath = Path().also { p ->
                val offsetX = ((idx % 5) - 2) * CHAR_GESTURE_SPREAD_X
                val offsetY = (idx / 5) * CHAR_GESTURE_SPREAD_Y
                p.moveTo((centerX + offsetX).toFloat(), (centerY + offsetY).toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(keyPath, keyStart, keyEnd - keyStart, false)
            val desc = GestureDescription.Builder().addStroke(stroke).build()
            runCatching { dispatchGesture(desc, null, null) }.onFailure { gestureOk = false; Log.w(TAG, "dispatchGesture char input failed at idx=$idx", it) }
        }
        return gestureOk
    }

    private fun obtainInputTargets(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val targets = mutableListOf<AccessibilityNodeInfo>()
        collectPreferredInputTargets(node, targets, 0)
        collectNearbyInputTargets(node, targets, 0)
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < INPUT_TARGET_DEPTH) {
            if (supportsDirectInput(current)) targets += current else current.recycle()
            current = try { current.parent?.let { AccessibilityNodeInfo.obtain(it) } } catch (_: Exception) { null }
            depth++
        }
        return rankAndDedupe(targets)
    }

    private fun rankAndDedupe(targets: MutableList<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        if (targets.size <= 1) return targets
        val seen = HashSet<String>()
        return targets.sortedByDescending { scoreDirectInputTarget(it) }
            .filter { node -> seen.add(buildInputTargetKey(node)) }
            .toList()
    }

    private fun collectPreferredInputTargets(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > INPUT_DESCENT_DEPTH) return
        try {
            if (supportsDirectInput(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectPreferredInputTargets(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun collectNearbyInputTargets(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>, ancestorDepth: Int) {
        if (ancestorDepth >= INPUT_NEARBY_SCOPE_DEPTH) return
        val parent = try { node.parent } catch (_: Exception) { null } ?: return
        try {
            for (i in 0 until parent.childCount) {
                val sibling = try { parent.getChild(i) } catch (_: Exception) { null } ?: continue
                collectPreferredInputTargets(sibling, into, 0)
                sibling.recycle()
            }
            collectNearbyInputTargets(parent, into, ancestorDepth + 1)
        } finally {
            parent.recycle()
        }
    }

    private fun supportsDirectInput(node: AccessibilityNodeInfo): Boolean =
        isTextEntryNode(node) || supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) || isHiddenInputProxyCandidate(node)

    private fun primeInputTarget(node: AccessibilityNodeInfo, aggressive: Boolean = false): Boolean {
        var changed = false
        for (i in 0 until 3) {
            if (refocusInputTarget(node)) changed = true
            if (activateInputTarget(node)) changed = true
            if (aggressive && supportsAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK) &&
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
            ) changed = true
            if (changed) break
            SystemClock.sleep(lowEndDelay(PRIME_TARGET_SETTLE_MS))
        }
        refreshInputTarget(node)
        return changed
    }

    private fun refocusInputTarget(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false) ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) }.getOrDefault(false))

    private fun activateInputTarget(node: AccessibilityNodeInfo): Boolean {
        if (!isSafeInputActivationCandidate(node)) return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false) ||
                performTapGesture(node)
    }

    private fun isSafeInputActivationCandidate(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        return editable || supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
                EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } || hasInputViewHint(viewId, hint)
    }

    private fun refreshInputTarget(node: AccessibilityNodeInfo) = runCatching { node.refresh() }

    @Suppress("DEPRECATION")
    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return runCatching {
            val legacy = (node.actions and actionId) != 0
            if (legacy) return@runCatching true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.actionList?.any { it.id == actionId } == true
            } else false
        }.getOrDefault(false)
    }

    private fun isTextEntryNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        if (cls.contains("Button", ignoreCase = true) || cls.contains("ImageButton", ignoreCase = true)) return false
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS || label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) return false
        return editable ||
                EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } ||
                supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
                (supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE) && (EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } || hasInputViewHint(viewId, hint))) ||
                (hasInputViewHint(viewId, hint) && (runCatching { node.isFocusable || node.isFocused }.getOrDefault(false) || runCatching { node.isClickable }.getOrDefault(false))) ||
                (hasInputLabelHint(label, desc) && (editable || supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)))
    }

    private fun isHiddenInputProxyCandidate(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val focusable = runCatching { node.isFocusable || node.isFocused || node.isClickable || node.isLongClickable }.getOrDefault(false)
        val hasSetText = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
        val hasPaste = supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        if (cls.contains("Button", ignoreCase = true) || cls.contains("ImageButton", ignoreCase = true)) return false
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS || label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) return false
        return (hasSetText || hasPaste) &&
                (EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } || hasInputViewHint(viewId, hint) || hasInputLabelHint(label, desc) || focusable) ||
                (focusable && (hasInputViewHint(viewId, hint) || hasInputLabelHint(label, desc)))
    }

    private fun collectTextEntryCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > VIEW_TRAVERSAL_MAX_DEPTH) return
        try {
            if (isTextEntryNode(node) || isLooseInputCandidate(node) || isHiddenInputProxyCandidate(node)) {
                into += AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectTextEntryCandidates(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun collectAggressiveTextEntryCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > VIEW_TRAVERSAL_MAX_DEPTH) return
        try {
            if (isAggressiveTextEntryCandidate(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectAggressiveTextEntryCandidates(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun isLooseInputCandidate(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val focusable = runCatching { node.isFocusable || node.isFocused }.getOrDefault(false)
        val clickable = runCatching { node.isClickable || node.isLongClickable }.getOrDefault(false)
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (editable) return false
        if (cls.contains("Button", ignoreCase = true) || cls.contains("ImageButton", ignoreCase = true)) return false
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS || label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) return false
        if (label.isNotBlank() && label.length > 24) return false
        val hasWritable = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) || supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val hasViewHint = hasInputViewHint(viewId, hint)
        return (hasWritable && (EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } || hasViewHint || focusable)) ||
                (hasViewHint && (focusable || clickable))
    }

    private fun isAggressiveTextEntryCandidate(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        val writable = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) || supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val focusable = runCatching { node.isFocusable || node.isFocused || node.isClickable || node.isLongClickable }.getOrDefault(false)
        if (cls.contains("Button", ignoreCase = true) || cls.contains("ImageButton", ignoreCase = true)) return false
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS || label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) return false
        return editable ||
                isHiddenInputProxyCandidate(node) ||
                isLooseInputCandidate(node) ||
                (writable && (EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } || hasInputViewHint(viewId, hint) || focusable)) ||
                (focusable && (hasInputViewHint(viewId, hint) || hasInputLabelHint(label, desc))) ||
                EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) }
    }

    private fun hasInputViewHint(viewId: String, hint: String): Boolean =
        INPUT_VIEW_ID_HINTS.any { viewId.contains(it) } || INPUT_FIELD_HINTS.any { hint.contains(it) }

    private fun hasInputLabelHint(label: String, desc: String): Boolean =
        INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) }

    private fun scoreTextEntryCandidate(node: AccessibilityNodeInfo): Int {
        val cls = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        var score = 0
        if (runCatching { node.isEditable }.getOrDefault(false)) score += 500
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 320
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) score += 140
        if (EDITABLE_CLASS_HINTS.any { cls.equals(it, ignoreCase = true) || cls.contains(it, ignoreCase = true) }) score += 300
        if (cls.contains("EditText", ignoreCase = true)) score += 240
        if (cls.contains("Text", ignoreCase = true)) score += 90
        if (INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 180
        if (INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) || hint.contains(it) }) score += 120
        if (runCatching { node.isFocused }.getOrDefault(false)) score += 90
        if (runCatching { node.isFocusable }.getOrDefault(false)) score += 70
        if (runCatching { node.isClickable }.getOrDefault(false)) score += 40
        val current = readFieldText(node)?.trim().orEmpty()
        if (current.isBlank()) score += 120
        else if (isLikelyPromptText(current)) score -= 180
        else if (current.length <= 24) score += 30
        else score -= 45
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score -= 280
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 280
        if (bounds.right > 0 || bounds.bottom > 0) { score += bounds.bottom / 24; score += bounds.right / 36 }
        return score
    }

    private fun scoreDirectInputTarget(node: AccessibilityNodeInfo): Int {
        var score = scoreTextEntryCandidate(node)
        if (runCatching { node.isEditable }.getOrDefault(false)) score += 320
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 260
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) score += 140
        if (runCatching { node.isFocused }.getOrDefault(false)) score += 90
        if (runCatching { node.isFocusable }.getOrDefault(false)) score += 60
        return score
    }

    private fun buildInputTargetKey(node: AccessibilityNodeInfo): String {
        val sig = buildInputNodeSignature(node)
        if (sig.isNotBlank()) return sig
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        val cls = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        return "$cls|$viewId|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    private fun readFieldText(node: AccessibilityNodeInfo): String? {
        val text = runCatching { node.text?.toString() }.getOrNull()
        if (!text.isNullOrBlank()) return text
        val desc = runCatching { node.contentDescription?.toString() }.getOrNull()
        if (!desc.isNullOrBlank()) return desc
        val tokens = mutableListOf<String>()
        extractTextTokens(node, tokens)
        return tokens.filterNot { isLikelyPromptText(it) }.minByOrNull { it.length }
            ?.takeIf { normalizeActionLabel(it).isNotBlank() && INPUT_FIELD_HINTS.none { normalizeActionLabel(it).contains(it) } }
    }

    private fun extractTextTokens(node: AccessibilityNodeInfo, into: MutableList<String>, depth: Int = 0) {
        if (depth > VIEW_TRAVERSAL_MAX_DEPTH) return
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { into += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { into += it }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                extractTextTokens(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun findFieldForExpectedValue(root: AccessibilityNodeInfo, expected: String): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectTextEntryCandidates(root, candidates)
        if (candidates.isEmpty()) collectAggressiveTextEntryCandidates(root, candidates)
        val match = candidates.firstOrNull { matchesExpectedInput(readFieldText(it), expected) }
        val result = match ?: candidates.maxByOrNull { scoreTextEntryCandidate(it) + if (matchesExpectedInput(readFieldText(it), expected)) 700 else 0 }
        return result?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun findEditableFieldForStep(root: AccessibilityNodeInfo, step: String, dialogText: String): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectTextEntryCandidates(root, candidates)
        if (candidates.isEmpty()) collectAggressiveTextEntryCandidates(root, candidates)
        return candidates.maxByOrNull { scoreTextEntryCandidateForStep(it, step, dialogText) }?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun scoreTextEntryCandidateForStep(node: AccessibilityNodeInfo, step: String, dialogText: String): Int {
        var score = scoreTextEntryCandidate(node)
        if (step != "INPUT_PHONE") return score
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val combined = listOf(viewId, label, desc, hint).joinToString(" ")
        if (PHONE_INPUT_HINTS.any { combined.contains(it) }) score += 260
        if (combined.contains("phone") || combined.contains("mobile") || combined.contains("msisdn")) score += 180
        if (combined.contains("recipient") || combined.contains("customer") || combined.contains("subscriber")) score += 140
        if (dialogSuggestsPhoneInput(dialogText.lowercase()) && INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 90
        return score
    }

    private fun obtainInteractionRoot(root: AccessibilityNodeInfo, requireStrict: Boolean): AccessibilityNodeInfo? {
        val captureRoot = findDialogCaptureRoot(root)
        if (captureRoot != null) return captureRoot
        if (requireStrict && !shouldAllowRelaxedFallback(root)) return null
        return AccessibilityNodeInfo.obtain(root)
    }

    private fun verifyExpectedInput(root: AccessibilityNodeInfo?, expected: String, existingField: AccessibilityNodeInfo? = null): Boolean {
        if (existingField != null) {
            refreshInputTarget(existingField)
            if (matchesExpectedInput(readFieldText(existingField), expected)) {
                rememberVerifiedInput(expected)
                return true
            }
        }
        val rootNode = root ?: getUssdRoot() ?: return false
        try {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectTextEntryCandidates(rootNode, candidates)
            if (candidates.isEmpty()) collectAggressiveTextEntryCandidates(rootNode, candidates)
            val verified = candidates.any { matchesExpectedInput(readFieldText(it), expected) }
            if (verified) rememberVerifiedInput(expected)
            return verified
        } finally {
            if (root == null) rootNode.recycle()
        }
    }

    private fun matchesExpectedInput(actual: String?, expected: String): Boolean {
        val a = normalizeInputValue(actual)
        val e = normalizeInputValue(expected)
        if (a.isBlank() || e.isBlank()) return false
        if (a == e || (a.length > e.length && a.startsWith(e))) return true
        if (isLikelyMaskedInput(a, e)) return true
        if (isLikelyMaskedInput(e, a)) return true
        val aPhone = normalizePhoneComparable(actual)
        val ePhone = normalizePhoneComparable(expected)
        return aPhone.isNotBlank() && ePhone.isNotBlank() && (aPhone == ePhone || (aPhone.length > ePhone.length && aPhone.startsWith(ePhone)))
    }

    private fun isLikelyMaskedInput(actual: String, expected: String): Boolean {
        val maskChars = setOf('*', '•', '●', '·')
        if (actual.isBlank() || expected.isBlank()) return false
        if (actual.length != expected.length) return false
        if (actual.all { it in maskChars }) return true
        return false
    }

    private fun normalizePhoneComparable(value: String?): String {
        val digits = value.orEmpty().replace(Regex("\\D+"), "")
        return if (digits.length < 9) "" else UssdHelper.normalizeRecipientForUssdInput(digits).replace(Regex("\\D+"), "")
    }

    private fun normalizeInputValue(value: String?) = value.orEmpty().replace(WHITESPACE_REGEX, "").trim()
    // endregion

    // region Button Finding (official view id and text search)
    private fun findMenuButton(root: AccessibilityNodeInfo, value: String, selectedLabel: String?): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        // exact match by text/contentDescription
        val exact = candidates.firstOrNull { normalizeActionLabel(it.text?.toString()) == normalizeActionLabel(value) ||
                normalizeActionLabel(it.contentDescription?.toString()) == normalizeActionLabel(value) }
        if (exact != null) return AccessibilityNodeInfo.obtain(exact)
        // contains match
        val contains = candidates.firstOrNull { normalizeActionLabel(it.text?.toString()).contains(normalizeActionLabel(value)) ||
                normalizeActionLabel(it.contentDescription?.toString()).contains(normalizeActionLabel(value)) }
        if (contains != null) return AccessibilityNodeInfo.obtain(contains)
        // by selected label
        if (!selectedLabel.isNullOrBlank()) {
            val labelMatch = candidates.firstOrNull { normalizeActionLabel(it.text?.toString()) == normalizeActionLabel(selectedLabel) ||
                    normalizeActionLabel(it.contentDescription?.toString()) == normalizeActionLabel(selectedLabel) }
            if (labelMatch != null) return AccessibilityNodeInfo.obtain(labelMatch)
        }
        // fallback to send button hints
        return findBestSendButton(root)
    }

    private fun findBestSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates.maxByOrNull { scoreSendActionCandidate(it) }?.let { AccessibilityNodeInfo.obtain(it) }
            ?: findAggressiveSendActionButton(root)
    }

    private fun findPositiveDialogButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates.filterNot { node ->
            normalizeActionLabel(node.text?.toString()) in DISMISS_BUTTON_LABELS ||
                    normalizeActionLabel(node.contentDescription?.toString()) in DISMISS_BUTTON_LABELS
        }.maxByOrNull { scoreActionCandidate(it) }?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun findBottomRightActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates.filterNot { node ->
            normalizeActionLabel(node.text?.toString()) in DISMISS_BUTTON_LABELS ||
                    normalizeActionLabel(node.contentDescription?.toString()) in DISMISS_BUTTON_LABELS
        }.maxByOrNull { scoreActionCandidate(it) + 120 }?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun collectActionCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > VIEW_TRAVERSAL_MAX_DEPTH) return
        try {
            if (isActionCandidate(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectActionCandidates(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun findAggressiveSendActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectAggressiveActionCandidates(root, candidates)
        return candidates.maxByOrNull { scoreSendActionCandidate(it) + scoreAggressiveActionCandidate(it) }
            ?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun collectAggressiveActionCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > VIEW_TRAVERSAL_MAX_DEPTH) return
        try {
            if (isAggressiveActionCandidate(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectAggressiveActionCandidates(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun isActionCandidate(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val actionable = runCatching { node.isClickable || node.isFocusable }.getOrDefault(false)
        val enabled = runCatching { node.isEnabled }.getOrDefault(true)
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) runCatching { node.isVisibleToUser }.getOrDefault(true) else true
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (!enabled || !visible || editable) return false
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val isButtonLike = cls.contains("Button", ignoreCase = true) || cls.contains("TextView", ignoreCase = true) ||
                cls.contains("ImageView", ignoreCase = true) || cls.contains("ImageButton", ignoreCase = true) ||
                cls.contains("View", ignoreCase = true)
        val hasHints = SEND_VIEW_ID_HINTS.any { viewId.contains(it) } || DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }
        return actionable && (isButtonLike || hasHints || label.isNotBlank() || desc.isNotBlank())
    }

    private fun scoreActionCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        var score = 0
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score += 500
        if (SEND_BUTTON_LABELS.any { label.startsWith(it) || desc.startsWith(it) }) score += 300
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 220
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 260
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 180
        if (label.isNotBlank() || desc.isNotBlank()) score += 80
        if (childCount == 0) score += 70
        if (childCount > 2) score -= childCount * 40
        val len = (label.ifBlank { desc }).length
        if (len in 1..18) score += 60
        if (len > 28) score -= 180
        if (bounds.right > 0 || bounds.bottom > 0) { score += bounds.right / 10; score += bounds.bottom / 20 }
        return score
    }

    private fun scoreSendActionCandidate(node: AccessibilityNodeInfo): Int {
        var score = scoreActionCandidate(node)
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score += 420
        if (SEND_BUTTON_LABELS.any { label.startsWith(it) || desc.startsWith(it) }) score += 240
        if (SEND_BUTTON_LABELS.any { label.contains(it) || desc.contains(it) }) score += 140
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 260
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 420
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 280
        return score
    }

    private fun scoreAggressiveActionCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) runCatching { node.isVisibleToUser }.getOrDefault(true) else true
        var score = 0
        if (!visible) score += 120
        if (SEND_BUTTON_LABELS.any { label.contains(it) || desc.contains(it) }) score += 240
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 220
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 320
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 260
        return score
    }

    private fun isAggressiveActionCandidate(node: AccessibilityNodeInfo): Boolean {
        val enabled = runCatching { node.isEnabled }.getOrDefault(true)
        if (!enabled) return false
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (editable) return false
        val actionable = runCatching { node.isClickable || node.isFocusable || node.isLongClickable }.getOrDefault(false)
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        return actionable && (SEND_VIEW_ID_HINTS.any { viewId.contains(it) } ||
                SEND_BUTTON_LABELS.any { label.contains(it) || desc.contains(it) } ||
                label.isNotBlank() || desc.isNotBlank())
    }

    @Suppress("DEPRECATION")
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
            if (bounds.width() > 0 && bounds.height() > 0) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                if (cx > 0f && cy > 0f) {
                    val path = Path().apply { moveTo(cx - bounds.width() / 4f, cy - bounds.height() / 4f); lineTo(cx + bounds.width() / 4f, cy + bounds.height() / 4f) }
                    val stroke = GestureDescription.StrokeDescription(path, 0, TAP_GESTURE_DURATION_MS, false)
                    if (runCatching { dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null) }.getOrDefault(false)) {
                        SystemClock.sleep(POST_GESTURE_WAIT_MS)
                        return true
                    }
                }
            }
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false) ||
                performTapGesture(node)
    }

    private fun performTapGesture(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        if (cx <= 0f || cy <= 0f) return false
        val dx = (bounds.width() / 4f).coerceAtLeast(2f)
        val dy = (bounds.height() / 4f).coerceAtLeast(2f)
        repeat(3) { attempt ->
            val offsetX = when (attempt) {
                0 -> 0f
                1 -> dx * 0.5f
                else -> -dx * 0.5f
            }
            val offsetY = when (attempt) {
                0 -> 0f
                1 -> dy * 0.5f
                else -> -dy * 0.5f
            }
            val path = Path().apply { moveTo(cx + offsetX - dx, cy + offsetY - dy); lineTo(cx + offsetX + dx, cy + offsetY + dy) }
            val stroke = GestureDescription.StrokeDescription(path, 0, TAP_GESTURE_DURATION_MS, false)
            if (runCatching { dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null) }.getOrDefault(false)) {
                return true
            }
            SystemClock.sleep(TAP_GESTURE_RETRY_SETTLE_MS)
        }
        return false
    }

    private fun performLongPressGesture(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        if (x <= 0f || y <= 0f) return false
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 120, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }
    // endregion

    // region Send / Verify helpers
    private fun tryImmediateVerifiedSend(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo?, expected: String, alreadyVerified: Boolean = false, skipVerification: Boolean = false): Boolean {
        val verified = skipVerification || alreadyVerified || verifyExpectedInput(root, expected, field) || hasRecentVerifiedInput(expected)
        if (!verified) return false
        val btn = findBestSendButton(root) ?: findPositiveDialogButton(root) ?: findBottomRightActionButton(root)
        if (btn != null && performClick(btn)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val aggressiveBtn = findAggressiveSendActionButton(root)
            if (aggressiveBtn != null && performClick(aggressiveBtn)) return true
        }
        return triggerInputSubmit(root, expected, field)
    }

    private fun tryAggressiveImmediateSubmit(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo?, expected: String, skipVerification: Boolean = false): Boolean {
        if (!skipVerification && !hasRecentVerifiedInput(expected)) return false
        val btn = findBestSendButton(root) ?: findPositiveDialogButton(root) ?: findBottomRightActionButton(root)
        if (btn != null && performClick(btn)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val aggressiveBtn = findAggressiveSendActionButton(root)
            if (aggressiveBtn != null && performClick(aggressiveBtn)) return true
        }
        return triggerInputSubmit(root, expected, field)
    }

    private fun tryDirectImeSubmit(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo?, expected: String): Boolean {
        val target = field ?: findFieldForExpectedValue(root, expected) ?: return false
        return try {
            val text = readFieldText(target)?.trim().orEmpty()
            if (text.isNotBlank() && !matchesExpectedInput(text, expected)) return false
            if (text.isBlank() && !hasRecentVerifiedInput(expected)) return false
            performImeAction(target)
        } finally {
            if (field == null) target.recycle()
        }
    }

    private fun triggerInputSubmit(root: AccessibilityNodeInfo, expected: String, field: AccessibilityNodeInfo?): Boolean {
        val f = field ?: findFieldForExpectedValue(root, expected) ?: return false
        return try {
            val text = readFieldText(f)?.trim().orEmpty()
            if (text.isNotEmpty() && !matchesExpectedInput(text, expected)) return false
            if (text.isBlank() && !hasRecentVerifiedInput(expected)) return false
            performImeAction(f)
        } finally {
            if (field == null) f.recycle()
        }
    }

    private fun performImeAction(node: AccessibilityNodeInfo): Boolean {
        val targets = obtainInputTargets(node)
        try {
            targets.forEach { target ->
                focusInputTarget(target)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    if (supportsAction(target, imeEnter) && runCatching { target.performAction(imeEnter) }.getOrDefault(false)) return true
                }
                val actionMatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { target.actionList?.firstOrNull { normalizeActionLabel(it.label?.toString()) in SEND_BUTTON_LABELS }?.id }.getOrNull()
                } else {
                    null
                }
                if (actionMatch != null && runCatching { target.performAction(actionMatch) }.getOrDefault(false)) return true
                val actionDone = 1 // AccessibilityNodeInfo.ACTION_DONE
                if (supportsAction(target, actionDone) && runCatching { target.performAction(actionDone) }.getOrDefault(false)) return true
                val actionNext = 2 // AccessibilityNodeInfo.ACTION_NEXT
                if (supportsAction(target, actionNext) && runCatching { target.performAction(actionNext) }.getOrDefault(false)) return true
            }
        } finally {
            targets.forEach { it.recycle() }
        }
        return false
    }

    private fun focusInputTarget(node: AccessibilityNodeInfo) {
        if (runCatching { node.isFocused }.getOrDefault(false)) return
        refocusInputTarget(node) || activateInputTarget(node)
    }

    private fun isSendActionNode(node: AccessibilityNodeInfo): Boolean {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        return label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS || SEND_VIEW_ID_HINTS.any { viewId.contains(it) }
    }

    private fun isDismissActionNode(node: AccessibilityNodeInfo): Boolean {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        return label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS || DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }
    }

    private fun hasSendOrOkButton(node: AccessibilityNodeInfo): Boolean {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS || SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (hasSendOrOkButton(child)) { child.recycle(); return true }
            child.recycle()
        }
        return false
    }

    private fun hasDismissButton(node: AccessibilityNodeInfo): Boolean {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS || DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (hasDismissButton(child)) { child.recycle(); return true }
            child.recycle()
        }
        return false
    }

    private fun hasEditableField(node: AccessibilityNodeInfo): Boolean {
        if (supportsDirectInput(node) || isLooseInputCandidate(node)) return true
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (hasEditableField(child)) { child.recycle(); return true }
            child.recycle()
        }
        return false
    }
    // endregion

    // region Signature Learning
    private fun captureSignatureStepIfNeeded(stepIndex: Int, rawStep: String, menu: LinkedHashMap<String, String>?, snapshot: UssdTreeSnapshot, dialogText: String) {
        if (!signatureLearningMode || !rawStep.all(Char::isDigit)) return
        val optionLabel = menu?.get(rawStep).orEmpty()
        if (optionLabel.isBlank() && menu != null) return
        val captured = UssdSignatureStep(
            stepIndex = stepIndex,
            expectedInput = rawStep,
            menuTitle = extractMenuTitle(snapshot.textTokens),
            menuText = formatRecordedDialogText(snapshot.textTokens, dialogText),
            selectedOptionLabel = optionLabel,
            menuOptionsSnapshot = menu?.values?.map { normalizeCollapsedText(it) }?.filter { it.isNotBlank() } ?: emptyList()
        )
        val existing = learnedSignatureSteps.indexOfFirst { it.stepIndex == stepIndex }
        if (existing >= 0) learnedSignatureSteps[existing] = captured else learnedSignatureSteps.add(captured)
    }

    private fun captureLearningDialogIfNeeded(snapshot: UssdTreeSnapshot?, root: AccessibilityNodeInfo, pkg: String) {
        if (snapshot == null) return
        val lower = snapshot.dialogText.lowercase()
        if (!looksLikeUssdDialogFast(lower, pkg)) return
        val menu = parseMenuFromSnapshot(snapshot)
        val recordedText = formatLearningRecordedDialogText(snapshot, menu)
        if (recordedText.isBlank()) return
        val captureIndex = if (currentStep >= advancedSteps.size) advancedSteps.lastIndex else currentStep
        val rawStep = advancedSteps.getOrNull(captureIndex).orEmpty()
        val (entered, selectedLabel) = resolveStepInput(captureIndex, rawStep, menu)
        val capture = UssdLearningCapture(captureIndex, entered, selectedLabel, recordedText)
        val existing = learningCaptures.indexOfFirst { it.stepIndex == capture.stepIndex && normalizeMenuText(it.popupText) == normalizeMenuText(capture.popupText) }
        if (existing >= 0) learningCaptures[existing] = capture else {
            if (learningCaptures.size >= MAX_LEARNING_CAPTURES) learningCaptures.removeAt(0)
            learningCaptures += capture
        }
    }

    private fun extractMenuTitle(tokens: List<String>): String {
        val lines = tokens.map { normalizeCollapsedText(it) }.filter { it.isNotBlank() }
        val menu = parseMenuOptions(lines)
        if (menu != null) {
            val idx = lines.indexOfFirst { MENU_ITEM_REGEX.containsMatchIn(it) }
            if (idx > 0) return lines.take(idx).joinToString(" / ")
        }
        return lines.take(2).joinToString(" / ")
    }

    private fun resolveStepInput(stepIndex: Int, rawStep: String, menu: LinkedHashMap<String, String>?): Pair<String, String> {
        if (rawStep == "INPUT_PHONE") {
            adjustedStepInputs[stepIndex] = rawStep
            return UssdHelper.normalizeRecipientForUssdInput(advancedPhoneNumber) to "Enter phone number"
        }
        if (!rawStep.all(Char::isDigit) || !signatureGuardEnabled) {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to (menu?.get(rawStep) ?: "")
        }
        val learned = getLoadedSignatureContext(stepIndex)
        if (learned == null || menu == null || menu.isEmpty()) {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to (menu?.get(rawStep) ?: "")
        }
        val match = findBestMenuOptionMatch(menu, learned)
        if (match == null) {
            val msg = buildChangeMessage(learned.step, null, null)
            signatureChangeDetected = true
            if (detectedChangeNotes.size >= MAX_DETECTED_CHANGE_NOTES) detectedChangeNotes.removeAt(0)
            detectedChangeNotes += msg
            failForSignatureChange(msg)
            return rawStep to ""
        }
        if (match.first != rawStep) {
            val msg = buildChangeMessage(learned.step, match.first, match.second)
            signatureChangeDetected = true
            if (detectedChangeNotes.size >= MAX_DETECTED_CHANGE_NOTES) detectedChangeNotes.removeAt(0)
            detectedChangeNotes += msg
            if (signatureAction != "ADJUST" || !isAutoAdjustSafe(match, menu, learned)) {
                failForSignatureChange(msg)
                return rawStep to ""
            }
            signatureAutoAdjusted = true
            adjustedStepInputs[stepIndex] = match.first
            return match.first to match.second
        }
        adjustedStepInputs[stepIndex] = rawStep
        return rawStep to match.second
    }

    private fun findBestMenuOptionMatch(menu: LinkedHashMap<String, String>, learned: LearnedSignatureContext): Pair<String, String>? {
        val expectedTokens = learned.selectedLabelTokens
        val menuOptions = menu.entries.map { (key, label) ->
            val normalized = normalizeMenuText(label)
            MenuOptionDescriptor(key, label, normalized, tokenizeMenuLabel(normalized))
        }
        val best = menuOptions.maxByOrNull { desc ->
            val shared = expectedTokens.intersect(desc.tokens).size
            val score = if (shared > 0) (2 * shared.toDouble()) / (expectedTokens.size + desc.tokens.size) else 0.0
            score
        }
        return if (best != null && best.tokens.intersect(expectedTokens).isNotEmpty()) best.key to best.label else null
    }

    private fun getLoadedSignatureContext(stepIndex: Int): LearnedSignatureContext? {
        if (loadedSignatureLookupSource !== loadedSignatureSteps) {
            loadedSignatureLookupSource = loadedSignatureSteps
            loadedSignatureLookup = loadedSignatureSteps.associateBy { it.stepIndex }.mapValues { (_, step) ->
                val normLabel = normalizeMenuText(step.selectedOptionLabel)
                val normTitle = normalizeMenuText(step.menuTitle)
                LearnedSignatureContext(
                    step = step,
                    normalizedSelectedLabel = normLabel,
                    selectedLabelTokens = tokenizeMenuLabel(normLabel),
                    normalizedMenuTitle = normTitle,
                    menuTitleTokens = tokenizeMenuLabel(normTitle),
                    normalizedOptionSnapshot = step.menuOptionsSnapshot.map { normalizeMenuText(it) }.filter { it.isNotBlank() }.toSet()
                )
            }
        }
        return loadedSignatureLookup[stepIndex]
    }

    private fun isAutoAdjustSafe(match: Pair<String, String>, menu: LinkedHashMap<String, String>, learned: LearnedSignatureContext): Boolean {
        // simple heuristic: label similarity must be high
        val label = normalizeMenuText(match.second)
        val learnedLabel = learned.normalizedSelectedLabel
        val tokens = tokenizeMenuLabel(label)
        val learnedTokens = learned.selectedLabelTokens
        val shared = tokens.intersect(learnedTokens).size
        return shared >= minOf(2, learnedTokens.size, tokens.size)
    }

    private fun buildChangeMessage(learned: UssdSignatureStep, actualKey: String?, actualLabel: String?): String {
        val menuLabel = learned.menuTitle.ifBlank { advancedOfferName.ifBlank { "the USSD menu" } }
        return if (actualKey == null) {
            "Detected a menu change in $menuLabel. The learned option '${learned.selectedOptionLabel}' is no longer available as selection ${learned.expectedInput}"
        } else {
            "Detected a menu change in $menuLabel. '${learned.selectedOptionLabel}' moved from option ${learned.expectedInput} to option $actualKey"
        }
    }

    private fun failForSignatureChange(message: String) {
        lastFinalResponse = message
        onDispatchComplete?.invoke(buildDispatchResult(message))
        tokenPurchaseCallback?.invoke(false)
        closeCurrentUssdUi()
        advancedInProgress = false
        updateOverlay()
        cleanupAdvanced()
    }

    private fun isFinalLearningStep(stepIndex: Int) = signatureLearningMode && stepIndex == advancedSteps.lastIndex

    private fun verifyLearningFinalInputThenDismiss(expected: String, attempt: Int) {
        if (attempt >= MAX_VERIFY_ATTEMPTS) {
            if (hasRecentExpectedInput(expected)) finishLearningWithoutFinalSubmission()
            else dismissErrorAndRestart()
            return
        }
        val root = getUssdRoot() ?: run {
            handler.postDelayed({ verifyLearningFinalInputThenDismiss(expected, attempt + 1) }, verificationPollDelay(expected))
            return
        }
        try {
            if (verifyExpectedInput(root, expected)) {
                rememberVerifiedInput(expected)
                finishLearningWithoutFinalSubmission()
            } else {
                if (!hasRecentExpectedInput(expected)) writeValueToField(root, expected)
                handler.postDelayed({ verifyLearningFinalInputThenDismiss(expected, attempt + 1) }, verificationPollDelay(expected))
            }
        } finally {
            root.recycle()
        }
    }

    private fun finishLearningWithoutFinalSubmission() {
        val finalText = lastFinalResponse.ifBlank { "Signature learning captured without submitting final step" }
        currentStep = advancedSteps.size
        isProcessing = false
        clearInputWriteMarkers()
        closeCurrentUssdUi()
        onDispatchComplete?.invoke(buildDispatchResult(finalText))
        advancedInProgress = false
        updateOverlay()
        cleanupAdvanced()
    }

    private fun formatRecordedDialogText(tokens: List<String>, fallback: String): String {
        val lines = tokens.map { normalizeCollapsedText(it) }.filter { it.isNotBlank() }
        val menu = parseMenuOptions(lines)
        return if (menu != null) {
            lines.takeWhile { !MENU_ITEM_REGEX.containsMatchIn(it) }.let { title ->
                (title + menu.entries.map { "${it.key}. ${normalizeCollapsedText(it.value)}" })
            }.joinToString("\n")
        } else fallback
    }

    private fun formatLearningRecordedDialogText(snapshot: UssdTreeSnapshot, menu: LinkedHashMap<String, String>?): String {
        val lines = snapshot.textTokens.map { normalizeCollapsedText(it) }.filter { it.isNotBlank() }
        return if (menu != null && menu.isNotEmpty()) {
            val firstOptionIdx = lines.indexOfFirst { MENU_ITEM_REGEX.containsMatchIn(it) }
            val title = if (firstOptionIdx > 0) lines.take(firstOptionIdx) else emptyList()
            (title + menu.entries.map { "${it.key}. ${normalizeCollapsedText(it.value)}" }).joinToString("\n")
        } else {
            lines.joinToString("\n")
        }
    }
    // endregion

    // region Timeout & Retry
    private fun startStepTimeout() {
        cancelStepTimeout()
        val timeout = Runnable {
            if (shouldExtendStepTimeout()) { startStepTimeout(); return@Runnable }
            val dismissed = closeCurrentUssdUi()
            if (currentStep >= advancedSteps.size) {
                finishAdvancedDispatch(lastFinalResponse)
            } else {
                handler.postDelayed({ restartFromBeginning() }, if (dismissed) DIALOG_DISMISS_SETTLE_MS else 0L)
            }
        }
        stepTimeoutRunnable = timeout
        handler.postDelayed(timeout, currentStepTimeoutMs())
    }

    private fun cancelStepTimeout() {
        stepTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stepTimeoutRunnable = null
    }

    private fun currentStepTimeoutMs(): Long {
        return when {
            pendingPhase != PendingPhase.NONE || pendingStepAdvanceFromKey.isNotBlank() ->
                if (shouldUseExtendedTimeout()) NETWORK_DELAY_PENDING_STEP_TIMEOUT_MS else PENDING_STEP_TIMEOUT_MS
            currentStep >= advancedSteps.size ->
                if (isWaitingOnTransientResponse()) NETWORK_DELAY_FINAL_RESPONSE_TIMEOUT_MS else FINAL_RESPONSE_TIMEOUT_MS
            !hasSeenAdvancedPopup -> STARTUP_STEP_TIMEOUT_MS
            hasRecentUssdUiEvent() -> FINAL_RESPONSE_TIMEOUT_MS
            shouldUseExtendedTimeout() -> NETWORK_DELAY_STEP_TIMEOUT_MS
            else -> STEP_TIMEOUT_MS
        }
    }

    private fun shouldExtendStepTimeout(): Boolean {
        if (!advancedActive) return false
        if (pendingPhase != PendingPhase.NONE || pendingStepAdvanceFromKey.isNotBlank()) return true
        if (!hasSeenAdvancedPopup) {
            return retryWindowStartedAt <= 0L || (SystemClock.elapsedRealtime() - retryWindowStartedAt) <= STARTUP_UI_KEEP_VISIBLE_MS
        }
        if (currentStep >= advancedSteps.size) {
            val norm = normalizeMenuText(lastFinalResponse)
            return norm.isBlank() || isTransientResponse(norm) || hasRecentUssdUiEvent()
        }
        return hasRecentUssdUiEvent() || shouldUseExtendedTimeout()
    }

    private fun shouldUseExtendedTimeout(): Boolean = isWaitingOnTransientResponse() || hasRecentStepAction() || waitingForRootSinceElapsed > 0L
    private fun hasRecentStepAction() = lastStepActionKey.isNotBlank() && SystemClock.elapsedRealtime() - lastStepActionElapsed <= NETWORK_DELAY_ACTION_GRACE_MS
    private fun isWaitingOnTransientResponse() = normalizeMenuText(lastFinalResponse).isNotBlank() && isTransientResponse(normalizeMenuText(lastFinalResponse))
    private fun isTransientResponse(text: String) = TRANSIENT_RESPONSE_HINTS.any { text.contains(it) }

    private fun verificationPollDelay(expected: String): Long {
        return when {
            hasRecentExpectedInput(expected) && hasRecentUssdUiEvent() -> POST_WRITE_VERIFY_POLL_MS
            hasRecentExpectedInput(expected) -> POST_WRITE_VERIFY_POLL_MS
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> RAPID_POST_POPUP_VERIFY_MS
            hasRecentUssdUiEvent() -> FAST_VERIFY_POLL_MS
            else -> VERIFY_POLL_MS
        }
    }

    private fun dismissErrorAndRestart() {
        clearPendingStepAdvance()
        val dismissed = closeCurrentUssdUi()
        if (currentStepRetryCount < MAX_STEP_RETRIES && currentStep < advancedSteps.size) {
            currentStepRetryCount++
            val retryDelay = when (currentStepRetryCount) {
                1 -> 500L
                2 -> 1000L
                3 -> 1500L
                4 -> 2000L
                else -> 2500L
            }
            handler.postDelayed({
                isProcessing = false
                clearPendingAdvance()
                scheduleProcessStep(true)
            }, if (dismissed) maxOf(DIALOG_DISMISS_SETTLE_MS, retryDelay) else retryDelay)
            return
        }
        currentStepRetryCount = 0
        handler.postDelayed({ restartFromBeginning() }, if (dismissed) DIALOG_DISMISS_SETTLE_MS else 0L)
    }

    private fun restartFromBeginning() {
        if (!advancedActive && !advancedInProgress) return
        val now = SystemClock.elapsedRealtime()
        if (retryWindowStartedAt <= 0) retryWindowStartedAt = now
        if (now - retryWindowStartedAt >= MAX_RETRY_WINDOW_MS) {
            val failMsg = if (lastFinalResponse.isNotBlank()) {
                lastFinalResponse
            } else {
                "FAILED after the retry window expired"
            }
            onDispatchComplete?.invoke(buildDispatchResult(failMsg))
            tokenPurchaseCallback?.invoke(false)
            tokenPurchaseCallback = null
            cleanupAdvanced()
            return
        }
        retryCount++
        currentStep = 0
        lastProcessedStep = -1
        isProcessing = false
        lastDialogText = ""
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastUiReturnElapsed = 0L
        lastWindowId = -1
        lastWindowPkg = ""
        lastObservedDialogStateKey = ""
        lastObservedDialogStateChangedElapsed = 0L
        pendingProcessToken = 0L
        clearInputWriteMarkers()
        clearRecentUssdContext()
        clearPendingStepAdvance()
        clearPendingAdvanceKick()
        cancelStepTimeout()
        requestAppUiBehindPopup(force = true)
        updateOverlay()
        redialAdvancedIfNeeded()
        startStepTimeout()
    }

    private fun finishAdvancedDispatch(finalText: String) {
        lastFinalResponse = finalText.ifBlank { lastFinalResponse }
        currentStep = advancedSteps.size
        currentStepRetryCount = 0
        isProcessing = false
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarkers()
        onDispatchComplete?.invoke(buildDispatchResult(lastFinalResponse))
        advancedInProgress = false
        updateOverlay()
        cleanupAdvanced()
    }

    private fun redialAdvancedIfNeeded() {
        val dialCode = advancedDialCode.trim()
        if (dialCode.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedialElapsed < REDIAL_COOLDOWN_MS) return
        lastRedialElapsed = now

        val existingRoot = getUssdRoot()
        if (existingRoot != null) {
            try {
                val pkg = existingRoot.packageName?.toString().orEmpty()
                val text = normalizeCollapsedText(extractAllText(existingRoot))
                val lower = text.lowercase()
                val looksLikeUssd = text.isNotBlank() && !NON_USSD_DIALOG_HINTS.any { lower.contains(it) } &&
                    (looksLikeUssdDialogFast(lower, pkg) || hasDialogLayout(existingRoot))
                if (looksLikeUssd) {
                    Log.d(TAG, "redialAdvancedIfNeeded: USSD dialog already visible, skipping redial")
                    return
                }
            } finally {
                existingRoot.recycle()
            }
        }

        runCatching {
            val intent = com.bingwa.mobile.UssdHelper.buildCallIntent(
                this,
                dialCode,
                preferredDialSubId.takeIf { it >= 0 }
            )
            startActivity(intent)
            val ussdRoot = getUssdRoot()
            if (shouldKeepAppUiVisible() && ussdRoot == null) com.bingwa.mobile.UssdHelper.relaunchAppUi(this)
            ussdRoot?.recycle()
        }
    }

    private fun closeCurrentUssdUi(): Boolean {
        if (dismissCurrentDialog()) return true
        val root = getUssdRoot() ?: return false
        try {
            val pkg = root.packageName?.toString().orEmpty()
            val text = normalizeCollapsedText(extractAllText(root))
            val lower = text.lowercase()
            val looksLikeUssd = text.isNotBlank() && !NON_USSD_DIALOG_HINTS.any { lower.contains(it) } &&
                    (looksLikeUssdDialogFast(lower, pkg) || hasDialogLayout(root))
            if (!looksLikeUssd) return false
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                if (getUssdRoot() != null) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, lowEndDelay(BACK_ACTION_RETRY_DELAY_MS))
            return true
        } finally {
            root.recycle()
        }
    }

    private fun dismissCurrentDialog(): Boolean {
        val root = getUssdRoot() ?: return false
        try {
            val btn = findActionButton(root, DISMISS_BUTTON_LABELS)
            return if (btn != null) performClick(btn) else false
        } finally {
            root.recycle()
        }
    }

    private fun handleIntermediatePopup(
        root: AccessibilityNodeInfo,
        dialogText: String,
        lower: String,
        windowPkg: String
    ): Boolean {
        if (looksLikeSimChooserDialog(lower, windowPkg)) {
            val target = findPreferredSimChooserAction(root) ?: return false
            return try {
                if (!performClick(target)) return false
                lastRelevantEventElapsed = SystemClock.elapsedRealtime()
                lastDialogText = dialogText
                isProcessing = false
                if (advancedActive || isForegroundUiActive()) {
                    requestAppUiBehindPopup(force = true)
                    startKeepingAppUiVisible()
                    updateOverlay()
                }
                if (advancedActive) {
                    scheduleProcessStep(dialogChanged = true, overrideDelay = SIM_CHOOSER_SETTLE_MS)
                }
                true
            } finally {
                target.recycle()
            }
        }
        if (advancedActive && isIntermediateUssdPopup(root, lower, windowPkg)) {
            lastRelevantEventElapsed = SystemClock.elapsedRealtime()
            lastDialogText = dialogText
            isProcessing = false
            if (advancedActive || isForegroundUiActive()) {
                requestAppUiBehindPopup(force = true)
                startKeepingAppUiVisible()
                updateOverlay()
            }
            if (advancedActive) {
                scheduleProcessStep(dialogChanged = true, overrideDelay = INTERMEDIATE_POPUP_SETTLE_MS)
            }
            return true
        }
        return false
    }

    private fun isIntermediateUssdPopup(root: AccessibilityNodeInfo, lower: String, windowPkg: String): Boolean {
        if (windowPkg in BLOCKED_PACKAGES || windowPkg == "android" || windowPkg == "com.android.systemui" ||
            windowPkg.contains("phone", ignoreCase = true) || windowPkg.contains("telecom", ignoreCase = true)
        ) return false
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        if (looksLikeSimChooserDialog(lower, windowPkg)) return false
        val hasUssdLang = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        val menuLike = MENU_ITEM_REGEX.containsMatchIn(lower)
        val isTransient = TRANSIENT_RESPONSE_HINTS.any { lower.contains(it) }
        val hasDialogLayout = hasDialogLayout(root)
        return (hasUssdLang || menuLike || isTransient) && (hasDialogLayout || windowPkg == "android")
    }

    private fun looksLikeSimChooserDialog(lower: String, windowPkg: String): Boolean {
        if (!SIM_CHOOSER_DIALOG_HINTS.any { lower.contains(it) }) return false
        return windowPkg in BLOCKED_PACKAGES || windowPkg == "android" || windowPkg == "com.android.systemui" ||
            windowPkg.contains("phone", ignoreCase = true) || windowPkg.contains("telecom", ignoreCase = true)
    }

    private fun findPreferredSimChooserAction(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        val filtered = candidates.filterNot { isDismissActionNode(it) || isSimChooserDecisionNode(it) }
        val preferred = filtered.maxByOrNull { scoreSimChooserCandidate(it) }
        if (preferred != null && scoreSimChooserCandidate(preferred) >= MIN_SIM_CHOOSER_SCORE) {
            return AccessibilityNodeInfo.obtain(preferred)
        }

        val slotIndex = preferredDialSlotIndex
        if (slotIndex < 0) return null
        val ordered = filtered.sortedWith(compareBy<AccessibilityNodeInfo> {
            Rect().also { bounds -> runCatching { it.getBoundsInScreen(bounds) } }.top
        }.thenBy {
            Rect().also { bounds -> runCatching { it.getBoundsInScreen(bounds) } }.left
        })
        return ordered.getOrNull(slotIndex)?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun scoreSimChooserCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val combined = listOf(label, desc).filter { it.isNotBlank() }.joinToString(" ")
        if (combined.isBlank() && SIM_CHOOSER_VIEW_ID_HINTS.none { viewId.contains(it) }) return Int.MIN_VALUE

        var score = 0
        if (SIM_CHOOSER_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 180
        if (SIM_CHOOSER_OPTION_HINTS.any { combined.contains(it) }) score += 220
        if (combined.length in 3..40) score += 40
        if (runCatching { node.childCount }.getOrDefault(0) == 0) score += 30

        val preferredSlotNumber = preferredDialSlotIndex + 1
        if (preferredSlotNumber > 0) {
            if (simChooserSlotHints(preferredSlotNumber).any { combined.contains(it) }) score += 900
            if (SIM_CHOOSER_OPTION_HINTS.any { combined.contains(it) } &&
                (SIM_SLOT_REGEX(preferredSlotNumber)).containsMatchIn(combined)
            ) score += 420
        }
        return score
    }

    private fun simChooserSlotHints(slotNumber: Int): List<String> {
        val word = when (slotNumber) {
            1 -> "one"
            2 -> "two"
            else -> slotNumber.toString()
        }
        return listOf(
            "sim $slotNumber",
            "sim$slotNumber",
            "slot $slotNumber",
            "slot$slotNumber",
            "line $slotNumber",
            "line$slotNumber",
            "card $slotNumber",
            "card$slotNumber",
            "subscription $slotNumber",
            "subscription$slotNumber",
            "sim $word",
            "slot $word",
            "line $word",
            "card $word"
        )
    }

    private fun isSimChooserDecisionNode(node: AccessibilityNodeInfo): Boolean {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        return SIM_CHOOSER_DECISION_LABELS.any { label.contains(it) || desc.contains(it) }
    }

    private fun findActionButton(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            val exact = findButtonExact(root, label)
            if (exact != null) return exact
        }
        for (label in labels) {
            val contains = findButtonContaining(root, label)
            if (contains != null) return contains
        }
        return null
    }

    private fun findButtonExact(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val norm = normalizeActionLabel(text)
        if (normalizeActionLabel(node.text?.toString()) == norm || normalizeActionLabel(node.contentDescription?.toString()) == norm) {
            return obtainClickableAncestor(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val res = findButtonExact(child, text)
            if (res != null) { child.recycle(); return res }
            child.recycle()
        }
        return null
    }

    private fun findButtonContaining(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val norm = normalizeActionLabel(text)
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        if ((label.contains(norm) || desc.contains(norm)) && norm.length > 2) {
            return obtainClickableAncestor(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val res = findButtonContaining(child, text)
            if (res != null) { child.recycle(); return res }
            child.recycle()
        }
        return null
    }

    private fun obtainClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 6) {
            if (runCatching { current.isClickable || current.isFocusable }.getOrDefault(false)) {
                return current
            }
            val parent = try { current.parent } catch (_: Exception) { null }
            current.recycle()
            current = parent
            depth++
        }
        current?.recycle()
        return AccessibilityNodeInfo.obtain(node)
    }
    // endregion

    // region Cleanup
    private fun cleanupAdvanced() {
        stopKeepingAppUiVisible()
        cancelStepTimeout()
        processStepRunnable?.let { handler.removeCallbacks(it) }
        processStepRunnable = null
        pendingAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingAdvanceKickRunnable = null
        pendingStepAdvanceTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceTimeoutRunnable = null
        pendingStepAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceKickRunnable = null
        uiKeepVisibleRunnable?.let { handler.removeCallbacks(it) }
        uiKeepVisibleRunnable = null
        currentStep = 0
        advancedSteps = emptyList()
        advancedPhoneNumber = ""
        advancedDialCode = ""
        advancedOfferId = -1
        advancedOfferName = ""
        preferredDialSubId = -1
        preferredDialSlotIndex = -1
        retryWindowStartedAt = 0L
        advancedActive = false
        advancedInProgress = false
        UssdNavigationService.isUsdExecutionLocked = false
        isProcessing = false
        retryCount = 0
        lastRedialElapsed = 0L
        signatureGuardEnabled = false
        signatureAction = "STOP"
        signatureLearningMode = false
        loadedSignatureSteps = emptyList()
        lastDialogText = ""
        lastFinalResponse = ""
        pendingProcessToken = 0L
        lastWindowPkg = ""
        lastWindowId = -1
        lastRelevantEventElapsed = 0L
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastUiReturnElapsed = 0L
        lastObservedDialogStateKey = ""
        lastObservedDialogStateChangedElapsed = 0L
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarkers()
        clearRecentUssdContext()
        hideOverlay()
        onDispatchComplete = null
        tokenPurchaseCallback = null
        adjustedStepInputs.clear()
        learnedSignatureSteps.clear()
        learningCaptures.clear()
        popupTranscript.clear()
        lastTranscriptEntryKey = ""
        detectedChangeNotes.clear()
        signatureChangeDetected = false
        signatureAutoAdjusted = false
        foregroundUiActive = false
        foregroundUiUntilElapsed = 0L
        hasSeenAdvancedPopup = false
        hasSeenForegroundPopup = false
        uiReturnSuppressed = false
    }

    private fun clearCallbacks() {
        balanceCallback = null
        tokenPurchaseCallback = null
        onDispatchComplete = null
    }

    private fun buildDispatchResult(finalResponse: String): AdvancedDispatchResult {
        return AdvancedDispatchResult(
            finalResponse = finalResponse,
            changeDetected = signatureChangeDetected,
            autoAdjusted = signatureAutoAdjusted,
            learningCompleted = signatureLearningMode && learnedSignatureSteps.isNotEmpty(),
            suggestedCode = if (signatureChangeDetected) buildSuggestedCode() else "",
            changeSummary = detectedChangeNotes.joinToString(". "),
            learnedSignature = learnedSignatureSteps.toList(),
            learningCaptures = learningCaptures.toList(),
            popupTranscript = popupTranscript.toList()
        )
    }

    private fun buildSuggestedCode(): String {
        val dialBase = advancedDialCode.trim().replace("%23", "").trimEnd('#')
        val steps = advancedSteps.mapIndexed { idx, step ->
            when {
                step == "INPUT_PHONE" -> "pn"
                adjustedStepInputs[idx].isNullOrBlank() -> step
                else -> adjustedStepInputs[idx] ?: step
            }
        }
        return if (steps.isEmpty()) "$dialBase#" else "$dialBase*${steps.joinToString("*")}#"
    }
    // endregion

    // region Context / Root helpers
    private fun shouldRequireStrictPopupScope(): Boolean {
        if (signatureLearningMode) return true
        if (!advancedActive) return false
        if (pendingPhase != PendingPhase.NONE || !pendingExpectedValue.isNullOrBlank()) return true
        val step = advancedSteps.getOrNull(currentStep).orEmpty()
        return step == "INPUT_PHONE" || (step.isNotBlank() && !step.all(Char::isDigit))
    }

    private fun rememberRecentUssdContext(root: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot?, windowId: Int, pkg: String, text: String, strict: Boolean) {
        recentUssdRoot?.let { runCatching { it.recycle() } }
        recentUssdRoot = AccessibilityNodeInfo.obtain(root)
        recentUssdSnapshot = snapshot
        recentUssdWindowId = windowId
        recentUssdWindowPkg = pkg
        recentUssdDialogText = text
        recentUssdStrictDialog = strict
        recentUssdCapturedElapsed = SystemClock.elapsedRealtime()
        recordPopupTranscript(windowId, pkg, snapshot, text)
    }

    private fun obtainRecentUssdContext(requireStrict: Boolean = false): RecentUssdContext? {
        if (!hasFreshRecentUssdContext(requireStrict)) return null
        val root = recentUssdRoot ?: return null
        return RecentUssdContext(
            root = AccessibilityNodeInfo.obtain(root),
            snapshot = recentUssdSnapshot,
            windowId = recentUssdWindowId,
            windowPkg = recentUssdWindowPkg,
            dialogText = recentUssdDialogText,
            strictDialog = recentUssdStrictDialog
        )
    }

    private fun hasFreshRecentUssdContext(requireStrict: Boolean = false): Boolean {
        if (recentUssdCapturedElapsed <= 0) return false
        if (SystemClock.elapsedRealtime() - recentUssdCapturedElapsed > RECENT_USSD_CONTEXT_WINDOW_MS) return false
        if (requireStrict && !recentUssdStrictDialog) return false
        return recentUssdRoot != null
    }

    private fun clearRecentUssdContext() {
        recentUssdRoot?.let { runCatching { it.recycle() } }
        recentUssdRoot = null
        recentUssdSnapshot = null
        recentUssdWindowId = -1
        recentUssdWindowPkg = ""
        recentUssdDialogText = ""
        recentUssdStrictDialog = false
        recentUssdCapturedElapsed = 0L
    }

    private fun recordPopupTranscript(windowId: Int, pkg: String, snapshot: UssdTreeSnapshot?, fallbackText: String) {
        val entry = buildPopupTranscriptEntry(snapshot, fallbackText) ?: return
        val key = "$windowId|$pkg|${normalizeMenuText(entry)}"
        if (key.isBlank() || key == lastTranscriptEntryKey) return
        lastTranscriptEntryKey = key
        popupTranscript += entry
        if (popupTranscript.size > MAX_POPUP_TRANSCRIPT_ENTRIES) {
            popupTranscript.removeAt(0)
        }
    }

    private fun buildPopupTranscriptEntry(snapshot: UssdTreeSnapshot?, fallbackText: String): String? {
        val entry = snapshot?.let { formatPopupTranscriptEntry(it) }
            ?.takeIf { it.isNotBlank() }
            ?: normalizeCollapsedText(fallbackText).takeIf { it.isNotBlank() }
        return entry?.take(MAX_POPUP_TRANSCRIPT_CHARS)
    }

    private fun formatPopupTranscriptEntry(snapshot: UssdTreeSnapshot): String {
        val lines = snapshot.textTokens.map { normalizeCollapsedText(it) }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return snapshot.dialogText
        val menu = parseMenuOptions(lines)
        val recorded = if (menu != null) {
            val titleLines = lines.takeWhile { !MENU_ITEM_REGEX.containsMatchIn(it) }
            (titleLines + menu.entries.map { "${it.key}. ${normalizeCollapsedText(it.value)}" }).distinct()
        } else {
            lines.distinct()
        }
        return recorded.joinToString("\n").ifBlank { snapshot.dialogText }
    }

    private fun rememberObservedDialogState(
        windowId: Int,
        windowPkg: String,
        dialogText: String,
        snapshot: UssdTreeSnapshot?
    ) {
        val normalized = buildDialogStateKey(dialogText, snapshot?.inputStateSignature.orEmpty())
            .ifBlank { normalizeCollapsedText(dialogText) }
        if (normalized.isBlank()) return
        val stateKey = "$windowId|$windowPkg|$normalized"
        if (stateKey != lastObservedDialogStateKey) {
            lastObservedDialogStateKey = stateKey
            lastObservedDialogStateChangedElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun popupStabilityRemainingMs(): Long {
        if (lastObservedDialogStateChangedElapsed <= 0L) return 0L
        val fastReady = isRecentPopupReadyForFastProcessing()
        val networkDelay = when {
            shouldUseExtendedTimeout() -> 2.0f
            !hasSeenAdvancedPopup -> 1.4f
            else -> 1.0f
        }
        val lowEndFactor = if (lowEndDevice) 1.5f else 1.0f
        val requiredStableMs = when {
            !hasSeenAdvancedPopup && fastReady -> (STARTUP_FAST_POPUP_STABILITY_DELAY_MS * networkDelay * lowEndFactor).toLong()
            !hasSeenAdvancedPopup -> (STARTUP_POPUP_STABILITY_DELAY_MS * networkDelay * lowEndFactor).toLong()
            shouldUseExtendedTimeout() && fastReady -> (WEAK_NETWORK_FAST_POPUP_STABILITY_DELAY_MS * networkDelay * lowEndFactor).toLong()
            shouldUseExtendedTimeout() -> (WEAK_NETWORK_POPUP_STABILITY_DELAY_MS * networkDelay * lowEndFactor).toLong()
            fastReady -> FAST_POPUP_STABILITY_DELAY_MS
            else -> POPUP_STABILITY_DELAY_MS
        }
        val elapsed = SystemClock.elapsedRealtime() - lastObservedDialogStateChangedElapsed
        return (requiredStableMs - elapsed).coerceAtLeast(0L)
    }

    private fun isRecentPopupReadyForFastProcessing(): Boolean {
        val snapshot = recentUssdSnapshot ?: return false
        if (snapshot.dialogText.isBlank()) return false
        if (snapshot.hasEditableField && snapshot.hasSendButton) return true
        if (snapshot.hasSendButton && dialogSuggestsTypedReplyPrompt(snapshot.dialogText.lowercase())) return true
        val menu = parseMenuFromSnapshot(snapshot)
        if (menu != null && menu.size >= 2) return true
        return snapshot.hasEditableField || snapshot.hasSendButton
    }

    private fun shouldWaitForRootRecovery(): Boolean {
        return advancedActive && (
            hasSeenAdvancedPopup || pendingPhase != PendingPhase.NONE || pendingStepAdvanceFromKey.isNotBlank() ||
                hasRecentUssdUiEvent() || currentStep == 0
        )
    }

    private fun waitForRootRecovery() {
        if (waitingForRootSinceElapsed == 0L) waitingForRootSinceElapsed = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - waitingForRootSinceElapsed > rootReacquireTimeout()) {
            clearRootRecoveryState()
            if (signatureLearningMode) {
                finishLearningWithoutFinalSubmission()
            } else {
                handler.post { dismissErrorAndRestart() }
            }
            return
        }
        pendingProcessToken = SystemClock.elapsedRealtime()
        scheduleProcessStep(false, ROOT_REACQUIRE_RETRY_DELAY_MS)
    }

    private fun clearRootRecoveryState() { waitingForRootSinceElapsed = 0L }
    private fun rootReacquireTimeout(): Long = if (shouldUseExtendedTimeout()) NETWORK_DELAY_ROOT_REACQUIRE_TIMEOUT_MS else ROOT_REACQUIRE_TIMEOUT_MS

    private fun hasRecentUssdUiEvent(): Boolean = SystemClock.elapsedRealtime() - lastRelevantEventElapsed <= RECENT_UI_EVENT_GRACE_MS

    private fun hasRecentExpectedInput(value: String): Boolean {
        val norm = normalizeInputValue(value)
        return lastInputWriteValue == norm && SystemClock.elapsedRealtime() - lastInputWriteElapsed <= RECENT_INPUT_GRACE_MS
    }

    private fun hasRecentVerifiedInput(value: String): Boolean {
        val norm = normalizeInputValue(value)
        return lastVerifiedInputValue == norm && SystemClock.elapsedRealtime() - lastVerifiedInputElapsed <= RECENT_VERIFIED_INPUT_GRACE_MS
    }

    private fun rememberInputWrite(value: String) {
        lastInputWriteValue = normalizeInputValue(value)
        lastInputWriteElapsed = SystemClock.elapsedRealtime()
    }

    private fun rememberVerifiedInput(value: String) {
        val norm = normalizeInputValue(value)
        if (norm.isNotBlank()) {
            lastVerifiedInputValue = norm
            lastVerifiedInputElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun clearInputWriteMarkers() {
        lastInputWriteValue = ""
        lastInputWriteElapsed = 0L
        lastVerifiedInputValue = ""
        lastVerifiedInputElapsed = 0L
    }

    private fun shouldForcePendingFieldRewrite(expected: String, fieldPresent: Boolean): Boolean {
        if (fieldPresent) return true
        if (!hasRecentExpectedInput(expected)) return true
        return shouldUseExtendedTimeout() && !hasRecentVerifiedInput(expected)
    }

    private fun shouldTrustFreshWrite(wrote: Boolean, expected: String, field: AccessibilityNodeInfo?, snapshot: UssdTreeSnapshot, lower: String): Boolean {
        return wrote && hasRecentVerifiedInput(expected) && (field != null || snapshot.hasSendButton)
    }

    private fun shouldAttemptAggressiveImmediateSubmit(snapshot: UssdTreeSnapshot, lower: String, step: String, expected: String, field: AccessibilityNodeInfo?): Boolean {
        return hasRecentVerifiedInput(expected) && snapshot.hasSendButton && (field != null || dialogSuggestsTypedReplyPrompt(lower))
    }

    private fun shouldTreatAsTextInput(step: String, value: String, selectedLabel: String?): Boolean {
        return step == "INPUT_PHONE" || (value.isNotBlank() && (!value.all(Char::isDigit) || value.length >= 4 || (selectedLabel == null && value.length > 1)))
    }

    private fun shouldTreatNumericReplyAsTextInput(step: String, value: String, snapshot: UssdTreeSnapshot, lower: String, menu: LinkedHashMap<String, String>?): Boolean {
        if (step == "INPUT_PHONE") return true
        if (value.isBlank() || !value.all(Char::isDigit)) return false
        if (!snapshot.hasSendButton) return false
        if (snapshot.hasEditableField) return true
        if (menu != null && menu.isNotEmpty()) return false
        return dialogSuggestsTypedReplyPrompt(lower)
    }

    private fun dialogSuggestsTextInput(lower: String) =
        lower.contains("enter") || lower.contains("input") || lower.contains("reply") ||
                lower.contains("amount") || lower.contains("pin") || lower.contains("phone") ||
                lower.contains("number") || lower.contains("code")

    private fun dialogSuggestsTypedReplyPrompt(lower: String) =
        dialogSuggestsTextInput(lower) || lower.contains("select") || lower.contains("choose") ||
                lower.contains("option") || lower.contains("press") || lower.contains("respond") ||
                lower.contains("response") || lower.contains("continue")

    private fun dialogSuggestsPhoneInput(lower: String) =
        PHONE_INPUT_HINTS.any { lower.contains(it) } ||
                (lower.contains("254") && (lower.contains("phone") || lower.contains("mobile"))) ||
                (lower.contains("07") && (lower.contains("phone") || lower.contains("number"))) ||
                lower.contains("tel:") || lower.contains("call") || lower.contains("dial") ||
                lower.contains("recipient") || lower.contains("subscriber") ||
                lower.contains("msisdn") || lower.contains("beneficiary") ||
                (PHONE_NUMBER_REGEX.containsMatchIn(lower) && lower.contains("phone"))

    private fun markStepAction(dialogText: String, root: AccessibilityNodeInfo?, snapshot: UssdTreeSnapshot?) {
        val sig = snapshot?.inputStateSignature ?: root?.let { captureInputStateSignature(it) }.orEmpty()
        lastStepActionKey = buildDialogStateKey(dialogText, sig)
        lastStepActionElapsed = SystemClock.elapsedRealtime()
    }

    private fun captureInputStateSignature(root: AccessibilityNodeInfo): String {
        val field = findEditableField(root) ?: return ""
        return try { buildInputNodeSignature(field) } finally { field.recycle() }
    }

    private fun findEditableField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectTextEntryCandidates(root, candidates)
        return candidates.maxByOrNull { scoreTextEntryCandidate(it) }?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun buildInputNodeSignature(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString().orEmpty().substringAfterLast('.')
        val viewId = normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) normalizeActionLabel(runCatching { node.hintText?.toString() }.getOrNull()) else ""
        val text = normalizeCollapsedText(readFieldText(node))
        val valueSig = if (text.isBlank()) "_" else if (isLikelyPromptText(text)) "prompt:${normalizeActionLabel(text).take(24)}" else "value:${normalizeInputValue(text).takeLast(20)}"
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        val role = if (runCatching { node.isEditable }.getOrDefault(false)) "editable" else if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) "settext" else "candidate"
        return "$cls|$viewId|$hint|$valueSig|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|$role"
    }

    private fun buildScreenSignatureKey(step: Int, windowId: Int, pkg: String, root: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot?, text: String): String {
        val cls = root.className?.toString().orEmpty()
        val flags = snapshot?.let { "${it.hasEditableField}|${it.hasSendButton}|${it.hasDismissButton}|${it.inputStateSignature}" }.orEmpty()
        return "$step|$windowId|$pkg|$cls|$flags|${normalizeCollapsedText(text)}"
    }

    private fun buildTransitionSignatureKey(root: AccessibilityNodeInfo, text: String, snapshot: UssdTreeSnapshot?): String {
        val cls = root.className?.toString().orEmpty()
        val flags = snapshot?.let { "${it.hasEditableField}|${it.hasSendButton}|${it.hasDismissButton}|${it.inputStateSignature}" }.orEmpty()
        return "${root.windowId}|${root.packageName?.toString().orEmpty()}|$cls|$flags|${normalizeCollapsedText(text)}"
    }

    private fun buildStepAdvanceSignatureKey(root: AccessibilityNodeInfo, text: String, snapshot: UssdTreeSnapshot?): String {
        val cls = root.className?.toString().orEmpty()
        val flags = snapshot?.let { "${it.hasEditableField}|${it.hasSendButton}|${it.hasDismissButton}|${it.inputStateSignature}" }.orEmpty()
        val menuFingerprint = snapshot?.let { parseMenuFromSnapshot(it) }?.entries?.joinToString(";") { "${it.key}:${normalizeMenuText(it.value)}" }.orEmpty()
        val stepFingerprint = "$currentStep|${normalizeInputValue(pendingExpectedValue)}"
        return "$stepFingerprint|${root.windowId}|${root.packageName?.toString().orEmpty()}|$cls|$flags|${normalizeCollapsedText(text)}|$menuFingerprint"
    }

    private fun extractAllText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun dfs(node: AccessibilityNodeInfo) {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(' ') }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(' ') }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                dfs(child)
                child.recycle()
            }
        }
        dfs(root)
        return sb.toString()
    }

    private fun handleCallbackDialogs(lower: String, dialogText: String) {
        tokenPurchaseCallback?.let { cb ->
            when {
                lower.contains("insufficient") || lower.contains("failed") || lower.contains("cancelled") -> {
                    cb(false); closeCurrentUssdUi(); clearCallbacks()
                }
                lower.contains("you have transferred") || (lower.contains("transfer") && lower.contains("successful")) -> {
                    cb(true); closeCurrentUssdUi(); clearCallbacks()
                }
                else -> Unit
            }
        }

        balanceCallback?.let { cb ->
            if (lower.contains("balance") || lower.contains("airtime") || lower.contains("ksh") || lower.contains("kes")) {
                airtimeBalance = dialogText
                val display = BalanceChecker.parseBalanceDisplay(dialogText)
                val balance = BalanceChecker.parseBalanceInt(dialogText)
                if (display.isNotBlank() && display.startsWith("KSh", ignoreCase = true)) {
                    BalanceChecker.currentBalance = balance
                    BalanceChecker.persistLastKnownBalance(applicationContext, display)
                    cb(display)
                    closeCurrentUssdUi()
                    clearCallbacks()
                }
            }
        }
    }
    // endregion

    // region Foreground UI & Overlay
    private fun isForegroundUiActive(): Boolean = foregroundUiActive && SystemClock.elapsedRealtime() < foregroundUiUntilElapsed
    private fun refreshForegroundUi() { if (foregroundUiActive) foregroundUiUntilElapsed = SystemClock.elapsedRealtime() + 35000L }
    private fun disarmForegroundUi() { foregroundUiActive = false; foregroundUiUntilElapsed = 0L }
    private fun shouldKeepAppUiVisible(): Boolean = keepAppUiVisibleEnabled && !uiReturnSuppressed && (advancedActive || isForegroundUiActive())

    private fun requestAppUiBehindPopup(force: Boolean = false) {
        if (!shouldKeepAppUiVisible()) return
        val ussdRoot = getUssdRoot()
        if (ussdRoot != null) {
            ussdRoot.recycle()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUiReturnElapsed < 900L) return
        lastUiReturnElapsed = now
        UssdHelper.relaunchAppUi(this, delayMs = if (force) 60L else 120L)
    }

    private fun startKeepingAppUiVisible() {
        uiKeepVisibleRunnable?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (foregroundUiActive && !isForegroundUiActive()) {
                    disarmForegroundUi()
                    hasSeenForegroundPopup = false
                    updateOverlay()
                }
                val startupWindow = advancedActive && !hasSeenAdvancedPopup &&
                        (SystemClock.elapsedRealtime() - lastRelevantEventElapsed) <= STARTUP_UI_KEEP_VISIBLE_MS
                val canKeep = shouldKeepAppUiVisible() && (startupWindow || (hasSeenAdvancedPopup && hasRecentUssdUiEvent()))
                if (!canKeep) { uiKeepVisibleRunnable = null; return }
                requestAppUiBehindPopup(force = true)
                handler.postDelayed(this, UI_KEEP_VISIBLE_INTERVAL_MS)
            }
        }
        uiKeepVisibleRunnable = task
        handler.postDelayed(task, UI_KEEP_VISIBLE_INTERVAL_MS)
    }

    private fun stopKeepingAppUiVisible() {
        uiKeepVisibleRunnable?.let { handler.removeCallbacks(it) }
        uiKeepVisibleRunnable = null
    }

    private fun updateOverlay() {
        if (!SHOW_RUNNING_OVERLAY) { hideOverlay(); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        val ussdRoot = getUssdRoot()
        if (ussdRoot != null) {
            ussdRoot.recycle()
            hideOverlay()
            return
        }
        val wm = windowManager ?: return
        if (overlayView == null) {
            val view = buildOverlayView()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(18) }
            runCatching { wm.addView(view, params) }.onFailure { Log.w(TAG, "Overlay add failed", it) }
            overlayView = view
        }
        overlayStatusText?.text = buildOverlayStatus()
        overlayDetailText?.text = buildOverlayDetail()
        overlayView?.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        val wm = windowManager ?: return
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
        overlayStatusText = null
        overlayDetailText = null
    }

    private fun buildOverlayView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(10); val padV = dp(6)
            setPadding(pad, padV, pad, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#661A1A1A"))
                setStroke(dp(1), Color.parseColor("#2929B6F6"))
            }
            elevation = dp(4).toFloat()
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val detail = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD7E3F4")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val progress = ProgressBar(this).apply { isIndeterminate = true; alpha = 0.9f }
        container.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        container.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(6) })
        overlayStatusText = status
        overlayDetailText = detail
        return container
    }

    private fun buildOverlayStatus(): String = when {
        retryCount > 0 -> "Bingwa USSD retrying"
        !hasSeenAdvancedPopup -> "Opening USSD"
        currentStep >= advancedSteps.size -> "Finishing USSD"
        else -> "USSD running"
    }

    private fun buildOverlayDetail(): String {
        val parts = mutableListOf<String>()
        advancedOfferName.takeIf { it.isNotBlank() }?.let { parts += it }
        when {
            advancedSteps.isEmpty() -> parts += "Waiting for network menu"
            currentStep >= advancedSteps.size -> parts += "Finalizing response"
            advancedSteps.getOrNull(currentStep) == "INPUT_PHONE" -> parts += "Step ${currentStep+1}/${advancedSteps.size}, entering phone"
            else -> parts += "Step ${currentStep+1}/${advancedSteps.size}"
        }
        if (retryCount > 0) parts += "Retry $retryCount"
        return parts.joinToString("  |  ").ifBlank { "USSD session active" }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    // endregion

    // region Notification & Foreground Service
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "USSD Automation", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bingwa Mobile")
            .setContentText("USSD automation active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }
    // endregion

    // region Data classes for signature & internal use
    // NOTE: UssdTreeSnapshot is now defined only once above (in the Snapshot & Parsing region).
    // The duplicate has been removed.
    private data class MenuOptionDescriptor(
        val key: String,
        val label: String,
        val normalizedLabel: String,
        val tokens: Set<String>
    )
    private data class LearnedSignatureContext(
        val step: UssdSignatureStep,
        val normalizedSelectedLabel: String,
        val selectedLabelTokens: Set<String>,
        val normalizedMenuTitle: String,
        val menuTitleTokens: Set<String>,
        val normalizedOptionSnapshot: Set<String>
    )
    private data class RecentUssdContext(
        val root: AccessibilityNodeInfo,
        val snapshot: UssdTreeSnapshot?,
        val windowId: Int,
        val windowPkg: String,
        val dialogText: String,
        val strictDialog: Boolean
    )
    private data class InputWriteResult(val wroteValue: Boolean, val likelyVerified: Boolean)
    private enum class PendingPhase { NONE, WAIT_VERIFY, WAIT_SEND }
    // endregion

    // region Constants (kept from original, expanded)
    private val USSD_PACKAGES = setOf(
        "com.android.phone", "com.android.server.telecom", "com.google.android.dialer",
        "com.samsung.android.incallui", "com.samsung.android.app.telephonyui",
        "com.samsung.android.dialer", "com.android.incallui", "com.android.dialer",
        "com.mediatek.phone", "com.transsion.phone", "com.infinix.phone",
        "com.tecno.phone", "com.itel.phone", "com.transsion.incallui",
        "com.mediatek.incallui", "com.android.contacts", "com.huawei.contacts",
        "com.huawei.incallui", "com.vivo.contacts", "com.vivo.dialer",
        "com.hihonor.dialer", "com.heytap.dialer", "com.coloros.dialer",
        "com.oplus.dialer", "com.oneplus.dialer", "com.realme.dialer",
        "com.miui.securitycenter", "com.miui.phone", "com.android.mms",
        "com.google.android.apps.tachyon", "com.sprd.contacts",
        "com.android.phone.htcdialer", "com.lge.phone", "com.sonyericsson.phone",
        "com.asus.phone", "com.nokia.phone", "com.bbk.phone", "com.iqoo.phone",
        "com.motorola.phone", "com.oneplus.phone", "com.realme.phone"
    )
    private val USSD_PACKAGE_HINTS = listOf(
        "phone", "dialer", "telecom", "incall", "callui", "telephony", "ussd",
        "miui", "coloros", "heytap", "oplus", "honor", "transsion", "vivo", "realme",
        "samsung", "huawei", "infinix", "tecno", "itel", "mediatek", "sprd"
    )
    private val BLOCKED_PACKAGES = setOf(
        "com.bingwa.mobile", "com.android.systemui", "com.android.launcher",
        "com.google.android.apps.nexuslauncher", "com.miui.home",
        "com.sec.android.app.launcher", "com.huawei.android.launcher",
        "com.android.settings", "com.google.android.gms", "com.android.keyguard",
        "com.android.packageinstaller"
    )
    private val LAUNCHER_PACKAGES = setOf(
        "com.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.miui.home", "com.sec.android.app.launcher", "com.huawei.android.launcher"
    )
    private val USSD_HINTS = listOf(
        "enter", "ussd", "choose", "select", "option", "menu", "number", "amount",
        "sambaza", "tuma", "please enter", "enter phone", "enter amount",
        "safaricom", "airtel", "telkom", "faiba", "reply", "continue", "submit",
        "balance", "airtime", "ksh", "kes", "bundle", "data", "account", "pin",
        "send money", "confirm", "retry", "proceed", "voucher", "recipient", "mobile",
        "entrez", "montant", "numéro", "solde", "continuer", "confirmer",
        "أدخل", "رصيد", "تأكيد", "press", "dial", "call", "response", "respond", "tap",
        "wait", "loading", "processing", "requesting", "invalid", "failed", "error",
        "transferred", "purchased", "activated", "successful", "completed"
    )
    private val SEND_BUTTON_LABELS = listOf(
        "send", "ok", "tuma", "call", "sambaza", "enda", "confirm",
        "reply", "next", "continue", "submit", "proceed", "accept", "yes", "done",
        "confirmar", "envoyer", "suivant", "continuer", "oui", "go", "enter", "dial",
        "execute", "موافق", "إرسال", "senden", "absenden", "invia", "invia sms",
        "verzenden", "odsłanie", "wyśl", "odeslat", "odeslat sms", "send message",
        "send ussd", "run", "start", "activate", "subscribe", "buy", "purchase"
    )
    private val SEND_VIEW_ID_HINTS = listOf(
        "send", "submit", "reply", "continue", "next", "confirm", "positive", "ok",
        "button1", "positivebutton", "positive_button", "dialog_button", "send_button",
        "action_button", "btn_ok", "btn_confirm", "btn_send", "btn_positive",
        "alertdialog_button", "right_button", "primary_button"
    )
    private val INPUT_VIEW_ID_HINTS = listOf(
        "input", "reply", "entry", "message", "ussd", "number", "phone", "amount", "pin",
        "edit", "text", "answer", "field", "value", "data", "query", "response"
    )
    private val INPUT_FIELD_HINTS = listOf(
        "enter", "input", "reply", "phone", "number", "amount", "pin", "account", "mobile", "recipient",
        "text", "answer", "value", "type here", "write here"
    )
    private val PHONE_INPUT_HINTS = listOf(
        "phone", "phone number", "number", "mobile", "mobile number", "recipient", "recipient number",
        "customer", "customer number", "subscriber", "subscriber number", "beneficiary",
        "beneficiary number", "msisdn", "tel", "telephone", "contact", "line number",
        "enter phone", "enter number", "enter mobile", "enter recipient", "enter customer"
    )
    private val DISMISS_BUTTON_LABELS = listOf(
        "ok", "cancel", "close", "dismiss", "back", "no", "exit", "annuler", "fermer",
        "non", "إلغاء", "خروج"
    )
    private val DISMISS_VIEW_ID_HINTS = listOf(
        "cancel", "dismiss", "close", "negative", "back", "no", "exit",
        "button2", "negativebutton", "negative_button", "btn_cancel", "btn_dismiss",
        "btn_negative", "left_button"
    )
    private val NON_USSD_DIALOG_HINTS = listOf(
        "choose sim", "select sim", "sim 1", "sim 2", "sim1", "sim2", "default sim",
        "complete action", "use by default", "just once", "always",
        "allow", "deny", "permission", "grant", "not now",
        "isn't responding", "is not responding", "stopped", "keeps stopping", "close app"
    )
    private val SIM_CHOOSER_DIALOG_HINTS = listOf(
        "choose sim", "select sim", "select card", "default sim", "pick sim",
        "sim 1", "sim 2", "sim1", "sim2", "slot 1", "slot 2", "line 1", "line 2"
    )
    private val SIM_CHOOSER_OPTION_HINTS = listOf(
        "sim", "slot", "line", "card", "subscription"
    )
    private val SIM_CHOOSER_VIEW_ID_HINTS = listOf(
        "sim", "slot", "subscription", "account", "phone"
    )
    private val SIM_CHOOSER_DECISION_LABELS = listOf(
        "always", "just once", "once", "use by default"
    )
    private val TRANSIENT_RESPONSE_HINTS = listOf(
        "ussd running", "running", "processing", "please wait", "wait", "loading",
        "requesting", "sending", "fetching", "working", "in progress"
    )
    private val EDITABLE_CLASS_HINTS = listOf(
        "EditText", "TextInputEditText", "AutoCompleteTextView",
        "MultiAutoCompleteTextView", "ExtractEditText",
        "com.samsung.android.widget.SamsungEditText", "android.widget.EditText",
        "com.miui.widget.EditText", "com.xiaomi.widget.EditText",
        "com.huawei.widget.EditText", "com.huawei.android.widget.EditText",
        "com.oppo.widget.EditText", "com.coloros.widget.EditText",
        "com.vivo.widget.EditText", "com.oneplus.widget.EditText",
        "com.realme.widget.EditText", "com.oplus.widget.EditText",
        "com.android.widget.EditText", "android.widget.TextView",
        "com.android.internal.widget.EditText", "com.transsion.widget.EditText",
        "com.infinix.widget.EditText", "com.tecno.widget.EditText",
        "com.itel.widget.EditText", "com.samsung.android.widget.EditText",
        "com.samsung.android.widget.SamsungInputMethod", "android.widget.EditText"
    )
    private val errorKeywords = listOf(
        "connection problem", "invalid mmi", "mmi code", "network error", "invalid", "failed",
        "cancelled", "try again", "unavailable", "problem", "request timeout",
        "busy", "sim error", "not available", "service unavailable", "temporary error",
        "session expired", "not registered", "maintenance", "maintainance"
    )

    // Timeouts (ms)
    private val STEP_DELAY_MS = 20L
    private val EVENT_HOT_POLL_MS = 14L
    private val ACCESSIBILITY_NOTIFICATION_TIMEOUT_MS = 300L
    private val DUPLICATE_EVENT_WINDOW_MS = 45L
    private val FAST_VERIFY_POLL_MS = 18L
    private val HOT_SEND_RETRY_DELAY_MS = 12L
    private val SEND_RETRY_DELAY_MS = 22L
    private val POST_WRITE_VERIFY_POLL_MS = 8L
    private val POST_WRITE_SEND_RETRY_MS = 12L
    private val STEP_TIMEOUT_MS = 10000L
    private val STARTUP_STEP_TIMEOUT_MS = 14000L
    private val FINAL_RESPONSE_TIMEOUT_MS = 10000L
    private val PENDING_STEP_TIMEOUT_MS = 12000L
    private val PENDING_ADVANCE_TIMEOUT_MS = 12000L
    private val ROOT_REACQUIRE_TIMEOUT_MS = 8000L
    private val PENDING_STEP_ADVANCE_TIMEOUT_MS = 15000L
    private val NETWORK_DELAY_STEP_TIMEOUT_MS = 22000L
    private val NETWORK_DELAY_FINAL_RESPONSE_TIMEOUT_MS = 28000L
    private val NETWORK_DELAY_PENDING_STEP_TIMEOUT_MS = 22000L
    private val NETWORK_DELAY_PENDING_ADVANCE_TIMEOUT_MS = 22000L
    private val NETWORK_DELAY_ROOT_REACQUIRE_TIMEOUT_MS = 18000L
    private val NETWORK_DELAY_STEP_ADVANCE_TIMEOUT_MS = 22000L
    private val NETWORK_DELAY_ACTION_GRACE_MS = 28000L
    private val PENDING_STEP_ADVANCE_KICK_MS = 18L
    private val VERIFY_POLL_MS = 26L
    private val RAPID_POST_POPUP_POLL_MS = 10L
    private val RAPID_POST_POPUP_VERIFY_MS = 8L
    private val RAPID_POST_POPUP_SEND_RETRY_MS = 10L
    private val MAX_VERIFY_ATTEMPTS = 2
    private val MAX_SEND_ATTEMPTS = 2
    private val FORCEFUL_WRITE_PASSES = 2
    private val WRITE_VERIFICATION_PASSES = 2
    private val WRITE_VERIFICATION_SETTLE_MS = 10L
    private val DIRECT_WRITE_VERIFY_PASSES = 2
    private val SET_TEXT_BURST_ATTEMPTS = 2
    private val PASTE_BURST_ATTEMPTS = 1
    private val NO_FIELD_PATIENCE = 3
    private val INPUT_TARGET_DEPTH = 8
    private val VIEW_TRAVERSAL_MAX_DEPTH = 32
    private val INPUT_DESCENT_DEPTH = 4
    private val INPUT_NEARBY_SCOPE_DEPTH = 3
    private val RECENT_INPUT_GRACE_MS = 2500L
    private val RECENT_VERIFIED_INPUT_GRACE_MS = 4000L
    private val RECENT_UI_EVENT_GRACE_MS = 1000L
    private val RECENT_USSD_CONTEXT_WINDOW_MS = 3_000L
    private val GESTURE_SETTLE_MS = 12L
    private val POST_GESTURE_WAIT_MS = 20L
    private val POST_WRITE_VERIFY_DELAY_MS = 18L
    private val FAST_POPUP_STABILITY_DELAY_MS = 12L
    private val POPUP_STABILITY_DELAY_MS = 40L
    private val STARTUP_FAST_POPUP_STABILITY_DELAY_MS = 20L
    private val STARTUP_POPUP_STABILITY_DELAY_MS = 70L
    private val WEAK_NETWORK_FAST_POPUP_STABILITY_DELAY_MS = 35L
    private val WEAK_NETWORK_POPUP_STABILITY_DELAY_MS = 90L
    private val SIM_CHOOSER_SETTLE_MS = 60L
    private val INTERMEDIATE_POPUP_SETTLE_MS = 110L
    private val TAP_GESTURE_DURATION_MS = 50L
    private val REDIAL_COOLDOWN_MS = 700L
    private val PENDING_ADVANCE_KICK_MS = 18L
    private val ROOT_REACQUIRE_RETRY_DELAY_MS = 80L
    private val DIALOG_DISMISS_SETTLE_MS = 24L
    private val BACK_ACTION_RETRY_DELAY_MS = 150L
    private val UI_KEEP_VISIBLE_INTERVAL_MS = 350L
    private val STARTUP_UI_KEEP_VISIBLE_MS = 7000L
    private val PRIME_TARGET_SETTLE_MS = 40L
    private val CHAR_GESTURE_STAGGER_MS = 45L
    private val CHAR_GESTURE_DURATION_MS = 18L
    private val CHAR_GESTURE_SPREAD_X = 22
    private val CHAR_GESTURE_SPREAD_Y = 22
    private val TAP_GESTURE_RETRY_SETTLE_MS = 30L
    private val SETTLE_BETWEEN_WRITE_PASSES_MS = 15L
    private val RESTART_FROM_ROOT_DELAY_MS = 700L
    private val STEP_TRANSITION_GUARD_MS = 150L
    private val MAX_RETRY_WINDOW_MS = 180000L
    private val MIN_SIM_CHOOSER_SCORE = 260

    private val CHANNEL_ID = "bingwa_ussd"
    private val NOTIFICATION_ID = 2001
    private val SHOW_RUNNING_OVERLAY = false

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val LEADING_DIGIT_REGEX = Regex("""^\d+\s*[\)\].:\-]?\s*""")
    private val NON_ALPHANUMERIC_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val MENU_ITEM_REGEX = Regex("""\b\d+\s*[\)\].:\-]""")
    private val MENU_OPTION_REGEX = Regex("""^(\d+)\s*[\)\].:\-]?\s*(.+)$""")
    private val PHONE_NUMBER_REGEX = Regex("""\b\d{9,15}\b""")
    private val SIM_SLOT_REGEX = { slot: Int -> Regex("""(^|\D)${slot}($|\D)""") }
    // endregion
}
