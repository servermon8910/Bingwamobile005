package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object OfferRepository {
    private const val PREFS_NAME = "DataOffers"
    private const val KEY_OFFERS = "offers"
    private const val KEY_CATALOG_VERSION = "catalog_version"
    private const val CURRENT_CATALOG_VERSION = 6
    const val ACTION_OFFERS_UPDATED = "com.bingwa.mobile.OFFERS_UPDATED"
    private val gson = Gson()
    private val lock = Any()
    @Volatile private var cachedRawJson: String? = null
    @Volatile private var cachedOffers: List<OfferItem>? = null

    data class RepairResult(
        val offers: List<OfferItem>,
        val restoredDefaultOffers: Int,
        val removedBrokenOffers: Int
    )

    fun ensureSeeded(context: Context): List<OfferItem> {
        return synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawJson = prefs.safeGetString(KEY_OFFERS, null)
            val parsed = parse(rawJson)
            when {
                rawJson.isNullOrBlank() -> {
                    seedDefaults(context, prefs)
                }
                parsed == null -> repair(context).offers
                else -> {
                    val cleaned = sanitize(parsed)
                    if (prefs.getInt(KEY_CATALOG_VERSION, 0) < CURRENT_CATALOG_VERSION) {
                        migrateCatalog(context, cleaned, prefs)
                    } else {
                        cleaned.also { updateCache(rawJson, it) }
                    }
                }
            }
        }
    }

    fun load(context: Context): MutableList<OfferItem> {
        return synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawJson = prefs.safeGetString(KEY_OFFERS, null)
            val cached = cachedOffers
            if (rawJson == cachedRawJson && cached != null) return@synchronized copyOffers(cached)
            when {
                rawJson.isNullOrBlank() -> seedDefaults(context, prefs).toMutableList()
                else -> {
                    val parsed = parse(rawJson) ?: return@synchronized repair(context).offers.toMutableList()
                    val cleaned = sanitize(parsed)
                    if (prefs.getInt(KEY_CATALOG_VERSION, 0) < CURRENT_CATALOG_VERSION) {
                        migrateCatalog(context, cleaned, prefs).toMutableList()
                    } else {
                        cleaned.also { updateCache(rawJson, it) }
                    }
                }
            }
        }
    }

    fun save(context: Context, offers: List<OfferItem>) {
        val sanitized = sanitize(offers)
        val json = gson.toJson(sanitized)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_OFFERS, json)
                .putInt(KEY_CATALOG_VERSION, CURRENT_CATALOG_VERSION)
                .commit()
            updateCache(json, sanitized)
        }
        context.sendBroadcast(Intent(ACTION_OFFERS_UPDATED).setPackage(context.packageName))
    }

    fun repair(context: Context): RepairResult {
        return synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storage = UssdStorage(context)
            val defaults = defaultOffers(context)
            val current = parse(prefs.safeGetString(KEY_OFFERS, null)).orEmpty()
            val cleaned = sanitize(current)
            val managedPrices = storage.managedCatalogPrices()
            val allManagedPrices = managedPrices + storage.legacyManagedCatalogPrices()
            val managedKeys = storage.managedCatalogKeys()
            val defaultIds = defaults.mapTo(mutableSetOf()) { it.id }
            val existingByCatalogKey = cleaned
                .filter { it.catalogKey.isNotBlank() }
                .associateBy { it.catalogKey }
            val legacyManagedById = cleaned
                .filter { it.catalogKey.isBlank() }
                .associateBy { it.id }
            val legacyManagedByPrice = cleaned
                .filter { it.catalogKey.isBlank() && it.price in allManagedPrices }
                .associateBy { it.price }
            val customOffers = cleaned.filterNot { offer ->
                offer.catalogKey in managedKeys ||
                    (offer.catalogKey.isBlank() && (offer.id in defaultIds || offer.price in allManagedPrices))
            }
            val merged = mutableListOf<OfferItem>()
            var nextId = ((cleaned.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0)) + 1
            var restoredDefaultOffers = 0

            defaults.forEach { default ->
                val existing = existingByCatalogKey[default.catalogKey]
                    ?: legacyManagedById[default.id]
                    ?: legacyManagedByPrice[default.price]
                if (existing == null) {
                    merged += default.copy(id = nextId++)
                    restoredDefaultOffers++
                    return@forEach
                }

                val mergedOffer = mergeManagedOffer(existing, default)
                if (!mergedOffer.ussdCode.trim().equals(existing.ussdCode.trim(), ignoreCase = true)) {
                    restoredDefaultOffers++
                }
                merged += mergedOffer
            }

            customOffers.forEach { offer ->
                merged += if (merged.any { it.id == offer.id }) {
                    offer.copy(id = nextId++)
                } else {
                    offer
                }
            }

            val repairedOffers = sanitize(merged).sortedWith(compareBy<OfferItem> { it.price }.thenBy { it.name })
            val removedBrokenOffers = current.size - cleaned.size
            save(context, repairedOffers)
            setCatalogVersion(prefs)
            RepairResult(
                offers = repairedOffers,
                restoredDefaultOffers = restoredDefaultOffers,
                removedBrokenOffers = removedBrokenOffers
            )
        }
    }

    fun findById(context: Context, offerId: Int): OfferItem? =
        load(context).firstOrNull { it.id == offerId }

    fun findByName(context: Context, offerName: String): OfferItem? =
        load(context).firstOrNull { it.enabled && it.name.trim().equals(offerName.trim(), ignoreCase = true) }

    fun findByPrice(context: Context, amount: Int): OfferItem? =
        load(context).firstOrNull { it.enabled && it.price == amount }

    fun updateSignature(
        context: Context,
        offerId: Int,
        signature: List<UssdSignatureStep>,
        learningCaptures: List<UssdLearningCapture> = emptyList()
    ): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val updated = offers[index].copy(
            signatureLearnedAt = System.currentTimeMillis(),
            learnedSignature = signature,
            signatureLearningCaptures = learningCaptures
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    fun stageSignatureReview(
        context: Context,
        offerId: Int,
        signature: List<UssdSignatureStep>,
        learningCaptures: List<UssdLearningCapture> = emptyList()
    ): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val updated = offers[index].copy(
            pendingLearnedSignature = signature,
            pendingSignatureLearningCaptures = learningCaptures,
            pendingSignatureLearnedAt = System.currentTimeMillis()
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    fun approveStagedSignature(context: Context, offerId: Int): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val offer = offers[index]
        if (offer.pendingLearnedSignature.isEmpty() && offer.pendingSignatureLearningCaptures.isEmpty()) return offer
        val approvedAt = if (offer.pendingSignatureLearnedAt > 0L) {
            offer.pendingSignatureLearnedAt
        } else {
            System.currentTimeMillis()
        }
        val updated = offer.copy(
            learnedSignature = offer.pendingLearnedSignature,
            signatureLearningCaptures = offer.pendingSignatureLearningCaptures,
            signatureLearnedAt = approvedAt,
            pendingLearnedSignature = emptyList(),
            pendingSignatureLearningCaptures = emptyList(),
            pendingSignatureLearnedAt = 0L
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    fun clearPendingSignature(context: Context, offerId: Int): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val updated = offers[index].copy(
            pendingLearnedSignature = emptyList(),
            pendingSignatureLearningCaptures = emptyList(),
            pendingSignatureLearnedAt = 0L
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    private fun parse(rawJson: String?): MutableList<OfferItem>? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            gson.fromJson<MutableList<OfferItem>>(rawJson, object : TypeToken<MutableList<OfferItem>>() {}.type)
        } catch (_: Exception) {
            null
        }
    }

    private fun updateCache(rawJson: String?, offers: List<OfferItem>) {
        cachedRawJson = rawJson
        cachedOffers = offers.map { it.copy() }
    }

    private fun copyOffers(offers: List<OfferItem>): MutableList<OfferItem> =
        offers.map { it.copy() }.toMutableList()

    private fun seedDefaults(context: Context, prefs: SharedPreferences): List<OfferItem> {
        val defaults = defaultOffers(context)
        save(context, defaults)
        setCatalogVersion(prefs)
        return defaults
    }

    private fun migrateCatalog(
        context: Context,
        currentOffers: List<OfferItem>,
        prefs: SharedPreferences
    ): List<OfferItem> {
        val storage = UssdStorage(context)
        val defaults = defaultOffers(context)
        val managedPrices = storage.managedCatalogPrices() + storage.legacyManagedCatalogPrices()
        val managedKeys = storage.managedCatalogKeys()
        val defaultIds = defaults.mapTo(mutableSetOf()) { it.id }
        val existingByCatalogKey = currentOffers
            .filter { it.catalogKey.isNotBlank() }
            .associateBy { it.catalogKey }
        val legacyManagedById = currentOffers
            .filter { it.catalogKey.isBlank() }
            .associateBy { it.id }
        val legacyManagedByPrice = currentOffers
            .filter { it.catalogKey.isBlank() && it.price in managedPrices }
            .associateBy { it.price }
        val migrated = mutableListOf<OfferItem>()
        var nextId = ((currentOffers.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0)) + 1

        defaults.forEach { default ->
            val existing = existingByCatalogKey[default.catalogKey]
                ?: legacyManagedById[default.id]
                ?: legacyManagedByPrice[default.price]
            migrated += if (existing != null) {
                mergeManagedOffer(existing, default)
            } else {
                default.copy(id = nextId++)
            }
        }

        currentOffers
            .filterNot { it.catalogKey in managedKeys || (it.catalogKey.isBlank() && (it.id in defaultIds || it.price in managedPrices)) }
            .forEach { offer ->
                migrated += if (migrated.any { it.id == offer.id }) {
                    offer.copy(id = nextId++)
                } else {
                    offer
                }
            }

        val repaired = sanitize(migrated).sortedWith(compareBy<OfferItem> { it.price }.thenBy { it.name })
        save(context, repaired)
        setCatalogVersion(prefs)
        return repaired
    }

    private fun setCatalogVersion(prefs: SharedPreferences) {
        prefs.edit().putInt(KEY_CATALOG_VERSION, CURRENT_CATALOG_VERSION).apply()
    }

    private fun sanitize(offers: List<OfferItem>): MutableList<OfferItem> {
        val seenIds = mutableSetOf<Int>()
        var nextId = ((offers.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0)) + 1
        val cleaned = mutableListOf<OfferItem>()

        offers.forEach { offer ->
            val trimmedName = offer.name.trim()
            val trimmedCode = offer.ussdCode.trim()
            if (trimmedName.isBlank() || trimmedCode.isBlank() || offer.price <= 0) return@forEach

            val category = normalizeOfferCategory(offer.category)
            val mode = normalizeOfferExecutionMode(offer.executionMode, category)
            var normalized = offer.copy(
                catalogKey = offer.catalogKey.trim(),
                name = trimmedName,
                ussdCode = trimmedCode,
                catalogDefaultUssdCode = normalizeCatalogDefaultCode(offer.catalogDefaultUssdCode),
                executionMode = mode,
                category = category,
                targetDevice = offer.targetDevice.ifBlank { "PRIMARY" },
                simSelection = normalizeOfferSimSelection(offer.simSelection),
                signatureDetectionEnabled = offer.signatureDetectionEnabled,
                signatureAction = offer.signatureAction,
                learnedSignature = offer.learnedSignature,
                signatureLearnedAt = offer.signatureLearnedAt,
                signatureLearningCaptures = offer.signatureLearningCaptures,
                pendingLearnedSignature = offer.pendingLearnedSignature,
                pendingSignatureLearnedAt = offer.pendingSignatureLearnedAt,
                pendingSignatureLearningCaptures = offer.pendingSignatureLearningCaptures
            )

            if (!seenIds.add(normalized.id)) {
                normalized = normalized.copy(id = nextId++)
                seenIds.add(normalized.id)
            }
            cleaned += normalized
        }
        return cleaned
    }

    private fun defaultOffers(context: Context): List<OfferItem> {
        val storage = UssdStorage(context)
        return storage.getCatalogOffers()
            .sortedBy { it.price }
            .map { entry ->
                val category = inferCategoryFromLabel(entry.label)
                OfferItem(
                    id = entry.legacyId,
                    catalogKey = entry.key,
                    name = entry.label,
                    price = entry.price.toInt(),
                    ussdCode = entry.ussdCode,
                    catalogDefaultUssdCode = entry.ussdCode,
                    category = category,
                    executionMode = defaultExecutionModeForCategory(category)
                )
            }
            .filter { it.ussdCode.isNotBlank() }
    }

    private fun normalizeCatalogDefaultCode(rawCode: String?): String =
        rawCode?.trim().orEmpty()

    private fun isUserEditedManagedOffer(existing: OfferItem, default: OfferItem): Boolean {
        val recordedDefaultCode = normalizeCatalogDefaultCode(existing.catalogDefaultUssdCode)
        val baselineCode = if (recordedDefaultCode.isNotBlank()) recordedDefaultCode else default.ussdCode.trim()
        return !existing.ussdCode.trim().equals(baselineCode, ignoreCase = true)
    }

    private fun mergeManagedOffer(existing: OfferItem, default: OfferItem): OfferItem {
        val preserveEditedCode = isUserEditedManagedOffer(existing, default)
        val mergedCode = if (preserveEditedCode) existing.ussdCode else default.ussdCode
        return existing.copy(
            catalogKey = default.catalogKey,
            name = if (existing.name.isBlank()) default.name else existing.name,
            ussdCode = mergedCode,
            catalogDefaultUssdCode = default.ussdCode,
            learnedSignature = if (preserveEditedCode) emptyList() else existing.learnedSignature,
            signatureLearnedAt = if (preserveEditedCode) 0L else existing.signatureLearnedAt,
            signatureLearningCaptures = if (preserveEditedCode) emptyList() else existing.signatureLearningCaptures,
            pendingLearnedSignature = if (preserveEditedCode) emptyList() else existing.pendingLearnedSignature,
            pendingSignatureLearnedAt = if (preserveEditedCode) 0L else existing.pendingSignatureLearnedAt,
            pendingSignatureLearningCaptures = if (preserveEditedCode) emptyList() else existing.pendingSignatureLearningCaptures
        )
    }

    private fun inferCategoryFromLabel(label: String): String {
        val normalized = label.trim().uppercase()
        return when {
            "SMS" in normalized || "TEXT" in normalized -> OFFER_CATEGORY_SMS
            "MINUTE" in normalized || "CALL" in normalized || "VOICE" in normalized -> OFFER_CATEGORY_CALLS
            else -> OFFER_CATEGORY_DATA
        }
    }

    private fun offerKey(offer: OfferItem): String =
        "${offer.name.trim().lowercase()}|${offer.price}"
}
