package com.bingwa.mobile

import android.content.Context

internal data class TransactionExecutionCopy(
    val statusLabel: String,
    val modeLabel: String,
    val processingLabel: String,
    val supportingLabel: String,
    val detailLabel: String
)

internal fun isTransactionActivelyExecuting(tx: Transaction): Boolean {
    if (DailyLimitPolicy.isDailyLimitHold(tx)) return false
    return tx.statusEnum == TransactionStatus.PENDING ||
        tx.statusEnum == TransactionStatus.PROCESSING ||
        tx.statusEnum == TransactionStatus.RETRYING
}

internal fun transactionExecutionCopy(context: Context, tx: Transaction): TransactionExecutionCopy {
    val offerMode = tx.offerId.takeIf { it >= 0 }?.let { OfferRepository.findById(context, it)?.executionMode }
    return transactionExecutionCopy(tx, offerMode)
}

internal fun transactionExecutionCopy(
    tx: Transaction,
    executionMode: String? = null
): TransactionExecutionCopy {
    val mode = resolveTransactionExecutionMode(tx, executionMode)
    val modeLabel = if (mode == OFFER_EXECUTION_MODE_ADVANCED) "Advanced USSD" else "Simple USSD"
    val failureReason = transactionReasonShort(tx, maxChars = 120)

    return when {
        DailyLimitPolicy.isDailyLimitHold(tx) -> TransactionExecutionCopy(
            statusLabel = "Scheduled",
            modeLabel = modeLabel,
            processingLabel = "Dispatch scheduled",
            supportingLabel = "Waiting for the next send window",
            detailLabel = "This transaction is held until the daily sending limit resets."
        )
        tx.statusEnum == TransactionStatus.SUCCESS -> TransactionExecutionCopy(
            statusLabel = "Completed",
            modeLabel = modeLabel,
            processingLabel = "Completed",
            supportingLabel = "Execution completed",
            detailLabel = "USSD execution finished successfully."
        )
        tx.statusEnum == TransactionStatus.FAILED -> TransactionExecutionCopy(
            statusLabel = "Failed",
            modeLabel = modeLabel,
            processingLabel = "Failed",
            supportingLabel = "Execution failed",
            detailLabel = failureReason.ifBlank { "Open the transaction to review the network response." }
        )
        tx.statusEnum == TransactionStatus.CANCELLED -> TransactionExecutionCopy(
            statusLabel = "Cancelled",
            modeLabel = modeLabel,
            processingLabel = "Cancelled",
            supportingLabel = "Execution stopped",
            detailLabel = failureReason.ifBlank { "The transaction stopped before the USSD session completed." }
        )
        tx.statusEnum == TransactionStatus.RETRYING && mode == OFFER_EXECUTION_MODE_ADVANCED -> TransactionExecutionCopy(
            statusLabel = "Retrying",
            modeLabel = modeLabel,
            processingLabel = "Reopening USSD menu",
            supportingLabel = "Advanced execution is retrying",
            detailLabel = "Bingwa is reopening the USSD menu and continuing the guided steps."
        )
        tx.statusEnum == TransactionStatus.RETRYING -> TransactionExecutionCopy(
            statusLabel = "Retrying",
            modeLabel = modeLabel,
            processingLabel = "Retrying USSD request",
            supportingLabel = "Simple execution is retrying",
            detailLabel = "Bingwa is sending the USSD request again after the last attempt did not finish cleanly."
        )
        tx.statusEnum == TransactionStatus.PROCESSING && mode == OFFER_EXECUTION_MODE_ADVANCED -> TransactionExecutionCopy(
            statusLabel = "Navigating",
            modeLabel = modeLabel,
            processingLabel = "Navigating USSD steps",
            supportingLabel = "Advanced execution is in progress",
            detailLabel = "Bingwa is moving through the USSD menu and submitting each required step."
        )
        tx.statusEnum == TransactionStatus.PROCESSING -> TransactionExecutionCopy(
            statusLabel = "Executing",
            modeLabel = modeLabel,
            processingLabel = "Waiting for network reply",
            supportingLabel = "Simple execution is in progress",
            detailLabel = "Bingwa has sent the USSD request and is waiting for the network response."
        )
        tx.statusEnum == TransactionStatus.PENDING && mode == OFFER_EXECUTION_MODE_ADVANCED -> TransactionExecutionCopy(
            statusLabel = "Preparing",
            modeLabel = modeLabel,
            processingLabel = "Opening USSD menu",
            supportingLabel = "Advanced execution is starting",
            detailLabel = "Bingwa is dialing the entry code and waiting for the first USSD menu."
        )
        else -> TransactionExecutionCopy(
            statusLabel = "Preparing",
            modeLabel = modeLabel,
            processingLabel = "Dialing USSD request",
            supportingLabel = "Simple execution is starting",
            detailLabel = "Bingwa is dialing the USSD code and preparing the session."
        )
    }
}

private fun resolveTransactionExecutionMode(
    tx: Transaction,
    executionMode: String?
): String {
    return when (executionMode?.trim()?.uppercase()) {
        OFFER_EXECUTION_MODE_ADVANCED -> OFFER_EXECUTION_MODE_ADVANCED
        OFFER_EXECUTION_MODE_SIMPLE -> OFFER_EXECUTION_MODE_SIMPLE
        else -> inferTransactionExecutionMode(tx)
    }
}

private fun inferTransactionExecutionMode(tx: Transaction): String {
    if (tx.ussdTranscript.isNotBlank()) return OFFER_EXECUTION_MODE_ADVANCED
    val normalizedCode = tx.ussdCode.replace("%23", "#").trim()
    if (normalizedCode.contains("pn", ignoreCase = true)) return OFFER_EXECUTION_MODE_ADVANCED
    val segments = normalizedCode.trimEnd('#').split("*").count { it.isNotBlank() }
    return if (segments >= 3) OFFER_EXECUTION_MODE_ADVANCED else OFFER_EXECUTION_MODE_SIMPLE
}

internal fun transactionReason(tx: Transaction): String {
    val raw = tx.ussdResponse.ifBlank { tx.response }.trim()
    if (raw.isBlank()) return ""
    val firstNonEmpty = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    return firstNonEmpty.ifBlank { raw }
}

internal fun transactionReasonShort(tx: Transaction, maxChars: Int = 90): String {
    val reason = transactionReason(tx)
    if (reason.isBlank()) return ""
    val normalized = reason.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars - 1).trimEnd() + "…"
}

internal fun formatExecutionMs(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    return when {
        durationMs < 1_000L -> "${durationMs}ms"
        durationMs < 60_000L -> "${durationMs}ms"
        else -> {
            val totalSeconds = durationMs / 1_000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            "${minutes}m ${seconds}s"
        }
    }
}
