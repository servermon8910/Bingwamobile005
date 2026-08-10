package com.bingwa.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object SilentUssd {
    private const val TAG             = "SilentUssd"
    private const val TIMEOUT_MS      = 25_000L
    @Volatile private var inProgress  = false

    private var successCb : ((String) -> Unit)? = null
    private var failureCb : ((String) -> Unit)? = null
    private var timeoutRunnable: Runnable?       = null
    private val handler = Handler(Looper.getMainLooper())

    // ── Public API: two overloads ────────────────────────────────────────────

    /** Overload for callers that have a Context (AutomationService, BalanceChecker). */
    fun execute(
        context   : Context,
        ussdCode  : String,
        onSuccess : (String) -> Unit,
        onFailure : (String) -> Unit
    ): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: run { onFailure("No TelephonyManager"); return false }
        return execute(tm, ussdCode, onSuccess, onFailure)
    }

    /** Overload for callers that already have a TelephonyManager (UssdHelper, etc.). */
    fun execute(
        telephonyManager : TelephonyManager,
        ussdCode         : String,
        onSuccess        : (String) -> Unit,
        onFailure        : (String) -> Unit
    ): Boolean {
        val code = normaliseCode(ussdCode)
        Log.d(TAG, "execute(tm): code=$code  sdk=${Build.VERSION.SDK_INT}")

        synchronized(this) {
            if (inProgress) {
                onFailure("USSD execution already in progress")
                return false
            }
            successCb = onSuccess
            failureCb = onFailure
            inProgress = true
        }
        armTimeout(code)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (tryPublicApi(telephonyManager, code)) return true
            if (tryReflectionApi(telephonyManager, code)) return true
        } else {
            if (tryReflectionApi(telephonyManager, code)) return true
        }

        synchronized(this) { clearLocked() }
        cancelTimeout()
        return false
    }

    fun isExecutionInProgress(): Boolean = synchronized(this) { inProgress }

    fun isSilentUssdSupported(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return true
        return hasReflectionMethod(tm)
    }

    // ── Strategy 1: Public API (Android 8+) ──────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun tryPublicApi(tm: TelephonyManager, code: String): Boolean {
        return try {
            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence
                ) {
                    Log.d(TAG, "publicApi onReceiveUssdResponse: $response")
                    // Extract only the popup response, not screen content
                    val cleanResponse = extractUssdPopupResponse(response.toString())
                    deliverSuccess(cleanResponse)
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int
                ) {
                    Log.w(TAG, "publicApi onReceiveUssdResponseFailed: $failureCode")
                    deliverFailure("USSD failed: $failureCode")
                }
            }, handler)
            true
        } catch (e: Exception) {
            Log.w(TAG, "tryPublicApi failed: ${e.message}")
            false
        }
    }

    // ── Strategy 2: Reflection (Android 5–7, OEMs) ───────────────────────────

    private fun tryReflectionApi(tm: TelephonyManager, code: String): Boolean {
        val candidates = listOf(
            "android.telephony.TelephonyManager"    to "sendUssdRequest",
            "com.android.internal.telephony.Phone"  to "sendUssdResponse",
            "android.telephony.TelephonyManager"    to "handleUssdRequest"
        )

        for ((className, methodName) in candidates) {
            try {
                val callbackClass = Class.forName("android.telephony.TelephonyManager\$UssdResponseCallback")
                    ?: Class.forName("android.telephony.UssdResponseCallback")

                val proxy = buildCallbackProxy(callbackClass)

                val targetClass = if (className == "android.telephony.TelephonyManager")
                    tm.javaClass
                else
                    Class.forName(className)

                val method = targetClass.getMethod(
                    methodName,
                    String::class.java,
                    callbackClass,
                    Handler::class.java
                )
                method.isAccessible = true
                method.invoke(
                    if (className == "android.telephony.TelephonyManager") tm else null,
                    code, proxy, handler
                )
                return true
            } catch (_: Exception) { /* try next candidate */ }
        }

        // Last-resort inner class variants
        val innerCandidates = listOf(
            "android.telephony.TelephonyManager\$UssdResponseCallback",
            "android.telephony.UssdResponseCallback"
        )
        for (callbackClassName in innerCandidates) {
            try {
                val callbackClass = Class.forName(callbackClassName)
                val proxy = buildCallbackProxy(callbackClass)
                val method = tm.javaClass.getMethod(
                    "sendUssdRequest",
                    String::class.java,
                    callbackClass,
                    Handler::class.java
                )
                method.isAccessible = true
                method.invoke(tm, code, proxy, handler)
                return true
            } catch (_: Exception) { /* try next */ }
        }

        return false
    }

    private fun buildCallbackProxy(callbackClass: Class<*>): Any {
        return Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                    when (method.name) {
                        "onReceiveUssdResponse" -> {
                            val response = args?.lastOrNull { it is CharSequence } as? CharSequence
                                ?: args?.getOrNull(2) as? CharSequence
                                ?: args?.getOrNull(1) as? CharSequence
                            val cleanResponse = extractUssdPopupResponse(response?.toString() ?: "")
                            deliverSuccess(cleanResponse)
                        }
                        "onReceiveUssdResponseFailed" -> {
                            val code = args?.filterIsInstance<Int>()?.firstOrNull() ?: -1
                            deliverFailure("USSD failed: $code")
                        }
                    }
                    return null
                }
            }
        )
    }

    /**
     * Extract the USSD popup response text, filtering out screen content.
     * This focuses on what the USSD popup actually displays, not what's on the screen.
     */
    private fun extractUssdPopupResponse(rawResponse: String): String {
        if (rawResponse.isBlank()) return ""
        
        val trimmed = rawResponse.trim()
        
        // If it looks like a menu/list (multiple lines with numbers), it's likely popup content
        val isMenuPopup = trimmed.contains(Regex("""\d+\s*[\)\].:\-]"""))
        
        // If it contains typical USSD response keywords, keep it
        val hasUssdKeywords = listOf(
            "success", "failed", "error", "balance", "ksh", "kes",
            "thank you", "wait", "enter", "confirm", "please", "option",
            "try again", "maintained", "process", "received", "activated"
        ).any { keyword -> trimmed.lowercase().contains(keyword) }
        
        // Prefer single-line responses or menu popups over multi-paragraph content
        val lines = trimmed.split("\n")
        
        return if (lines.size <= 2 || isMenuPopup || hasUssdKeywords) {
            trimmed
        } else {
            // Multi-paragraph - extract first meaningful paragraph
            lines.firstOrNull { it.trim().isNotEmpty() }?.trim() ?: trimmed
        }
    }

    private fun hasReflectionMethod(tm: TelephonyManager): Boolean {
        return try {
            val cb = try {
                Class.forName("android.telephony.TelephonyManager\$UssdResponseCallback")
            } catch (_: Exception) {
                Class.forName("android.telephony.UssdResponseCallback")
            }
            tm.javaClass.getMethod("sendUssdRequest", String::class.java, cb, Handler::class.java)
            true
        } catch (_: Exception) { false }
    }

    // ── Delivery helpers ─────────────────────────────────────────────────────

    private fun deliverSuccess(response: String) {
        cancelTimeout()
        val cb = synchronized(this) { val c = successCb; clearLocked(); c }
        handler.post { runCatching { cb?.invoke(response) } }
    }

    private fun deliverFailure(reason: String) {
        cancelTimeout()
        val cb = synchronized(this) { val c = failureCb; clearLocked(); c }
        handler.post { runCatching { cb?.invoke(reason) } }
    }

    private fun armTimeout(code: String) {
        cancelTimeout()
        val timeout = Runnable {
            Log.w(TAG, "Silent USSD timed out for $code")
            deliverFailure("Timeout waiting for USSD response on a weak or delayed network")
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    @Synchronized
    private fun clearLocked() {
        successCb = null
        failureCb = null
        inProgress = false
    }

    private fun normaliseCode(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("*") && trimmed.endsWith("#")) return trimmed
        if (trimmed.startsWith("*")) return "$trimmed#"
        val decoded = trimmed.replace("%23", "#")
        if (decoded.startsWith("*") && decoded.endsWith("#")) return decoded
        if (decoded.startsWith("*")) return "$decoded#"
        return trimmed
    }
}
