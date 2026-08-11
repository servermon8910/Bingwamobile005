package com.bingwa.mobile

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun transactionTimestamp(tx: Transaction): Long {
    if (tx.timestamp > 0L) return tx.timestamp
    return runCatching {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).parse(tx.date)?.time ?: 0L
    }.getOrDefault(0L)
}

fun dayKey(timestamp: Long): Int {
    if (timestamp <= 0L) return 0
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return (cal.get(Calendar.YEAR) * 1000) + cal.get(Calendar.DAY_OF_YEAR)
}

fun currentDayKey(): Int = dayKey(System.currentTimeMillis())

fun transactionDayKey(tx: Transaction): Int = dayKey(transactionTimestamp(tx))

fun millisUntilNextMidnight(): Long {
    val now = Calendar.getInstance()
    val next = Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }
    return (next.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
}

fun transactionStatusLabel(tx: Transaction): String = when {
    DailyLimitPolicy.isDailyLimitHold(tx) -> "Scheduled"
    else -> when (tx.statusEnum) {
        TransactionStatus.SUCCESS -> "Completed"
        TransactionStatus.PROCESSING -> "Executing"
        TransactionStatus.PENDING -> "Queued"
        TransactionStatus.RETRYING -> "Retrying"
        TransactionStatus.FAILED -> "Failed"
        TransactionStatus.CANCELLED -> "Cancelled"
    }
}
