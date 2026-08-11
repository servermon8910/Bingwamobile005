package com.bingwa.mobile

const val TX_SOURCE_AUTOMATED = "AUTOMATED"
const val TX_SOURCE_MANUAL = "CONSOLE"
const val TX_SOURCE_CONSOLE = TX_SOURCE_MANUAL
const val TX_SOURCE_SMS_COMMAND = "SMS_COMMAND"
const val TX_SOURCE_AIRTIME = "AIRTIME"
const val TX_SOURCE_SYSTEM = "SYSTEM"

enum class TransactionStatus(val value: String) {
    PENDING("Pending"), PROCESSING("Processing"), SUCCESS("Success"),
    FAILED("Failed"), CANCELLED("Cancelled"), RETRYING("Retrying");
    companion object {
        fun fromString(text: String): TransactionStatus =
            entries.find { it.value.equals(text, ignoreCase = true) } ?: PENDING
    }
}

data class DataOffer(
    val name: String, val price: Int, val ussdCode: String,
    val executionMode: String = "SIMPLE", val mode: String = "daily"
)

data class Transaction(
    val id: Int = 0, val description: String, val amount: String,
    val amountValue: Double = 0.0, val date: String, val status: String,
    val statusEnum: TransactionStatus = TransactionStatus.PENDING,
    val ussdCode: String = "", val phoneNumber: String = "",
    val response: String = "", val timestamp: Long = System.currentTimeMillis(),
    val clientName: String = "", val ussdResponse: String = "",
    val ussdTranscript: String = "",
    val source: String = TX_SOURCE_SYSTEM,
    val showInRecent: Boolean = false,
    val offerId: Int = -1,
    val completedAt: Long = 0L,
    val executionDurationMs: Long = 0L
)
