package com.bingwa.mobile

import android.content.SharedPreferences

internal fun SharedPreferences.safeGetString(key: String, defaultValue: String? = null): String? =
    try {
        getString(key, defaultValue)
    } catch (_: ClassCastException) {
        when (val value = all[key]) {
            null -> defaultValue
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> defaultValue
        }
    }

internal fun SharedPreferences.safeGetBoolean(key: String, defaultValue: Boolean = false): Boolean =
    try {
        getBoolean(key, defaultValue)
    } catch (_: ClassCastException) {
        when (val value = all[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> defaultValue
            }
            else -> defaultValue
        }
    }

internal fun SharedPreferences.safeGetInt(key: String, defaultValue: Int = 0): Int =
    try {
        getInt(key, defaultValue)
    } catch (_: ClassCastException) {
        when (val value = all[key]) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: defaultValue
            is Boolean -> if (value) 1 else 0
            else -> defaultValue
        }
    }

internal fun SharedPreferences.safeGetLong(key: String, defaultValue: Long = 0L): Long =
    try {
        getLong(key, defaultValue)
    } catch (_: ClassCastException) {
        when (val value = all[key]) {
            is Long -> value
            is Int -> value.toLong()
            is Float -> value.toLong()
            is Double -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: defaultValue
            is Boolean -> if (value) 1L else 0L
            else -> defaultValue
        }
    }
