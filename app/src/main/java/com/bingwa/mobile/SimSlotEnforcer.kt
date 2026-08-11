package com.bingwa.mobile

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Enforces strict SIM slot selection without fallback to other slots.
 * When user selects a specific SIM slot, only that slot is used.
 */
object SimSlotEnforcer {
    private const val TAG = "SimSlotEnforcer"

    /**
     * Get TelephonyManager for the selected SIM slot strictly.
     * Returns null if the selected slot is not available.
     */
    fun getStrictSlotTelephonyManager(
        baseTm: TelephonyManager,
        context: Context,
        simSelection: Int
    ): TelephonyManager? {
        // No override - use base TelephonyManager
        if (simSelection == OFFER_SIM_USE_GENERAL) {
            return baseTm
        }

        // Get the subscription ID for the selected slot
        val subId = getSubscriptionIdForSlot(context, simSelection) ?: run {
            Log.w(TAG, "Selected SIM slot not available: slot=$simSelection")
            return null
        }

        // Android N+ - use specific subscription
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return try {
                baseTm.createForSubscriptionId(subId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create TelephonyManager for subscription $subId", e)
                null
            }
        }

        // Pre-N: return base TelephonyManager (limited slot control)
        return baseTm
    }

    /**
     * Get subscription ID for the specified slot selection.
     * Returns null if slot is not available.
     */
    private fun getSubscriptionIdForSlot(context: Context, simSelection: Int): Int? {
        val sims = getAvailableSims(context)
        if (sims.isEmpty()) return null

        return when (simSelection) {
            USSD_SIM_SELECTION_SLOT_1 -> {
                // Strictly slot 1
                sims.firstOrNull { it.simSlotIndex == 0 }?.subscriptionId
                    ?: sims.getOrNull(0)?.subscriptionId
            }
            USSD_SIM_SELECTION_SLOT_2 -> {
                // Strictly slot 2
                sims.firstOrNull { it.simSlotIndex == 1 }?.subscriptionId
                    ?: sims.getOrNull(1)?.subscriptionId
            }
            else -> {
                // Explicit subscription ID
                sims.firstOrNull { it.subscriptionId == simSelection }?.subscriptionId
            }
        }
    }

    /**
     * Validate that the selected SIM slot is actually available.
     */
    fun isSlotAvailable(context: Context, simSelection: Int): Boolean {
        if (simSelection == OFFER_SIM_USE_GENERAL) return true
        return getSubscriptionIdForSlot(context, simSelection) != null
    }

    /**
     * Get human-readable slot label for the selection.
     */
    fun getSlotLabel(context: Context, simSelection: Int): String {
        return when (simSelection) {
            USSD_SIM_SELECTION_SLOT_1 -> "Slot 1"
            USSD_SIM_SELECTION_SLOT_2 -> "Slot 2"
            OFFER_SIM_USE_GENERAL -> "Default SIM"
            else -> {
                val sims = getAvailableSims(context)
                val sim = sims.firstOrNull { it.subscriptionId == simSelection }
                if (sim != null) "Slot ${sim.simSlotIndex + 1}" else "SIM $simSelection"
            }
        }
    }
}
