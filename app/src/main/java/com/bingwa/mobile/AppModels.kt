package com.bingwa.mobile

import androidx.annotation.Keep

const val OFFER_CATEGORY_DATA = "Data"
const val OFFER_CATEGORY_CALLS = "Calls"
const val OFFER_CATEGORY_SMS = "SMS"

const val OFFER_EXECUTION_MODE_SIMPLE = "SIMPLE"
const val OFFER_EXECUTION_MODE_ADVANCED = "ADVANCED"
const val USSD_EXECUTION_PRIORITY_NORMAL = "NORMAL"
const val USSD_EXECUTION_PRIORITY_HIGH = "HIGH"
const val USSD_EXECUTION_PRIORITY_SPECIAL = "SPECIAL"

const val OFFER_SIM_USE_GENERAL = 0

private val OFFER_CATEGORIES = listOf(
    OFFER_CATEGORY_DATA,
    OFFER_CATEGORY_CALLS,
    OFFER_CATEGORY_SMS
)

fun offerCategoryOptions(): List<String> = OFFER_CATEGORIES

fun normalizeOfferCategory(rawCategory: String?): String {
    return when (rawCategory?.trim()?.uppercase()) {
        OFFER_CATEGORY_CALLS.uppercase(),
        "CALL",
        "MINUTE",
        "MINUTES",
        "VOICE" -> OFFER_CATEGORY_CALLS
        OFFER_CATEGORY_SMS.uppercase(),
        "TEXT",
        "TEXTS" -> OFFER_CATEGORY_SMS
        else -> OFFER_CATEGORY_DATA
    }
}

fun defaultExecutionModeForCategory(category: String): String {
    return when (normalizeOfferCategory(category)) {
        OFFER_CATEGORY_DATA -> OFFER_EXECUTION_MODE_SIMPLE
        else -> OFFER_EXECUTION_MODE_ADVANCED
    }
}

fun normalizeOfferExecutionMode(rawMode: String?, category: String): String {
    return when (rawMode?.trim()?.uppercase()) {
        OFFER_EXECUTION_MODE_SIMPLE -> OFFER_EXECUTION_MODE_SIMPLE
        OFFER_EXECUTION_MODE_ADVANCED -> OFFER_EXECUTION_MODE_ADVANCED
        else -> defaultExecutionModeForCategory(category)
    }
}

fun normalizeOfferSimSelection(rawSelection: Int): Int {
    return when (rawSelection) {
        USSD_SIM_SELECTION_SLOT_1,
        USSD_SIM_SELECTION_SLOT_2 -> rawSelection
        else -> OFFER_SIM_USE_GENERAL
    }
}

fun offerSimSelectionLabel(selection: Int): String {
    return when (normalizeOfferSimSelection(selection)) {
        USSD_SIM_SELECTION_SLOT_2 -> "Slot 2"
        USSD_SIM_SELECTION_SLOT_1 -> "Slot 1"
        else -> "General SIM"
    }
}

@Keep
data class OfferItem(
    val id: Int,
    val catalogKey: String = "",
    val name: String,
    val price: Int,
    val ussdCode: String,
    val catalogDefaultUssdCode: String = "",
    var enabled: Boolean = true,
    val executionMode: String = OFFER_EXECUTION_MODE_SIMPLE,
    val category: String = OFFER_CATEGORY_DATA,
    val targetDevice: String = "PRIMARY",
    val simSelection: Int = OFFER_SIM_USE_GENERAL,
    val signatureDetectionEnabled: Boolean = false,
    val signatureAction: String = "STOP",
    val learnedSignature: List<UssdSignatureStep> = emptyList(),
    val signatureLearnedAt: Long = 0L,
    val signatureLearningCaptures: List<UssdLearningCapture> = emptyList(),
    val pendingLearnedSignature: List<UssdSignatureStep> = emptyList(),
    val pendingSignatureLearnedAt: Long = 0L,
    val pendingSignatureLearningCaptures: List<UssdLearningCapture> = emptyList()
)

data class SavedContact(val name: String, val phone: String)
