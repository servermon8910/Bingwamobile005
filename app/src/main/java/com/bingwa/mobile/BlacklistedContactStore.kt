package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Stores phone numbers that should NOT receive automated bundle dispatches.
 *
 * Note: phone numbers are stored in normalized local format (e.g. 0712345678).
 */
internal object BlacklistedContactStore {
    private const val PREFS_NAME = "blacklisted_contacts"
    private const val KEY_LIST = "list"
    const val ACTION_BLACKLIST_UPDATED = "com.bingwa.mobile.BLACKLIST_UPDATED"

    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Set<String> = load(prefs(context))

    fun load(prefs: SharedPreferences): Set<String> = synchronized(lock) {
        parse(prefs.safeGetString(KEY_LIST, "[]") ?: "[]")
    }

    fun isBlacklisted(context: Context, phone: String): Boolean {
        val normalized = SmsCommandHandler.normalizePhone(phone)
        if (!normalized.matches(Regex("^0\\d{9}$"))) return false
        return load(context).contains(normalized)
    }

    fun add(context: Context, phone: String): Set<String> = synchronized(lock) {
        val normalized = SmsCommandHandler.normalizePhone(phone)
        if (!normalized.matches(Regex("^0\\d{9}$"))) return@synchronized load(context)
        val current = load(prefs(context)).toMutableSet()
        current.add(normalized)
        persist(prefs(context), current)
        context.sendBroadcast(Intent(ACTION_BLACKLIST_UPDATED).setPackage(context.packageName))
        current
    }

    fun remove(context: Context, phone: String): Set<String> = synchronized(lock) {
        val normalized = SmsCommandHandler.normalizePhone(phone)
        val current = load(prefs(context)).toMutableSet()
        current.remove(normalized)
        persist(prefs(context), current)
        context.sendBroadcast(Intent(ACTION_BLACKLIST_UPDATED).setPackage(context.packageName))
        current
    }

    fun toggle(context: Context, phone: String): Set<String> {
        val normalized = SmsCommandHandler.normalizePhone(phone)
        if (!normalized.matches(Regex("^0\\d{9}$"))) return load(context)
        return if (isBlacklisted(context, normalized)) remove(context, normalized) else add(context, normalized)
    }

    private fun parse(rawJson: String): Set<String> {
        return runCatching {
            val arr = JSONArray(rawJson)
            buildSet {
                for (i in 0 until arr.length()) {
                    val p = SmsCommandHandler.normalizePhone(arr.optString(i, ""))
                    if (p.matches(Regex("^0\\d{9}$"))) add(p)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun persist(prefs: SharedPreferences, set: Set<String>) {
        val arr = JSONArray()
        set.sorted().forEach(arr::put)
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }
}

