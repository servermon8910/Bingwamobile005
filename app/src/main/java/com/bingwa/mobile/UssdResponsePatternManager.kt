package com.bingwa.mobile

import android.content.Context
import android.util.Log

class UssdResponsePatternManager(private val context: Context) {

    companion object {
        private const val TAG = "UssdPatternManager"
        private const val DEFAULT_MAINTENANCE_RETRY_DELAY_MS = 5_000L
        private const val DEFAULT_MAX_MAINTENANCE_RETRIES = 3
        private val WHITESPACE_REGEX = Regex("\\s+")

        val DEFAULT_SUCCESS_PATTERNS: List<String> = listOf(
            "You have successfully purchased",
            "You have successfuly purchased",
            "Kindly wait as we process your request. Thank you",
            "Kindly wait while we process your request",
            "Keep selling",
            "Submitted successfully",
            "Keep selling!!",
            "Keep selling!! Be a Bingwa Sokoni Champion",
            "Kindly wait",
            "successful",
            "success",
            "processed",
            "delivered",
            "activated",
            "confirmed",
            "bundle activated",
            "transaction successful",
            "thank you",
            "confirmed. Thank you",
            "you have been registered",
            "you have registered",
            "registration successful",
            "you are now subscribed",
            "subscription successful",
            "you have subscribed",
            "purchase successful",
            "you have purchased",
            "airtime purchased",
            "bundle purchased",
            "data purchased",
            "loan approved",
            "loan disbursed",
            "refund processed",
            "transfer successful",
            "you have transferred",
            "transfer completed",
            "payment successful",
            "you have paid",
            "bill paid",
            "recharge successful",
            "you have bought",
            "purchase completed",
            "order confirmed",
            "order placed",
            "you have won",
            "claim successful",
            "bonus credited",
            "reward credited",
            "points credited",
            "cashback credited",
            "you have received",
            "received successfully",
            "funds received",
            "money received",
            "deposit successful",
            "withdrawal successful",
            "you have withdrawn",
            "you have deposited",
            "account credited",
            "account debited",
            "balance updated",
            "your new balance",
            "current balance",
            "available balance",
            "you are eligible",
            "you qualify",
            "approved",
            "accepted",
            "granted",
            "enabled",
            "subscribed",
            "registered",
            "completed",
            "done",
            "finished",
            "ok",
            "done. thank you",
            "success. thank you"
        )
        val DEFAULT_FAILED_PATTERNS: List<String> = listOf(
            "USSD failure",
            "insufficient airtime",
            "Okoa Jahazi and cannot receive bundles",
            "Dear Partner",
            "Recommendation failed",
            "failed",
            "not allowed",
            "invalid",
            "error",
            "rejected",
            "declined",
            "denied",
            "unable to",
            "cannot",
            "can't",
            "doesn't exist",
            "not found",
            "no such",
            "wrong",
            "incorrect",
            "invalid number",
            "invalid amount",
            "invalid request",
            "invalid input",
            "invalid option",
            "invalid code",
            "invalid pin",
            "wrong pin",
            "incorrect pin",
            "pin mismatch",
            "authentication failed",
            "not authenticated",
            "unauthorized",
            "forbidden",
            "blocked",
            "suspended",
            "deactivated",
            "closed",
            "expired",
            "already used",
            "already redeemed",
            "already claimed",
            "already received",
            "already purchased",
            "already subscribed",
            "already registered",
            "duplicate",
            "double",
            "same day",
            "once per day",
            "daily limit",
            "monthly limit",
            "weekly limit",
            "maximum reached",
            "limit exceeded",
            "quota exceeded",
            "threshold exceeded",
            "below minimum",
            "above maximum",
            "range",
            "between",
            "minimum amount",
            "maximum amount",
            "insufficient balance",
            "not enough",
            "lacking",
            "shortfall",
            "deficit",
            "you do not have",
            "you don't have",
            "no balance",
            "zero balance",
            "empty account"
        )
        val DEFAULT_MAINTENANCE_PATTERNS: List<String> = listOf(
            "Service is currently under maintenance",
            "under maintenance",
            "under maintainance",
            "please try again later",
            "temporarily unavailable",
            "service unavailable",
            "technical problem",
            "system is down",
            "system down",
            "scheduled maintenance",
            "maintenance window",
            "out of service",
            "currently unavailable",
            "temporarily out of service",
            "we are experiencing",
            "high traffic",
            "overloaded",
            "too many requests",
            "rate limit",
            "throttled"
        )
        val DEFAULT_RETRIABLE_FINAL_PATTERNS: List<String> = listOf(
            "connection problem",
            "invalid mmi",
            "mmi code",
            "network error",
            "temporary error",
            "request timeout",
            "timeout",
            "service unavailable",
            "temporarily unavailable",
            "technical problem",
            "under maintenance",
            "under maintainance",
            "maintenance",
            "maintainance",
            "session expired",
            "error",
            "network is busy",
            "network congestion",
            "signal weak",
            "no network",
            "no signal",
            "sim not detected",
            "sim error",
            "sim not ready",
            "ussd not supported",
            "ussd failed",
            "ussd timeout",
            "ussd busy",
            "try again",
            "please retry",
            "retry later",
            "try once more",
            "not completed",
            "incomplete",
            "interrupted",
            "disconnected",
            "call failed",
            "dial failed"
        )
        val DEFAULT_ALREADY_RECOMMENDED_PATTERNS: List<String> = listOf(
            "has already been recommended the same product today",
            "has already been recommended",
            "Failed. 254 has already been recommended today",
            "has already been recommended today",
            "already been recommended",
            "already purchased",
            "already received",
            "same product today",
            "once per day",
            "already used today",
            "already redeemed today",
            "already claimed today",
            "already subscribed today",
            "already registered today",
            "daily limit",
            "today's limit",
            "daily cap",
            "today's cap",
            "limit reached",
            "cap reached"
        )
        val DEFAULT_FAILED_RETRY_PATTERNS: List<String> = listOf("*1#", "***#")
    }

    init { Log.d(TAG, "Using built-in USSD response patterns") }

    fun determineResponseStatus(response: CharSequence?): String {
        val normalized = normalize(response)
        if (normalized.isBlank()) return "Failed"
        return when {
            matchesFailedRetryPattern(normalized) -> "Failed"
            matchesFailedPattern(normalized) -> "Failed"
            matchesMaintenancePattern(normalized) -> "UnderMaintenance"
            matchesAlreadyRecommendedPattern(normalized) -> "Pending"
            matchesSuccessPattern(normalized) -> "Success"
            looksLikeValidResponse(normalized) -> "Success"
            else -> "Failed"
        }
    }

    private fun looksLikeValidResponse(normalized: String): Boolean {
        if (normalized.length < 3) return false
        val hasContent = normalized.any { it.isLetterOrDigit() }
        val hasMultipleWords = normalized.split("\\s+".toRegex()).size > 2
        return hasContent && hasMultipleWords
    }

    fun getSuccessPatterns(): List<String> = DEFAULT_SUCCESS_PATTERNS + getCustomPatterns("success_patterns")
    fun getFailedPatterns(): List<String> = DEFAULT_FAILED_PATTERNS + getCustomPatterns("failed_patterns")
    fun getMaintenancePatterns(): List<String> = DEFAULT_MAINTENANCE_PATTERNS + getCustomPatterns("maintenance_patterns")
    fun getRetriableFinalPatterns(): List<String> = DEFAULT_RETRIABLE_FINAL_PATTERNS + getCustomPatterns("retriable_patterns")
    fun getAlreadyRecommendedPatterns(): List<String> = DEFAULT_ALREADY_RECOMMENDED_PATTERNS + getCustomPatterns("already_recommended_patterns")
    fun getFailedRetryPatterns(): List<String> = DEFAULT_FAILED_RETRY_PATTERNS + getCustomPatterns("failed_retry_patterns")
    fun getMaxMaintenanceRetries(): Int = getCustomInt("max_maintenance_retries", DEFAULT_MAX_MAINTENANCE_RETRIES)
    fun getMaintenanceRetryDelayMs(): Long = getCustomLong("maintenance_retry_delay_ms", DEFAULT_MAINTENANCE_RETRY_DELAY_MS)

    fun saveSuccessPatterns(patterns: List<String>) { saveCustomPatterns("success_patterns", patterns) }
    fun saveFailedPatterns(patterns: List<String>) { saveCustomPatterns("failed_patterns", patterns) }
    fun saveMaintenancePatterns(patterns: List<String>) { saveCustomPatterns("maintenance_patterns", patterns) }
    fun saveAlreadyRecommendedPatterns(patterns: List<String>) { saveCustomPatterns("already_recommended_patterns", patterns) }
    fun saveFailedRetryPatterns(patterns: List<String>) { saveCustomPatterns("failed_retry_patterns", patterns) }
    fun saveMaxMaintenanceRetries(max: Int) { saveCustomInt("max_maintenance_retries", max) }
    fun saveMaintenanceRetryDelayMs(delayMs: Long) { saveCustomLong("maintenance_retry_delay_ms", delayMs) }

    fun resetToDefaults() {
        clearCustomPatterns("success_patterns")
        clearCustomPatterns("failed_patterns")
        clearCustomPatterns("maintenance_patterns")
        clearCustomPatterns("already_recommended_patterns")
        clearCustomPatterns("failed_retry_patterns")
        clearCustomInt("max_maintenance_retries")
        clearCustomLong("maintenance_retry_delay_ms")
    }

    fun matchesSuccessPattern(response: CharSequence?) = matchAny(response, getSuccessPatterns())
    fun matchesFailedPattern(response: CharSequence?) = matchAny(response, getFailedPatterns())
    fun matchesMaintenancePattern(response: CharSequence?) = matchAny(response, getMaintenancePatterns())
    fun matchesRetriableFinalPattern(response: CharSequence?) = matchAny(response, getRetriableFinalPatterns())
    fun matchesAlreadyRecommendedPattern(response: CharSequence?) = matchAny(response, getAlreadyRecommendedPatterns())
    fun matchesFailedRetryPattern(response: CharSequence?) = matchAny(response, getFailedRetryPatterns())

    private fun matchAny(response: CharSequence?, patterns: List<String>): Boolean {
        val normalizedResponse = normalize(response)
        if (normalizedResponse.isBlank()) return false
        return patterns.any { normalizedResponse.contains(normalize(it)) }
    }

    private fun normalize(value: CharSequence?): String =
        value?.toString()
            ?.lowercase()
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            .orEmpty()

    private fun getCustomPatterns(key: String): List<String> {
        val prefs = context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
        val raw = prefs.getString(key, "") ?: return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }
    }

    private fun saveCustomPatterns(key: String, patterns: List<String>) {
        context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
            .edit()
            .putString(key, patterns.joinToString("|||"))
            .apply()
    }

    private fun clearCustomPatterns(key: String) {
        context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }

    private fun getCustomInt(key: String, default: Int): Int {
        val prefs = context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
        return prefs.getInt(key, default)
    }

    private fun saveCustomInt(key: String, value: Int) {
        context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
            .edit()
            .putInt(key, value)
            .apply()
    }

    private fun clearCustomInt(key: String) {
        context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }

    private fun getCustomLong(key: String, default: Long): Long {
        val prefs = context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
        return prefs.getLong(key, default)
    }

    private fun saveCustomLong(key: String, value: Long) {
        context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
            .edit()
            .putLong(key, value)
            .apply()
    }

    private fun clearCustomLong(key: String) {
        context.getSharedPreferences("ussd_patterns", Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }
}