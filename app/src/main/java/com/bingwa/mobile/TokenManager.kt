package com.bingwa.mobile

import android.content.Context

class TokenManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("TokenStore", Context.MODE_PRIVATE)
    fun getBalance(): Int = prefs.safeGetInt(KEY_BALANCE, 0)
    fun addTokens(amount: Int) { setBalance(getBalance() + amount, fromRelay = false) }
    fun spendTokens(amount: Int): Boolean {
        val cur = getBalance()
        if (cur < amount) return false
        setBalance(cur - amount, fromRelay = false)
        return true
    }
    fun clearBalance(clearUnlimited: Boolean = true) {
        setBalance(0, fromRelay = false, notify = true)
        if (clearUnlimited) {
            UnlimitedManager(context).clear()
        }
    }

    fun setBalanceFromRelay(amount: Int) {
        setBalance(amount, fromRelay = true)
    }

    fun clearTemporaryRelayBalance(notify: Boolean = true) {
        if (!prefs.safeGetBoolean(KEY_TEMPORARY_RELAY_BALANCE, false)) return
        prefs.edit()
            .putInt(KEY_BALANCE, 0)
            .putBoolean(KEY_TEMPORARY_RELAY_BALANCE, false)
            .apply()
        if (notify) tokenBalanceListener?.invoke(0)
    }

    private fun setBalance(amount: Int, fromRelay: Boolean, notify: Boolean = true) {
        val clean = amount.coerceAtLeast(0)
        prefs.edit()
            .putInt(KEY_BALANCE, clean)
            .putBoolean(KEY_TEMPORARY_RELAY_BALANCE, fromRelay)
            .apply()
        if (notify) tokenBalanceListener?.invoke(clean)
        if (!fromRelay) RelayManager.syncTokenBalance(context, clean)
    }

    companion object {
        private const val KEY_BALANCE = "balance"
        private const val KEY_TEMPORARY_RELAY_BALANCE = "temporary_relay_balance"
        var tokenBalanceListener: ((Int) -> Unit)? = null
        fun convertAmountToTokens(ksh: Int): Int = when (ksh) {
            15 -> 105
            25 -> 255
            55 -> 605
            100 -> 1200
            else -> ksh
        }
    }
}
