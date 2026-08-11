package com.bingwa.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

internal object ScheduledOfferDispatchStore {
    private const val PREFS_NAME = "scheduled_offer_dispatches"
    private const val KEY_LIST = "list"
    private val lock = Any()

    data class ScheduledDispatch(
        val txId: Int,
        val triggerAtMillis: Long,
        val mode: String,
        val code: String,
        val phoneNumber: String,
        val offerId: Int,
        val offerName: String,
        val simSelection: Int,
        val signatureEnabled: Boolean,
        val signatureMode: String,
        val executionPriority: String,
        val returnToAppAggressively: Boolean
    )

    fun schedule(
        context: Context,
        dispatch: ScheduledDispatch,
        preferExact: Boolean = true
    ): Boolean {
        if (dispatch.txId < 0) return false
        if (dispatch.triggerAtMillis <= 0L) return false

        val pi = buildPendingIntent(context, dispatch)
        val ok = AlarmCompat.scheduleRtcWakeup(
            context = context,
            triggerAtMillis = dispatch.triggerAtMillis,
            pendingIntent = pi,
            preferExact = preferExact,
            allowWhileIdle = true
        )
        if (!ok) return false
        upsert(context, dispatch)
        return true
    }

    fun cancel(context: Context, txId: Int) {
        if (txId < 0) return
        remove(context, txId)
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@runCatching
            val pi = PendingIntent.getService(
                context,
                txId,
                Intent(context, AutomationService::class.java).apply { action = AutomationService.ACTION_RUN_SCHEDULED },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun markExecuted(context: Context, txId: Int) {
        remove(context, txId)
    }

    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val existingTxIds = TransactionStore.load(context).asSequence().map { it.id }.toHashSet()
        val dispatches = load(context)
        if (dispatches.isEmpty()) return

        dispatches.forEach { item ->
            if (item.txId !in existingTxIds) {
                remove(context, item.txId)
                return@forEach
            }
            val triggerAt = item.triggerAtMillis.takeIf { it > now } ?: (now + 15_000L)
            val updated = if (triggerAt == item.triggerAtMillis) item else item.copy(triggerAtMillis = triggerAt)
            schedule(context, updated, preferExact = false)
        }
    }

    fun load(context: Context): List<ScheduledDispatch> = synchronized(lock) {
        parse(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .safeGetString(KEY_LIST, "[]")
                ?: "[]"
        )
    }

    private fun upsert(context: Context, dispatch: ScheduledDispatch) = synchronized(lock) {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.txId == dispatch.txId }
        if (index >= 0) current[index] = dispatch else current.add(dispatch)
        persist(context, current)
    }

    private fun remove(context: Context, txId: Int) = synchronized(lock) {
        val current = load(context)
        val updated = current.filterNot { it.txId == txId }
        persist(context, updated)
    }

    private fun persist(context: Context, list: List<ScheduledDispatch>) {
        val arr = JSONArray().apply {
            list.forEach { put(it.toJson()) }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }

    private fun parse(rawJson: String): List<ScheduledDispatch> {
        return runCatching {
            val arr = JSONArray(rawJson)
            (0 until arr.length()).mapNotNull { index ->
                val obj = arr.optJSONObject(index) ?: return@mapNotNull null
                val txId = obj.optInt("txId", -1)
                val triggerAtMillis = obj.optLong("triggerAtMillis", 0L)
                if (txId < 0 || triggerAtMillis <= 0L) return@mapNotNull null
                ScheduledDispatch(
                    txId = txId,
                    triggerAtMillis = triggerAtMillis,
                    mode = obj.optString("mode", OFFER_EXECUTION_MODE_SIMPLE),
                    code = obj.optString("code", ""),
                    phoneNumber = obj.optString("phoneNumber", ""),
                    offerId = obj.optInt("offerId", -1),
                    offerName = obj.optString("offerName", ""),
                    simSelection = obj.optInt("simSelection", OFFER_SIM_USE_GENERAL),
                    signatureEnabled = obj.optBoolean("signatureEnabled", false),
                    signatureMode = obj.optString("signatureMode", "STOP"),
                    executionPriority = obj.optString("executionPriority", USSD_EXECUTION_PRIORITY_SPECIAL),
                    returnToAppAggressively = obj.optBoolean("returnToAppAggressively", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun ScheduledDispatch.toJson(): JSONObject = JSONObject().apply {
        put("txId", txId)
        put("triggerAtMillis", triggerAtMillis)
        put("mode", mode)
        put("code", code)
        put("phoneNumber", phoneNumber)
        put("offerId", offerId)
        put("offerName", offerName)
        put("simSelection", simSelection)
        put("signatureEnabled", signatureEnabled)
        put("signatureMode", signatureMode)
        put("executionPriority", executionPriority)
        put("returnToAppAggressively", returnToAppAggressively)
    }

    private fun buildPendingIntent(context: Context, dispatch: ScheduledDispatch): PendingIntent {
        val intent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN_SCHEDULED
            putExtra("mode", dispatch.mode)
            putExtra("code", dispatch.code)
            putExtra("phoneNumber", dispatch.phoneNumber)
            putExtra("txId", dispatch.txId)
            putExtra("offerId", dispatch.offerId)
            putExtra("offerName", dispatch.offerName)
            putExtra("simSelection", dispatch.simSelection)
            putExtra("signatureEnabled", dispatch.signatureEnabled)
            putExtra("signatureMode", dispatch.signatureMode)
            putExtra("signatureLearning", false)
            putExtra("executionPriority", dispatch.executionPriority)
            putExtra("returnToAppAggressively", dispatch.returnToAppAggressively)
        }
        return PendingIntent.getService(
            context,
            dispatch.txId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
