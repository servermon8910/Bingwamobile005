package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object TransactionStore {
    private const val PREFS_NAME = "transactions"
    private const val KEY_LIST = "list"
    private const val MAX_RECENT_TRANSACTIONS = 100
    private val AMOUNT_REGEX = Regex("""\d+(?:\.\d+)?""")
    private val lock = Any()

    @Volatile
    private var cachedRawJson: String? = null

    @Volatile
    private var cachedTransactions: List<Transaction>? = null

    fun load(context: Context): MutableList<Transaction> {
        return synchronized(lock) {
            val rawJson = rawJson(context)
            val cached = cachedTransactions
            if (rawJson == cachedRawJson && cached != null) {
                return@synchronized cached.map { it.copy() }.toMutableList()
            }
            val parsed = parse(rawJson)
            updateCache(rawJson, parsed)
            parsed.toMutableList()
        }
    }

    fun save(context: Context, list: List<Transaction>) {
        val normalized = list.map { it.normalized() }
        val json = JSONArray().apply {
            normalized.forEach { put(it.toJson()) }
        }.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs.edit().putString(KEY_LIST, json).commit()
            updateCache(json, normalized)
        }
    }

    fun findById(context: Context, txId: Int): Transaction? {
        if (txId < 0) return null
        return load(context).firstOrNull { it.id == txId }
    }

    /**
     * Update an existing transaction without creating duplicates.
     * Used for retry operations.
     */
    fun updateExisting(
        context: Context,
        txId: Int,
        status: String,
        response: String,
        transcript: String? = null
    ): Boolean {
        return synchronized(lock) {
            if (txId < 0) return@synchronized false
            val current = load(context)
            val index = current.indexOfFirst { it.id == txId }
            if (index < 0) return@synchronized false

            val existing = current[index]
            val normalizedStatus = TransactionStatus.fromString(status)
            val completedAt = when (normalizedStatus) {
                TransactionStatus.SUCCESS,
                TransactionStatus.FAILED,
                TransactionStatus.CANCELLED -> System.currentTimeMillis()
                else -> existing.completedAt
            }
            val executionDurationMs = when {
                completedAt > 0L && existing.timestamp > 0L -> (completedAt - existing.timestamp).coerceAtLeast(0L)
                else -> existing.executionDurationMs
            }

            current[index] = existing.copy(
                status = status,
                statusEnum = normalizedStatus,
                ussdResponse = response,
                ussdTranscript = transcript ?: existing.ussdTranscript,
                completedAt = completedAt,
                executionDurationMs = executionDurationMs
            )
            save(context, current)
            true
        }
    }

    fun restartExisting(
        context: Context,
        txId: Int,
        description: String,
        amount: String,
        phone: String,
        ussd: String,
        clientName: String = "",
        status: String = TransactionStatus.PROCESSING.value,
        source: String = TX_SOURCE_SYSTEM,
        showInRecent: Boolean = false,
        offerId: Int = -1,
        response: String = ""
    ): Boolean {
        val updatedTransaction = synchronized(lock) {
            if (txId < 0) return@synchronized null
            val current = load(context)
            val index = current.indexOfFirst { it.id == txId }
            if (index < 0) return@synchronized null

            val restartedAt = System.currentTimeMillis()
            val existing = current[index]
            val normalizedStatus = TransactionStatus.fromString(status)
            val restartedDate = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(restartedAt))

            current[index] = existing.copy(
                description = description,
                amount = amount,
                amountValue = extractAmountValue(amount),
                date = restartedDate,
                status = status,
                statusEnum = normalizedStatus,
                ussdCode = ussd,
                phoneNumber = phone,
                clientName = formatClientName(clientName),
                ussdResponse = response,
                ussdTranscript = "",
                timestamp = restartedAt,
                source = source,
                showInRecent = showInRecent,
                offerId = offerId,
                completedAt = 0L,
                executionDurationMs = 0L
            )
            save(context, current)
            current[index]
        }
        if (updatedTransaction == null) return false
        notifyDispatchStarted(context, updatedTransaction)
        return true
    }

    fun createPendingTransaction(
        context: Context,
        description: String,
        amount: String,
        phone: String,
        ussd: String,
        clientName: String = "",
        status: String = TransactionStatus.PENDING.value,
        source: String = TX_SOURCE_SYSTEM,
        showInRecent: Boolean = false,
        offerId: Int = -1
    ): Int {
        val created = synchronized(lock) {
            val current = load(context)
            val usedIds = current.asSequence().map { it.id }.toHashSet()
            var newId = ((System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()).coerceAtLeast(1)
            while (!usedIds.add(newId)) {
                newId = if (newId == Int.MAX_VALUE) 1 else newId + 1
            }

            val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val created = Transaction(
                id = newId,
                description = description,
                amount = amount,
                amountValue = extractAmountValue(amount),
                date = date,
                status = status,
                statusEnum = TransactionStatus.fromString(status),
                ussdCode = ussd,
                phoneNumber = phone,
                clientName = formatClientName(clientName),
                ussdResponse = "",
                ussdTranscript = "",
                timestamp = System.currentTimeMillis(),
                source = source,
                showInRecent = showInRecent,
                offerId = offerId
            )

            val updated = ArrayList<Transaction>(minOf(current.size + 1, MAX_RECENT_TRANSACTIONS))
            updated += created
            current.take(MAX_RECENT_TRANSACTIONS - 1).forEach(updated::add)
            save(context, updated)
            created
        }
        broadcastTransactionCreated(context, created.id)
        notifyDispatchStarted(context, created)
        return created.id
    }

    fun insertTransaction(context: Context, tx: Transaction): Int {
        val created = synchronized(lock) {
            val current = load(context)
            val usedIds = current.asSequence().map { it.id }.toHashSet()
            var newId = ((System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()).coerceAtLeast(1)
            while (!usedIds.add(newId)) {
                newId = if (newId == Int.MAX_VALUE) 1 else newId + 1
            }

            val createdAt = tx.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
            val normalizedStatus = TransactionStatus.fromString(tx.status)
            val completedAt = tx.completedAt.takeIf { it > 0L } ?: when (normalizedStatus) {
                TransactionStatus.SUCCESS,
                TransactionStatus.FAILED,
                TransactionStatus.CANCELLED -> createdAt
                else -> 0L
            }
            val executionDurationMs = tx.executionDurationMs.takeIf { it > 0L } ?: when {
                completedAt > 0L -> (completedAt - createdAt).coerceAtLeast(0L)
                else -> 0L
            }

            val date = tx.date.ifBlank {
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(createdAt))
            }
            val created = tx.copy(
                id = newId,
                amountValue = tx.amountValue.takeIf { it > 0.0 } ?: extractAmountValue(tx.amount),
                date = date,
                statusEnum = normalizedStatus,
                clientName = formatClientName(tx.clientName),
                timestamp = createdAt,
                completedAt = completedAt,
                executionDurationMs = executionDurationMs
            )

            val updated = ArrayList<Transaction>(minOf(current.size + 1, MAX_RECENT_TRANSACTIONS))
            updated += created
            current.take(MAX_RECENT_TRANSACTIONS - 1).forEach(updated::add)
            save(context, updated)
            created
        }
        broadcastTransactionCreated(context, created.id)
        notifyInsertedTransaction(context, created)
        return created.id
    }

    fun saveOutcome(
        context: Context,
        txId: Int,
        status: String,
        response: String,
        transcript: String? = null
    ): Boolean {
        val updatedTransaction = synchronized(lock) {
            if (txId < 0) return@synchronized null
            val current = load(context)
            val index = current.indexOfFirst { it.id == txId }
            if (index < 0) return@synchronized null
            val existing = current[index]
            val normalizedStatus = TransactionStatus.fromString(status)
            val completedAt = when (normalizedStatus) {
                TransactionStatus.SUCCESS,
                TransactionStatus.FAILED,
                TransactionStatus.CANCELLED -> System.currentTimeMillis()
                else -> existing.completedAt
            }
            val executionDurationMs = when {
                completedAt > 0L && existing.timestamp > 0L -> (completedAt - existing.timestamp).coerceAtLeast(0L)
                else -> existing.executionDurationMs
            }
            current[index] = existing.copy(
                status = status,
                statusEnum = normalizedStatus,
                ussdResponse = response,
                ussdTranscript = transcript ?: existing.ussdTranscript,
                completedAt = completedAt,
                executionDurationMs = executionDurationMs
            )
            save(context, current)
            current[index]
        }
        if (updatedTransaction == null) return false
        notifyDispatchOutcome(context, updatedTransaction)
        return true
    }

    fun clear(context: Context) {
        synchronized(lock) {
            updateCache("[]", emptyList())
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LIST)
                .apply()
        }
    }

    private fun notifyDispatchStarted(context: Context, tx: Transaction) {
        if (!shouldShowHeadsUp(tx)) return
        DispatchHeadsUpNotifications.showDispatching(context, tx.id, tx.description, tx.phoneNumber)
    }

    private fun notifyInsertedTransaction(context: Context, tx: Transaction) {
        if (!shouldShowHeadsUp(tx)) return
        when (tx.statusEnum) {
            TransactionStatus.PROCESSING,
            TransactionStatus.PENDING,
            TransactionStatus.RETRYING -> DispatchHeadsUpNotifications.showDispatching(
                context,
                tx.id,
                tx.description,
                tx.phoneNumber
            )
            TransactionStatus.SUCCESS -> DispatchHeadsUpNotifications.showSuccess(context, tx.id, tx.description, tx.phoneNumber)
            TransactionStatus.FAILED -> DispatchHeadsUpNotifications.showFailed(context, tx.id, tx.description, tx.phoneNumber)
            TransactionStatus.CANCELLED -> DispatchHeadsUpNotifications.showCancelled(context, tx.id, tx.description, tx.phoneNumber)
        }
    }

    private fun notifyDispatchOutcome(context: Context, tx: Transaction) {
        if (!shouldShowHeadsUp(tx)) return
        when {
            tx.statusEnum == TransactionStatus.PROCESSING &&
                tx.ussdResponse.contains("Forwarded to Relay", ignoreCase = true) ->
                DispatchHeadsUpNotifications.showForwarded(context, tx.id, tx.description, tx.phoneNumber)
            tx.statusEnum == TransactionStatus.SUCCESS ->
                DispatchHeadsUpNotifications.showSuccess(context, tx.id, tx.description, tx.phoneNumber)
            tx.statusEnum == TransactionStatus.FAILED ->
                DispatchHeadsUpNotifications.showFailed(context, tx.id, tx.description, tx.phoneNumber)
            tx.statusEnum == TransactionStatus.PENDING ->
                DispatchHeadsUpNotifications.showPending(context, tx.id, tx.description, tx.phoneNumber)
            tx.statusEnum == TransactionStatus.CANCELLED ->
                DispatchHeadsUpNotifications.showCancelled(context, tx.id, tx.description, tx.phoneNumber)
            else -> Unit
        }
    }

    private fun shouldShowHeadsUp(tx: Transaction): Boolean {
        if (tx.id < 0) return false
        if (tx.phoneNumber.isBlank()) return false
        return tx.source == TX_SOURCE_AUTOMATED ||
            tx.source == TX_SOURCE_MANUAL ||
            tx.source == TX_SOURCE_SMS_COMMAND
    }

    private fun rawJson(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .safeGetString(KEY_LIST, "[]")
            ?: "[]"

    private fun parse(rawJson: String?): List<Transaction> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            List(arr.length()) { index -> fromJson(arr.getJSONObject(index), index) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun updateCache(rawJson: String?, transactions: List<Transaction>) {
        cachedRawJson = rawJson
        cachedTransactions = transactions.map { it.copy() }
    }

    private fun fromJson(obj: JSONObject, fallbackId: Int): Transaction {
        val description = obj.optString("description", "")
        val amount = obj.optString("amount", "")
        val clientName = obj.optString("clientName", "")
        val ussdCode = obj.optString("ussdCode", "")
        val status = obj.optString("status", TransactionStatus.PENDING.value)
        val source = obj.optString("source").ifBlank {
            inferSource(description, amount, clientName, ussdCode)
        }
        val showInRecent = if (obj.has("showInRecent")) {
            obj.optBoolean("showInRecent", false)
        } else {
            inferRecentVisibility(description, amount, clientName, ussdCode)
        }
        return Transaction(
            id = obj.optInt("id", fallbackId),
            description = description,
            amount = amount,
            amountValue = extractAmountValue(amount),
            date = obj.optString("date", ""),
            status = status,
            statusEnum = TransactionStatus.fromString(status),
            ussdCode = ussdCode,
            phoneNumber = obj.optString("phoneNumber", ""),
            response = obj.optString("response", ""),
            timestamp = obj.optLong("timestamp", 0L),
            clientName = clientName,
            ussdResponse = obj.optString("ussdResponse", ""),
            ussdTranscript = obj.optString("ussdTranscript", ""),
            source = source,
            showInRecent = showInRecent,
            offerId = obj.optInt("offerId", -1),
            completedAt = obj.optLong("completedAt", 0L),
            executionDurationMs = obj.optLong("executionDurationMs", 0L)
        )
    }

    private fun Transaction.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("description", description)
            put("amount", amount)
            put("date", date)
            put("status", status)
            put("ussdCode", ussdCode)
            put("phoneNumber", phoneNumber)
            put("clientName", clientName)
            put("ussdResponse", ussdResponse)
            put("ussdTranscript", ussdTranscript)
            put("timestamp", timestamp)
            put("source", source)
            put("showInRecent", showInRecent)
            put("offerId", offerId)
            put("completedAt", completedAt)
            put("executionDurationMs", executionDurationMs)
        }

    private fun Transaction.normalized(): Transaction {
        val normalizedStatus = status.ifBlank { statusEnum.value }
        val normalizedAmountValue = amountValue.takeIf { it > 0.0 } ?: extractAmountValue(amount)
        val normalizedClientName = if (clientName.isBlank()) "" else formatClientName(clientName)
        return copy(
            amountValue = normalizedAmountValue,
            status = normalizedStatus,
            statusEnum = TransactionStatus.fromString(normalizedStatus),
            clientName = normalizedClientName
        )
    }

    private fun extractAmountValue(amount: String): Double =
        AMOUNT_REGEX.find(amount)?.value?.toDoubleOrNull() ?: 0.0

    private fun inferSource(description: String, amount: String, clientName: String, ussdCode: String): String {
        if (amount.trim().startsWith("-") || description.contains("airtime", ignoreCase = true)) {
            return TX_SOURCE_AIRTIME
        }
        if (ussdCode.isNotBlank() && clientName.isNotBlank()) return TX_SOURCE_AUTOMATED
        return TX_SOURCE_SYSTEM
    }

    private fun inferRecentVisibility(description: String, amount: String, clientName: String, ussdCode: String): Boolean =
        inferSource(description, amount, clientName, ussdCode) == TX_SOURCE_AUTOMATED
}
