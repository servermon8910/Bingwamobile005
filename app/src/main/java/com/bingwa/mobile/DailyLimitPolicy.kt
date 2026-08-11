package com.bingwa.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class DailyLimitFallbackMapping(
    val primaryOfferId: Int,
    val fallbackOfferIds: List<Int> = emptyList()
)

data class DailyLimitPolicyConfig(
    val mode: String = DailyLimitPolicy.MODE_QUEUE_TOMORROW,
    val fallbackEnabled: Boolean = false,
    val fallbackRuleMode: String = DailyLimitPolicy.FALLBACK_RULE_BOTH,
    val fallbackMappings: List<DailyLimitFallbackMapping> = emptyList(),
    val legacyFallbackOfferId: Int = -1,
    val repeatNoticeEnabled: Boolean = false
) {
    val fallbackTriggerOfferNotFound: Boolean
        get() = DailyLimitPolicy.ruleIncludesOfferNotFound(fallbackRuleMode)

    val fallbackTriggerDailyLimit: Boolean
        get() = DailyLimitPolicy.ruleIncludesAlreadyRecommended(fallbackRuleMode)
}

data class DailyLimitReplyState(
    val txId: Int,
    val stage: String
)

object DailyLimitPolicy {
    const val MODE_QUEUE_TOMORROW = "QUEUE_TOMORROW"
    const val MODE_NOTICE_ONLY = "NOTICE_ONLY"
    const val FALLBACK_RULE_OFFER_NOT_FOUND = "OFFER_NOT_FOUND"
    const val FALLBACK_RULE_ALREADY_RECOMMENDED = "ALREADY_RECOMMENDED"
    const val FALLBACK_RULE_BOTH = "BOTH"
    private const val SETTINGS_PREFS = "app_settings"
    private const val KEY_FALLBACK_ENABLED = "daily_limit_fallback_enabled"
    private const val KEY_FALLBACK_RULE_MODE = "daily_limit_fallback_rule_mode"
    private const val KEY_FALLBACK_TRIGGER_OFFER_NOT_FOUND = "fallback_trigger_offer_not_found"
    private const val KEY_FALLBACK_TRIGGER_FAILED_LEGACY = "fallback_trigger_failed"
    private const val KEY_FALLBACK_TRIGGER_DAILY_LIMIT = "fallback_trigger_daily_limit"
    private const val KEY_FALLBACK_OFFER_ID = "daily_limit_fallback_offer_id"
    private const val KEY_FALLBACK_MAPPINGS = "daily_limit_fallback_mappings"
    private const val REPLY_PREFS = "daily_limit_reply_state"
    private const val STAGE_MENU = "MENU"
    private const val STAGE_ALT_NUMBER = "ALT_NUMBER"

    fun load(context: Context): DailyLimitPolicyConfig {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val legacyOfferNotFound = prefs.safeGetBoolean(
            KEY_FALLBACK_TRIGGER_OFFER_NOT_FOUND,
            prefs.safeGetBoolean(KEY_FALLBACK_TRIGGER_FAILED_LEGACY, true)
        )
        val legacyDailyLimit = prefs.safeGetBoolean(KEY_FALLBACK_TRIGGER_DAILY_LIMIT, true)
        return DailyLimitPolicyConfig(
            mode = prefs.safeGetString("daily_limit_mode", MODE_QUEUE_TOMORROW) ?: MODE_QUEUE_TOMORROW,
            fallbackEnabled = prefs.safeGetBoolean(KEY_FALLBACK_ENABLED, false),
            fallbackRuleMode = normalizeFallbackRule(
                rawMode = prefs.safeGetString(KEY_FALLBACK_RULE_MODE, null),
                legacyOfferNotFound = legacyOfferNotFound,
                legacyDailyLimit = legacyDailyLimit
            ),
            fallbackMappings = parseFallbackMappings(prefs.safeGetString(KEY_FALLBACK_MAPPINGS, null)),
            legacyFallbackOfferId = prefs.safeGetInt(KEY_FALLBACK_OFFER_ID, -1),
            repeatNoticeEnabled = prefs.safeGetBoolean("daily_limit_repeat_notice_enabled", false)
        )
    }

    fun normalizeFallbackRule(
        rawMode: String?,
        legacyOfferNotFound: Boolean = true,
        legacyDailyLimit: Boolean = true
    ): String {
        return when (rawMode?.uppercase()) {
            FALLBACK_RULE_OFFER_NOT_FOUND -> FALLBACK_RULE_OFFER_NOT_FOUND
            FALLBACK_RULE_ALREADY_RECOMMENDED -> FALLBACK_RULE_ALREADY_RECOMMENDED
            FALLBACK_RULE_BOTH -> FALLBACK_RULE_BOTH
            else -> when {
                legacyOfferNotFound && legacyDailyLimit -> FALLBACK_RULE_BOTH
                legacyOfferNotFound -> FALLBACK_RULE_OFFER_NOT_FOUND
                legacyDailyLimit -> FALLBACK_RULE_ALREADY_RECOMMENDED
                else -> FALLBACK_RULE_BOTH
            }
        }
    }

    fun ruleIncludesOfferNotFound(ruleMode: String): Boolean {
        return when (normalizeFallbackRule(ruleMode)) {
            FALLBACK_RULE_OFFER_NOT_FOUND, FALLBACK_RULE_BOTH -> true
            else -> false
        }
    }

    fun ruleIncludesAlreadyRecommended(ruleMode: String): Boolean {
        return when (normalizeFallbackRule(ruleMode)) {
            FALLBACK_RULE_ALREADY_RECOMMENDED, FALLBACK_RULE_BOTH -> true
            else -> false
        }
    }

    fun saveFallbackRuleMode(context: Context, ruleMode: String) {
        val normalizedRule = normalizeFallbackRule(ruleMode)
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FALLBACK_RULE_MODE, normalizedRule)
            .putBoolean(KEY_FALLBACK_TRIGGER_OFFER_NOT_FOUND, ruleIncludesOfferNotFound(normalizedRule))
            .putBoolean(KEY_FALLBACK_TRIGGER_DAILY_LIMIT, ruleIncludesAlreadyRecommended(normalizedRule))
            .apply()
    }

    fun saveFallbackMappings(context: Context, mappings: List<DailyLimitFallbackMapping>) {
        val normalized = normalizeFallbackMappings(mappings)
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FALLBACK_MAPPINGS, serializeFallbackMappings(normalized))
            .remove(KEY_FALLBACK_OFFER_ID)
            .apply()
    }

    fun resolveFallbackOffer(context: Context, originalOfferId: Int): OfferItem? {
        return resolveFallbackOffers(context, originalOfferId).firstOrNull()
    }

    fun resolveFallbackOffers(context: Context, originalOfferId: Int): List<OfferItem> {
        val config = load(context)
        if (!config.fallbackEnabled) return emptyList()

        val allOffers = OfferRepository.load(context)
        val offerById = allOffers.associateBy { it.id }
        return configuredFallbackIds(config, originalOfferId)
            .asSequence()
            .filter { it != originalOfferId }
            .distinct()
            .mapNotNull { offerById[it] }
            .filter { it.enabled }
            .toList()
    }

    fun findOpenDailyLimitTransaction(
        context: Context,
        phoneNumber: String,
        offerName: String,
        amountValue: Int
    ): Transaction? {
        val normalizedPhone = SmsCommandHandler.normalizePhone(phoneNumber)
        if (normalizedPhone.isBlank()) return null
        val prefs = context.getSharedPreferences("transactions", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        val today = Calendar.getInstance()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val status = obj.optString("status", "")
            val response = obj.optString("ussdResponse", "")
            if (!isDailyLimitHold(status, response)) continue
            val txPhone = SmsCommandHandler.normalizePhone(obj.optString("phoneNumber", ""))
            if (txPhone != normalizedPhone) continue
            val timestamp = obj.optLong("timestamp", 0L)
            if (!isSameDay(timestamp, today)) continue
            val sameOffer = offerName.isNotBlank() && obj.optString("description", "").equals(offerName, ignoreCase = true)
            val sameAmount = amountValue > 0 && extractAmountValue(obj.optString("amount", "")) == amountValue.toDouble()
            if (!sameOffer && !sameAmount) continue
            return Transaction(
                id = obj.optInt("id", -1),
                description = obj.optString("description", ""),
                amount = obj.optString("amount", ""),
                amountValue = extractAmountValue(obj.optString("amount", "")),
                date = obj.optString("date", ""),
                status = status,
                ussdCode = obj.optString("ussdCode", ""),
                phoneNumber = obj.optString("phoneNumber", ""),
                clientName = obj.optString("clientName", ""),
                ussdResponse = response,
                ussdTranscript = obj.optString("ussdTranscript", ""),
                timestamp = timestamp,
                offerId = obj.optInt("offerId", -1),
                completedAt = obj.optLong("completedAt", 0L),
                executionDurationMs = obj.optLong("executionDurationMs", 0L)
            )
        }
        return null
    }

    fun beginReplyMenu(context: Context, customerPhone: String, txId: Int) {
        saveReplyState(context, customerPhone, txId, STAGE_MENU)
    }

    fun awaitAlternativeNumber(context: Context, customerPhone: String, txId: Int) {
        saveReplyState(context, customerPhone, txId, STAGE_ALT_NUMBER)
    }

    fun loadReplyState(context: Context, customerPhone: String): DailyLimitReplyState? {
        val normalizedPhone = SmsCommandHandler.normalizePhone(customerPhone)
        if (normalizedPhone.isBlank()) return null
        val prefs = context.getSharedPreferences(REPLY_PREFS, Context.MODE_PRIVATE)
        val txId = prefs.getInt("${normalizedPhone}_tx_id", -1)
        val stage = prefs.getString("${normalizedPhone}_stage", "") ?: ""
        if (txId < 0 || stage.isBlank()) return null
        return DailyLimitReplyState(txId = txId, stage = stage)
    }

    fun clearReplyState(context: Context, customerPhone: String) {
        val normalizedPhone = SmsCommandHandler.normalizePhone(customerPhone)
        if (normalizedPhone.isBlank()) return
        context.getSharedPreferences(REPLY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("${normalizedPhone}_tx_id")
            .remove("${normalizedPhone}_stage")
            .apply()
    }

    fun isReplyMenuStage(stage: String): Boolean = stage.equals(STAGE_MENU, ignoreCase = true)

    fun isAwaitingAlternativeNumber(stage: String): Boolean = stage.equals(STAGE_ALT_NUMBER, ignoreCase = true)

    fun isReplyEligible(tx: Transaction?): Boolean {
        val record = tx ?: return false
        return isDailyLimitHold(record.status, record.ussdResponse)
    }

    fun isDailyLimitHold(tx: Transaction?): Boolean {
        val record = tx ?: return false
        return isDailyLimitHold(record.status, record.ussdResponse)
    }

    fun isDailyLimitHold(status: String, response: String): Boolean {
        if (!status.equals("Pending", ignoreCase = true)) return false
        val lower = response.lowercase()
        return UssdResponsePatternManager.DEFAULT_ALREADY_RECOMMENDED_PATTERNS.any { pattern ->
            lower.contains(pattern.lowercase())
        } ||
            lower.contains("once per day") ||
            lower.contains("alternative number") ||
            lower.contains("tomorrow morning") ||
            lower.contains("following day")
    }

    fun isAlreadyRecommendedResponse(response: String): Boolean {
        val lower = response.lowercase()
        return UssdResponsePatternManager.DEFAULT_ALREADY_RECOMMENDED_PATTERNS.any { pattern ->
            lower.contains(pattern.lowercase())
        } ||
            lower.contains("once per day") ||
            lower.contains("already purchased") ||
            lower.contains("already received") ||
            lower.contains("same product today")
    }

    data class AlternativeDispatchResult(
        val success: Boolean,
        val message: String,
        val newTxId: Int = -1
    )

    fun dispatchAlternativeNumber(
        context: Context,
        originalTx: Transaction,
        alternativePhone: String
    ): AlternativeDispatchResult {
        if (BlacklistedContactStore.isBlacklisted(context, alternativePhone)) {
            return AlternativeDispatchResult(
                success = false,
                message = "Blocked: $alternativePhone is blacklisted and cannot receive bundles."
            )
        }
        val offer = OfferRepository.findByName(context, originalTx.description)
            ?: originalTx.amountValue.toInt().takeIf { it > 0 }?.let { OfferRepository.findByPrice(context, it) }

        if (offer != null && RelayManager.isPrimary(context) && offer.targetDevice.uppercase() == "RELAY") {
            val sent = RelayManager.forwardBuyAmount(context, alternativePhone, offer.price)
            return if (sent) {
                AlternativeDispatchResult(
                    success = true,
                    message = "Alternative number received. ${offer.name} has been forwarded for $alternativePhone."
                )
            } else {
                AlternativeDispatchResult(
                    success = false,
                    message = "We received the alternative number, but Relay forwarding failed. Please try again shortly."
                )
            }
        }

        val finalCode = when {
            offer != null -> offer.ussdCode.replace("pn", alternativePhone, ignoreCase = true)
            originalTx.phoneNumber.isNotBlank() && originalTx.ussdCode.contains(originalTx.phoneNumber) ->
                originalTx.ussdCode.replace(originalTx.phoneNumber, alternativePhone, ignoreCase = true)
            else -> ""
        }
        if (finalCode.isBlank()) {
            return AlternativeDispatchResult(
                success = false,
                message = "We received the alternative number, but could not rebuild the bundle command. Please contact support."
            )
        }

        val txId = createPendingTransaction(
            context,
            originalTx.description,
            originalTx.amount,
            alternativePhone,
            finalCode,
            originalTx.clientName,
            status = if (originalTx.showInRecent) TransactionStatus.PROCESSING.value else TransactionStatus.PENDING.value,
            source = originalTx.source,
            showInRecent = originalTx.showInRecent,
            offerId = offer?.id ?: originalTx.offerId
        )
        if (txId < 0) {
            return AlternativeDispatchResult(
                success = false,
                message = "We received the alternative number, but could not save the new dispatch request."
            )
        }

        context.startOfferAutomation(
            offer = offer,
            phoneNumber = alternativePhone,
            txId = txId,
            finalCode = finalCode,
            mode = offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE,
            returnToAppAggressively = false
        )
        val offerLabel = offer?.name ?: originalTx.description
        return AlternativeDispatchResult(
            success = true,
            message = "Alternative number received. Dispatch started for $alternativePhone using $offerLabel.",
            newTxId = txId
        )
    }

    fun replaceDailyLimitWithAlternativeNumber(
        context: Context,
        originalTx: Transaction,
        alternativePhone: String
    ): AlternativeDispatchResult {
        val dispatchResult = dispatchAlternativeNumber(context, originalTx, alternativePhone)
        if (!dispatchResult.success) return dispatchResult

        cancelScheduledRetry(context, originalTx.id)
        val originalNote = buildString {
            append(originalTx.ussdResponse.ifBlank { "Daily-limit request replaced by alternative number." })
            append("\n\nAlternative number used: $alternativePhone.")
            dispatchResult.newTxId.takeIf { it >= 0 }?.let { append(" Replacement transaction: #$it.") }
        }
        saveTransactionOutcome(context, originalTx.id, "Cancelled", originalNote)
        clearReplyState(context, originalTx.phoneNumber)
        return dispatchResult
    }

    private fun saveReplyState(context: Context, customerPhone: String, txId: Int, stage: String) {
        val normalizedPhone = SmsCommandHandler.normalizePhone(customerPhone)
        if (normalizedPhone.isBlank() || txId < 0) return
        context.getSharedPreferences(REPLY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("${normalizedPhone}_tx_id", txId)
            .putString("${normalizedPhone}_stage", stage)
            .apply()
    }

    private fun configuredFallbackIds(config: DailyLimitPolicyConfig, originalOfferId: Int): List<Int> {
        val mappedIds = config.fallbackMappings
            .firstOrNull { it.primaryOfferId == originalOfferId }
            ?.fallbackOfferIds
            .orEmpty()
        if (mappedIds.isNotEmpty()) return mappedIds
        return config.legacyFallbackOfferId.takeIf { it >= 0 }?.let(::listOf).orEmpty()
    }

    private fun parseFallbackMappings(raw: String?): List<DailyLimitFallbackMapping> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val primaryOfferId = obj.optInt("primaryOfferId", -1)
                    if (primaryOfferId < 0) continue
                    val fallbackIds = mutableListOf<Int>()
                    val fallbackArray = obj.optJSONArray("fallbackOfferIds") ?: JSONArray()
                    for (index in 0 until fallbackArray.length()) {
                        val fallbackId = fallbackArray.optInt(index, -1)
                        if (fallbackId >= 0 && fallbackId != primaryOfferId && fallbackId !in fallbackIds) {
                            fallbackIds += fallbackId
                        }
                    }
                    if (fallbackIds.isNotEmpty()) {
                        add(DailyLimitFallbackMapping(primaryOfferId = primaryOfferId, fallbackOfferIds = fallbackIds))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeFallbackMappings(mappings: List<DailyLimitFallbackMapping>): String {
        val arr = JSONArray()
        normalizeFallbackMappings(mappings).forEach { mapping ->
            arr.put(
                JSONObject().apply {
                    put("primaryOfferId", mapping.primaryOfferId)
                    put("fallbackOfferIds", JSONArray(mapping.fallbackOfferIds))
                }
            )
        }
        return arr.toString()
    }

    private fun normalizeFallbackMappings(mappings: List<DailyLimitFallbackMapping>): List<DailyLimitFallbackMapping> {
        return mappings.mapNotNull { mapping ->
            val primaryOfferId = mapping.primaryOfferId.takeIf { it >= 0 } ?: return@mapNotNull null
            val fallbackIds = mapping.fallbackOfferIds
                .filter { it >= 0 && it != primaryOfferId }
                .distinct()
            if (fallbackIds.isEmpty()) null else DailyLimitFallbackMapping(primaryOfferId, fallbackIds)
        }
    }
}

fun loadTransactionById(context: Context, txId: Int): Transaction? {
    return TransactionStore.findById(context, txId)
}

fun saveTransactionOutcome(
    context: Context,
    txId: Int,
    status: String,
    response: String,
    transcript: String? = null
): Boolean {
    return TransactionStore.saveOutcome(context, txId, status, response, transcript)
}

fun cancelScheduledRetry(context: Context, txId: Int) {
    if (txId < 0) return
    runCatching {
        val intent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RETRY_PENDING
        }
        val pi = PendingIntent.getService(
            context,
            txId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        am?.cancel(pi)
        pi.cancel()
    }
    AutomationService.cancelRetriableResponseRetry(context, txId)
}

fun scheduleRetryTomorrowForTransaction(context: Context, tx: Transaction, offer: OfferItem? = null) {
    if (tx.id < 0 || tx.ussdCode.isBlank()) return
    runCatching {
        val intent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RETRY_PENDING
            putExtra("mode", offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE)
            putExtra("code", tx.ussdCode)
            putExtra("phoneNumber", tx.phoneNumber)
            putExtra("txId", tx.id)
            putExtra("offerId", offer?.id ?: -1)
            putExtra("offerName", offer?.name ?: tx.description)
            putExtra("simSelection", offer?.simSelection ?: OFFER_SIM_USE_GENERAL)
            putExtra("signatureEnabled", offer?.signatureDetectionEnabled ?: false)
            putExtra("signatureMode", offer?.signatureAction ?: "STOP")
            putExtra("signatureLearning", false)
        }
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val pi = PendingIntent.getService(
            context,
            tx.id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        AlarmCompat.scheduleRtcWakeup(
            context = context,
            triggerAtMillis = tomorrow.timeInMillis,
            pendingIntent = pi,
            preferExact = true,
            allowWhileIdle = true
        )
    }
}

fun sendCustomerOutcomeSms(context: Context, outcome: String, tx: Transaction?) {
    val record = tx ?: return
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val enabled = when (outcome.lowercase()) {
        "success" -> prefs.getBoolean("notify_success", true)
        "pending" -> prefs.getBoolean("notify_pending", true)
        "limit_notice" -> prefs.getBoolean("notify_limit_notice", true)
        "scheduled" -> prefs.getBoolean("notify_scheduled", true)
        else -> prefs.getBoolean("notify_failed", true)
    }
    if (!enabled) return

    val phone = record.phoneNumber.trim()
    if (!phone.matches(Regex("^0\\d{9}$"))) return

    val amount = extractAmountToken(record.amount)
    val message = buildSmsMessage(
        context,
        outcome,
        record.clientName,
        record.description,
        amount,
        phone
    )
    val subId = prefs.getInt("notify_sim_id", -1).takeIf { it != -1 }
    SmsCommandHandler.sendSms(context, phone, message, subId)
}

private fun extractAmountToken(amount: String): String =
    Regex("""\d+(?:\.\d+)?""").find(amount)?.value ?: amount

private fun extractAmountValue(amount: String): Double =
    Regex("""\d+(?:\.\d+)?""").find(amount)?.value?.toDoubleOrNull() ?: 0.0

private fun transactionFromJson(obj: org.json.JSONObject): Transaction {
    val amount = obj.optString("amount", "")
    val description = obj.optString("description", "")
    val clientName = obj.optString("clientName", "")
    val ussdCode = obj.optString("ussdCode", "")
    val status = obj.optString("status", "Pending")
    return Transaction(
        id = obj.optInt("id", -1),
        description = description,
        amount = amount,
        amountValue = extractAmountValue(amount),
        date = obj.optString("date", ""),
        status = status,
        statusEnum = TransactionStatus.fromString(status),
        ussdCode = ussdCode,
        phoneNumber = obj.optString("phoneNumber", ""),
        clientName = clientName,
        ussdResponse = obj.optString("ussdResponse", ""),
        ussdTranscript = obj.optString("ussdTranscript", ""),
        timestamp = obj.optLong("timestamp", 0L),
        source = obj.optString("source").ifBlank {
            if (amount.trim().startsWith("-") || description.contains("airtime", ignoreCase = true)) TX_SOURCE_AIRTIME
            else if (ussdCode.isNotBlank() && clientName.isNotBlank()) TX_SOURCE_AUTOMATED
            else TX_SOURCE_SYSTEM
        },
        showInRecent = if (obj.has("showInRecent")) {
            obj.optBoolean("showInRecent", false)
        } else {
            ussdCode.isNotBlank() && clientName.isNotBlank() && !amount.trim().startsWith("-")
        },
        offerId = obj.optInt("offerId", -1),
        completedAt = obj.optLong("completedAt", 0L),
        executionDurationMs = obj.optLong("executionDurationMs", 0L)
    )
}

private fun isSameDay(timestamp: Long, today: Calendar): Boolean {
    if (timestamp <= 0L) return false
    val other = Calendar.getInstance().apply { timeInMillis = timestamp }
    return today.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
}
