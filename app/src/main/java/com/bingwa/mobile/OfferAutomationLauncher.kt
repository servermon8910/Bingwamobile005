package com.bingwa.mobile

import android.content.Context
import android.content.Intent

fun Context.startOfferAutomation(
    offer: OfferItem?,
    phoneNumber: String,
    txId: Int,
    finalCode: String,
    mode: String = offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE,
    signatureLearning: Boolean = false,
    executionPriority: String = USSD_EXECUTION_PRIORITY_SPECIAL,
    returnToAppAggressively: Boolean = true
) {
    val requestedMode = mode.ifBlank { offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE }
    val protectionEnabled = offer?.signatureDetectionEnabled == true
    // Signature learning and protection both need the guided flow so live menus can be read safely.
    val effectiveMode = if (signatureLearning || protectionEnabled) {
        OFFER_EXECUTION_MODE_ADVANCED
    } else {
        requestedMode
    }
    val effectiveSignatureEnabled = protectionEnabled && !signatureLearning
    ServiceLauncher.startAutomationService(this, Intent(this, AutomationService::class.java).apply {
        putExtra("mode", effectiveMode)
        putExtra("code", finalCode)
        putExtra("phoneNumber", phoneNumber)
        putExtra("txId", txId)
        putExtra("signatureLearning", signatureLearning)
        putExtra("executionPriority", executionPriority)
        putExtra("returnToAppAggressively", returnToAppAggressively)
        if (offer != null) {
            putExtra("offerId", offer.id)
            putExtra("offerName", offer.name)
            putExtra("simSelection", offer.simSelection)
            putExtra("signatureEnabled", effectiveSignatureEnabled)
            putExtra("signatureMode", offer.signatureAction)
        }
    })
}
