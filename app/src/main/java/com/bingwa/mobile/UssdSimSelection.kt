package com.bingwa.mobile

import android.content.Context
import android.telephony.SubscriptionInfo

internal const val USSD_SIM_SELECTION_SLOT_1 = -1
internal const val USSD_SIM_SELECTION_SLOT_2 = -2
internal const val USSD_SIM_SELECTION_BOTH = -3

internal data class UssdSimTarget(
    val subId: Int,
    val slotIndex: Int
)

internal fun normalizeUssdSimSelection(rawSelection: Int, sims: List<SubscriptionInfo>): Int {
    return when (rawSelection) {
        USSD_SIM_SELECTION_SLOT_1,
        USSD_SIM_SELECTION_SLOT_2 -> rawSelection
        USSD_SIM_SELECTION_BOTH -> USSD_SIM_SELECTION_SLOT_1
        else -> {
            val matched = sims.firstOrNull { it.subscriptionId == rawSelection }
            when (matched?.simSlotIndex) {
                1 -> USSD_SIM_SELECTION_SLOT_2
                else -> USSD_SIM_SELECTION_SLOT_1
            }
        }
    }
}

internal fun currentUssdSimSelection(context: Context): Int {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val sims = getAvailableSims(context)
    return normalizeUssdSimSelection(
        rawSelection = prefs.safeGetInt("selected_sim_id", USSD_SIM_SELECTION_SLOT_1),
        sims = sims
    )
}

internal fun resolvePreferredUssdSubId(context: Context, selectionOverride: Int? = null): Int? =
    resolveUssdSimTargets(context, selectionOverride).firstOrNull()?.subId

internal fun resolveUssdSimTargets(context: Context, selectionOverride: Int? = null): List<UssdSimTarget> {
    val sims = getAvailableSims(context).sortedBy { it.simSlotIndex }
    if (sims.isEmpty()) return emptyList()

    val slot1 = sims.firstOrNull { it.simSlotIndex == 0 } ?: sims.getOrNull(0)
    val slot2 = sims.firstOrNull { it.simSlotIndex == 1 } ?: sims.getOrNull(1)
    val rawSelection = selectionOverride ?: context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .safeGetInt("selected_sim_id", USSD_SIM_SELECTION_SLOT_1)
    val explicitSubscription = sims.firstOrNull { it.subscriptionId == rawSelection }
    val normalizedSelection = normalizeUssdSimSelection(rawSelection, sims)

    val resolved = when {
        explicitSubscription != null -> listOf(explicitSubscription)
        rawSelection == USSD_SIM_SELECTION_BOTH -> listOfNotNull(slot1, slot2)
        normalizedSelection == USSD_SIM_SELECTION_SLOT_2 -> slot2?.let { listOf(it) } ?: emptyList()
        normalizedSelection == USSD_SIM_SELECTION_SLOT_1 -> slot1?.let { listOf(it) } ?: emptyList()
        else -> listOfNotNull(slot1 ?: slot2)
    }

    return resolved.distinctBy { it.subscriptionId }
        .map { UssdSimTarget(subId = it.subscriptionId, slotIndex = it.simSlotIndex) }
}

internal fun describeUssdSimSelection(selection: Int, sims: List<SubscriptionInfo>): String {
    val slot1 = sims.firstOrNull { it.simSlotIndex == 0 } ?: sims.getOrNull(0)
    val slot2 = sims.firstOrNull { it.simSlotIndex == 1 } ?: sims.getOrNull(1)

    return when (normalizeUssdSimSelection(selection, sims)) {
        USSD_SIM_SELECTION_SLOT_2 -> formatUssdSlotLabel(2, slot2)
        else -> formatUssdSlotLabel(1, slot1)
    }
}

private fun formatUssdSlotLabel(slotNumber: Int, simInfo: SubscriptionInfo?): String {
    val name = simInfo?.displayName?.toString()?.trim().orEmpty()
    return if (name.isBlank()) {
        "Slot $slotNumber"
    } else {
        "Slot $slotNumber · $name"
    }
}
