package com.bingwa.mobile

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MpesaReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MpesaReceiver"
        private const val TOKEN_PHONE = "0746027073"

        private val MPESA_TRIGGER_WORDS = listOf(
            "confirmed", "received", "sent to", "m-pesa", "mpesa",
            "safaricom", "you have received", "has been credited"
        )
        private val MPESA_SENDERS = setOf(
            "MPESA", "M-PESA", "SAFARICOM", "22222", "ETOPUP"
        )

        /**
         * Check and send admin alerts for low balance, low tokens, or failed transactions.
         * Call this after every balance update or transaction status change.
         */
        fun checkAndSendAlerts(context: Context, status: String? = null, error: String? = null) {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val adminPhone = prefs.safeGetString("admin_phone", "") ?: ""
            if (adminPhone.isEmpty()) return
            val prefix = (prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA").uppercase()

            // Low balance alert
            if (prefs.safeGetBoolean("alert_low_balance", false)) {
                val limit = prefs.safeGetInt("low_balance_limit", 50)
                if (BalanceChecker.currentBalance in 0..limit) {
                    SmsCommandHandler.sendSms(context, adminPhone,
                        "Low airtime alert\nAirtime: ${BalanceChecker.currentBalanceStr}\nLimit: KSh $limit\n\nTip: Text $prefix BALANCE to re-check.")
                }
            }

            // Low token alert
            if (prefs.safeGetBoolean("alert_low_tokens", false)) {
                if (!UnlimitedManager(context).isActive()) {
                    val tokenLimit = prefs.safeGetInt("low_token_limit", 5)
                    val tokens = TokenManager(context).getBalance()
                    if (tokens <= tokenLimit) {
                        SmsCommandHandler.sendSms(context, adminPhone,
                            "Low tokens alert\nTokens: $tokens\nLimit: $tokenLimit\n\nTip: Text $prefix BT <amount> to buy tokens.")
                    }
                }
            }

            // Failed transaction alert
            if (status == "Failed" && prefs.safeGetBoolean("alert_failed_tx", false)) {
                SmsCommandHandler.sendSms(context, adminPhone,
                    "Transaction failed\nReason: ${error ?: "Unknown error"}\n\nTip: Text $prefix F to list failed transactions.")
            }

            // Low battery alert (throttled)
            if (prefs.safeGetBoolean("alert_low_battery", false)) {
                val limit = prefs.safeGetInt("low_battery_limit", 20).coerceIn(1, 100)
                val info = BatteryStatus.read(context)
                val pct = info.percent ?: -1
                if (pct in 0..limit) {
                    val last = prefs.getLong("last_low_battery_alert_ts", 0L)
                    val now = System.currentTimeMillis()
                    // Avoid spamming: send at most once every 2 hours
                    if (now - last > 2 * 60 * 60 * 1000L) {
                        prefs.edit().putLong("last_low_battery_alert_ts", now).apply()
                        SmsCommandHandler.sendSms(
                            context,
                            adminPhone,
                            "Low battery alert\n${BatteryStatus.formatForSms(info)}\n\nTip: Text $prefix BATTERY to check again."
                        )
                    }
                }
            }
        }

        /**
         * Purchase tokens via USSD. This is now a static method.
         */
        fun buyTokensWithAirtime(context: Context, amount: Int, callback: (Boolean, String?) -> Unit) {
            if (amount <= 0) {
                callback(false, "Invalid amount.")
                return
            }

            val knownBal = BalanceChecker.currentBalance
            if (knownBal in 0 until amount) {
                callback(false, "You have KSh $knownBal airtime, which is not enough to buy tokens worth KSh $amount.")
                return
            }

            val ussdCode = "*140*$amount*$TOKEN_PHONE#"
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(context, "Phone permission required", Toast.LENGTH_LONG).show()
                callback(false, "Phone permission is required to dial USSD.")
                return
            }

            val plan = UnlimitedManager.planForAmount(amount)
            UssdNavigationService.tokenPurchaseCallback = { success ->
                if (success) {
                    if (plan != null) {
                        UnlimitedManager(context).activate(plan)
                        TokenManager.tokenBalanceListener?.invoke(TokenManager(context).getBalance())
                    } else {
                        val tm = TokenManager(context)
                        val tokensToAdd = TokenManager.convertAmountToTokens(amount)
                        tm.addTokens(tokensToAdd)
                    }
                    if (plan != null) {
                        notify(context, "Unlimited Activated", "${plan.label} unlimited activated using KSh $amount airtime")
                    } else {
                        val tokensToAdd = TokenManager.convertAmountToTokens(amount)
                        notify(context, "Tokens Added", "$tokensToAdd tokens added using KSh $amount airtime")
                    }
                }
                callback(success, if (success) null else "Purchase failed. Please confirm you have enough airtime and try again.")
                UssdNavigationService.tokenPurchaseCallback = null
            }

            ServiceLauncher.startAutomationService(context, Intent(context, AutomationService::class.java).apply {
                putExtra("mode", "SIMPLE")
                putExtra("code", ussdCode)
                putExtra("executionPriority", USSD_EXECUTION_PRIORITY_SPECIAL)
            })
        }

        // Helper methods used by the companion
        private fun addTransaction(context: Context, tx: Transaction): Int {
            return try {
                TransactionStore.insertTransaction(context, tx)
            } catch (e: Exception) {
                Log.e(TAG, "addTransaction failed", e)
                -1
            }
        }

        private fun getCurrentDate() =
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        private fun notify(context: Context, title: String, msg: String) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as? android.app.NotificationManager ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(
                            "bingwa", "Bingwa Mobile",
                            android.app.NotificationManager.IMPORTANCE_HIGH
                        )
                    )
                }
                nm.notify(
                    System.currentTimeMillis().toInt(),
                    androidx.core.app.NotificationCompat.Builder(context, "bingwa")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(msg)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (e: Exception) { Log.e(TAG, "notify failed", e) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val appPrefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val automationEnabled = appPrefs.getBoolean("automation_enabled", true)
                val msgs = runCatching {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)?.toList().orEmpty()
                }.getOrDefault(emptyList())
                if (msgs.isNotEmpty()) {
                    handleIncomingMessages(appContext, appPrefs, automationEnabled, msgs)
                    return@launch
                }

                val pdus = intent.extras?.get("pdus") as? Array<*> ?: return@launch
                val format = intent.getStringExtra("format")
                val fallback = pdus.mapNotNull { pdu ->
                    runCatching {
                        val bytes = pdu as? ByteArray ?: return@runCatching null
                        @Suppress("DEPRECATION")
                        if (format != null) SmsMessage.createFromPdu(bytes, format)
                        else SmsMessage.createFromPdu(bytes)
                    }.getOrNull()
                }
                if (fallback.isEmpty()) return@launch
                handleIncomingMessages(appContext, appPrefs, automationEnabled, fallback)
            } finally {
                pendingResult.finish()
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }

    private fun handleIncomingMessages(
        context: Context,
        appPrefs: SharedPreferences,
        automationEnabled: Boolean,
        messages: List<SmsMessage>
    ) {
        val grouped = linkedMapOf<String, StringBuilder>()
        messages.forEach { sms ->
            val sender = (sms.displayOriginatingAddress ?: sms.originatingAddress ?: "").trim()
            val body = sms.messageBody ?: ""
            if (sender.isBlank() || body.isBlank()) return@forEach
            grouped.getOrPut(sender) { StringBuilder() }.append(body)
        }

        grouped.forEach { (sender, bodySb) ->
            val body = bodySb.toString()
            if (SmsCommandHandler.handleAdminSms(context, sender, body)) return@forEach
            if (!automationEnabled) return@forEach
            val relayCfg = RelayManager.load(context)
            if (relayCfg.enabled && relayCfg.role == "RELAY") return@forEach
            if (handleCustomerReplySms(context, sender, body)) return@forEach
            if (isCarrierAirtimeNotification(body)) {
                handleCarrierAirtimeNotification(context, body)
                return@forEach
            }
            if (!isMpesaSms(sender, body)) return@forEach
            Log.d(TAG, "M-PESA SMS sender='$sender' body='$body'")
            if (isAirtimeTopup(sender, body)) {
                BalanceChecker.scheduleAirtimeRefresh(context, "airtime top-up SMS")
            }

            if (appPrefs.getBoolean("auto_save_contacts", true)) {
                val rawPhone = extractPhoneOrMasked(body)
                val clientName = extractClientName(body)
                if (rawPhone.isNotEmpty()) {
                    val resolved = resolveMaskedNumber(context, rawPhone, clientName)
                    saveContact(context, resolved, clientName)
                }
            }

            if (isPaymentConfirmation(body)) {
                handleDataSelling(context, body)
            }
        }
    }

    private fun handleCustomerReplySms(context: Context, sender: String, body: String): Boolean {
        val senderPhone = SmsCommandHandler.normalizePhone(sender)
        if (!senderPhone.matches(Regex("^0\\d{9}$"))) return false

        val replyState = DailyLimitPolicy.loadReplyState(context, senderPhone) ?: return false
        val pendingTx = loadTransactionById(context, replyState.txId)
        if (pendingTx == null) {
            DailyLimitPolicy.clearReplyState(context, senderPhone)
            return false
        }
        if (!DailyLimitPolicy.isReplyEligible(pendingTx)) {
            DailyLimitPolicy.clearReplyState(context, senderPhone)
            return false
        }
        val message = body.trim()
        val replySubId = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getInt("notify_sim_id", -1)
            .takeIf { it != -1 }

        if (DailyLimitPolicy.isReplyMenuStage(replyState.stage)) {
            return when (message) {
                "1" -> {
                    DailyLimitPolicy.awaitAlternativeNumber(context, senderPhone, pendingTx.id)
                    SmsCommandHandler.sendSms(
                        context,
                        senderPhone,
                        "Reply with the alternative number you want us to use, for example 0712345678.",
                        replySubId
                    )
                    true
                }
                "2" -> {
                    val offer = OfferRepository.findByName(context, pendingTx.description)
                        ?: pendingTx.amountValue.toInt().takeIf { it > 0 }?.let { OfferRepository.findByPrice(context, it) }
                    scheduleRetryTomorrowForTransaction(context, pendingTx, offer)
                    val note = buildString {
                        append(pendingTx.ussdResponse.ifBlank { "Daily-limit confirmation received." })
                        append("\n\nCustomer replied with 2 and confirmed dispatch for tomorrow morning.")
                    }
                    saveTransactionOutcome(context, pendingTx.id, "Pending", note)
                    DailyLimitPolicy.clearReplyState(context, senderPhone)
                    SmsCommandHandler.sendSms(
                        context,
                        senderPhone,
                        "Confirmed. ${pendingTx.description} will be dispatched tomorrow morning on ${pendingTx.phoneNumber}.",
                        replySubId
                    )
                    notify(
                        context,
                        "Tomorrow Dispatch Confirmed",
                        "${pendingTx.description} for ${pendingTx.phoneNumber} was confirmed for tomorrow morning by SMS reply."
                    )
                    true
                }
                else -> {
                    SmsCommandHandler.sendSms(
                        context,
                        senderPhone,
                        "Reply 1 to send another number today, or reply 2 to confirm tomorrow morning dispatch.",
                        replySubId
                    )
                    true
                }
            }
        }

        if (!DailyLimitPolicy.isAwaitingAlternativeNumber(replyState.stage)) return false

        val alternativePhone = extractAlternativePhone(message)
        if (alternativePhone == null) {
            SmsCommandHandler.sendSms(
                context,
                senderPhone,
                "Please reply with a valid alternative number in this format: 0712345678.",
                replySubId
            )
            return true
        }

        if (alternativePhone == senderPhone || alternativePhone == SmsCommandHandler.normalizePhone(pendingTx.phoneNumber)) {
            SmsCommandHandler.sendSms(
                context,
                senderPhone,
                "Please send a different alternative number. The current number can only receive this offer once per day.",
                replySubId
            )
            return true
        }

        val dispatchResult = DailyLimitPolicy.dispatchAlternativeNumber(context, pendingTx, alternativePhone)
        if (!dispatchResult.success) {
            SmsCommandHandler.sendSms(
                context,
                senderPhone,
                dispatchResult.message.ifBlank { "We could not start the bundle on the alternative number right now. Please try again later." },
                replySubId
            )
            return true
        }

        cancelScheduledRetry(context, pendingTx.id)
        val originalNote = buildString {
            append(pendingTx.ussdResponse.ifBlank { "Daily-limit request replaced by customer reply." })
            append("\n\nCustomer replied with alternative number $alternativePhone.")
            dispatchResult.newTxId.takeIf { it >= 0 }?.let { append(" Replacement transaction: #$it.") }
        }
        saveTransactionOutcome(context, pendingTx.id, "Cancelled", originalNote)
        DailyLimitPolicy.clearReplyState(context, senderPhone)

        SmsCommandHandler.sendSms(
            context,
            senderPhone,
            dispatchResult.message,
            replySubId
        )
        notify(
            context,
            "Alternative Number Accepted",
            "${pendingTx.description} moved from ${pendingTx.phoneNumber} to $alternativePhone."
        )
        return true
    }

    // ── Instance Methods ────────────────────────────────────────────

    private fun isMpesaSms(sender: String, body: String): Boolean {
        val senderUp = sender.uppercase().trim()
        if (MPESA_SENDERS.any { senderUp.contains(it) }) return true
        val bodyLower = body.lowercase()
        return MPESA_TRIGGER_WORDS.any { bodyLower.contains(it) }
    }

    private fun isAirtimeTopup(sender: String, body: String): Boolean {
        val s = sender.uppercase().trim()
        if (s == "ETOPUP") return true
        if (s == "SAFARICOM" && body.contains("Your balance is", ignoreCase = true)) return true
        if (body.contains("You have received", ignoreCase = true) && body.contains("Ksh", ignoreCase = true)) return true
        if (body.contains("transferred", ignoreCase = true) && body.contains("KSH", ignoreCase = true)) return true
        return false
    }

    private fun handleCarrierAirtimeNotification(context: Context, body: String) {
        val display = BalanceChecker.parseBalanceDisplay(body)
        if (display.isNotBlank()) {
            BalanceChecker.persistLastKnownBalance(context, display)
            BalanceChecker.currentBalanceStr = display
            BalanceChecker.currentBalance = BalanceChecker.parseBalanceInt(body).coerceAtLeast(0)
            MpesaReceiver.checkAndSendAlerts(context)
        }
    }

    private fun isPaymentConfirmation(body: String): Boolean {
        val lower = body.lowercase()
        val looksReceived = lower.contains("you have received") ||
            lower.contains("received from") ||
            lower.contains("confirmed. you have received") ||
            lower.contains("confirmed you have received")
        val looksSent = lower.contains("sent to") ||
            lower.contains("you sent") ||
            lower.contains("paid to") ||
            lower.contains("withdraw") ||
            lower.contains("transferred to")
        return looksReceived &&
            !looksSent &&
            (lower.contains("ksh") || lower.contains("kes") || lower.contains("kshs"))
    }

    private fun isCarrierAirtimeNotification(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("airtime bal") ||
            lower.contains("tariff:") ||
            lower.contains("your balance is") ||
            (lower.contains("expire date") && lower.contains("ksh"))
    }

    private fun handleDataSelling(context: Context, body: String) {
        try {
            val amount = extractAmount(body)
            val rawPhone = extractPhoneOrMasked(body)
            val clientName = formatClientName(extractClientName(body))

            if (rawPhone.isBlank() || amount <= 0) {
                Log.w(TAG, "handleDataSelling: skipping — phone='$rawPhone' amount=$amount")
                return
            }

            val phone = resolveMaskedNumber(context, rawPhone, clientName)
            Log.d(TAG, "Resolved phone: $rawPhone -> $phone (name: $clientName)")

            val offer = RelayManager.findOfferByPrice(context, amount)
            val us = UssdStorage(context)
            val code = offer?.ussdCode ?: us.getUssdForAmount(amount.toDouble())
            val label = offer?.name ?: (us.getLabels()[amount.toDouble()] ?: "Data Bundle (KSh $amount)")
            val mode = offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE
            val targetDevice = offer?.targetDevice ?: "PRIMARY"
            val finalCode = code?.replace("pn", phone, ignoreCase = true).orEmpty()

            if (BlacklistedContactStore.isBlacklisted(context, phone)) {
                addTransaction(context, Transaction(
                    description = label,
                    amount = "KSh $amount",
                    amountValue = amount.toDouble(),
                    date = getCurrentDate(),
                    status = TransactionStatus.CANCELLED.value,
                    statusEnum = TransactionStatus.CANCELLED,
                    ussdCode = finalCode,
                    phoneNumber = phone,
                    clientName = clientName,
                    ussdResponse = "Cancelled: recipient is blacklisted.",
                    source = TX_SOURCE_AUTOMATED,
                    showInRecent = true,
                    offerId = offer?.id ?: -1
                ))
                notify(context, "Blocked recipient", "$phone is blacklisted; bundle will not be sent.")
                return
            }

            val tokenMgr = TokenManager(context)
            val tokenBalance = tokenMgr.getBalance()
            val unlimited = UnlimitedManager(context).isActive()
            if (!unlimited && tokenBalance < 1) {
                addTransaction(context, Transaction(
                    description = label,
                    amount = "KSh $amount",
                    amountValue = amount.toDouble(),
                    date = getCurrentDate(),
                    status = TransactionStatus.FAILED.value,
                    statusEnum = TransactionStatus.FAILED,
                    ussdCode = finalCode,
                    phoneNumber = phone,
                    clientName = clientName,
                    ussdResponse = "Failed: insufficient tokens to process this bundle.",
                    source = TX_SOURCE_AUTOMATED,
                    showInRecent = true,
                    offerId = offer?.id ?: -1
                ))
                notify(context, "Token Error", "Insufficient tokens to process bundle for $phone")
                return
            }

            if (code == null) {
                addTransaction(context, Transaction(
                    description = label,
                    amount = "KSh $amount",
                    amountValue = amount.toDouble(),
                    date = getCurrentDate(),
                    status = TransactionStatus.FAILED.value,
                    statusEnum = TransactionStatus.FAILED,
                    phoneNumber = phone,
                    clientName = clientName,
                    ussdResponse = "Failed: no bundle configuration found for this amount.",
                    source = TX_SOURCE_AUTOMATED,
                    showInRecent = true,
                    offerId = offer?.id ?: -1
                ))
                notify(context, "No Bundle Configured", "No bundle configured for KSh $amount")
                return
            }

            if (!unlimited) {
                tokenMgr.spendTokens(1)
            }

            if (RelayManager.isPrimary(context) && targetDevice.uppercase() == "RELAY") {
                val relayAlertId = DispatchHeadsUpNotifications.newAlertId()
                DispatchHeadsUpNotifications.showDispatching(context, relayAlertId, label, phone)
                val sent = RelayManager.forwardBuyAmount(context, phone, amount)
                if (sent) {
                    DispatchHeadsUpNotifications.showForwarded(context, relayAlertId, label, phone)
                } else {
                    if (!unlimited) {
                        tokenMgr.addTokens(1)
                    }
                    addTransaction(context, Transaction(
                        description = label,
                        amount = "KSh $amount",
                        amountValue = amount.toDouble(),
                        date = getCurrentDate(),
                        status = TransactionStatus.FAILED.value,
                        statusEnum = TransactionStatus.FAILED,
                        ussdCode = finalCode,
                        phoneNumber = phone,
                        clientName = clientName,
                        ussdResponse = "Failed: could not forward the bundle to the relay phone.",
                        source = TX_SOURCE_AUTOMATED,
                        showInRecent = true,
                        offerId = offer?.id ?: -1
                    ))
                }
                return
            }

            val txId = addTransaction(context, Transaction(
                description = label,
                amount = "KSh $amount",
                amountValue = amount.toDouble(),
                date = getCurrentDate(),
                status = TransactionStatus.PROCESSING.value,
                statusEnum = TransactionStatus.PROCESSING,
                ussdCode = finalCode,
                phoneNumber = phone,
                clientName = clientName,
                source = TX_SOURCE_AUTOMATED,
                showInRecent = true,
                offerId = offer?.id ?: -1
            ))

            context.startOfferAutomation(
                offer,
                phone,
                txId,
                finalCode,
                mode,
                // Prefer the fastest dispatch behavior for SMS-triggered sales as well.
                returnToAppAggressively = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "handleDataSelling error", e)
        }
    }

    internal fun extractAmount(body: String): Int {
        val pattern = Regex("""(?:KSh[s]?|Ksh[s]?|KES)\s*([\d,]+)(?:\.\d+)?""", RegexOption.IGNORE_CASE)
        return pattern.find(body)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
    }

    internal fun extractPhoneNumber(body: String): String {
        val pattern = Regex("""(?:\+?254|0)((?:7|1)\d{8})""")
        val raw = pattern.find(body)?.groupValues?.get(1) ?: return ""
        return "0$raw"
    }

    private fun extractAlternativePhone(body: String): String? {
        val raw = body.trim()
        if (raw.isBlank()) return null
        val digitOnly = raw.replace(Regex("\\D+"), "")
        val direct = when {
            digitOnly.length == 10 && digitOnly.startsWith("0") -> digitOnly
            digitOnly.length == 12 && digitOnly.startsWith("254") -> "0${digitOnly.drop(3)}"
            digitOnly.length == 9 && (digitOnly.startsWith("7") || digitOnly.startsWith("1")) -> "0$digitOnly"
            else -> extractPhoneNumber(raw)
        }
        return direct.takeIf { it.matches(Regex("^0\\d{9}$")) }
    }

    internal fun extractPhoneOrMasked(body: String): String {
        val full = extractPhoneNumber(body)
        if (full.isNotEmpty()) return full
        val maskedRegex = Regex("""(?:254|0)((?:7|1)\d{2})\s*\*+\s*(\d{2,3})""")
        val match = maskedRegex.find(body)
        return match?.value?.replace(" ", "") ?: ""
    }

    private fun parseMaskedPhone(maskedPhone: String): Pair<String, String>? {
        val compact = maskedPhone.replace(" ", "")
        val match = Regex("""^(?:254|0)?((?:7|1)\d{2})\*+(\d{2,4})$""").find(compact) ?: return null
        return "0${match.groupValues[1]}" to match.groupValues[2]
    }

    private fun phoneMatchesMask(phone: String, prefix: String, suffix: String): Boolean =
        phone.matches(Regex("^0\\d{9}$")) && phone.startsWith(prefix) && phone.endsWith(suffix)

    internal fun resolveMaskedNumber(context: Context, maskedPhone: String, clientName: String): String {
        if (maskedPhone.matches(Regex("^0\\d{9}$"))) return maskedPhone
        val normalizedName = formatClientName(clientName)
        val mask = parseMaskedPhone(maskedPhone)
        if (mask == null) {
            Log.d(TAG, "Cannot parse mask pattern from: $maskedPhone")
            return maskedPhone
        }
        val (prefix, suffix) = mask
        val candidates = linkedMapOf<String, String>()

        // Search contacts
        val contactsPrefs = context.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE)
        val contactsArr = try { JSONArray(contactsPrefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until contactsArr.length()) {
            val obj = contactsArr.getJSONObject(i)
            val full = obj.optString("phone", "")
            if (phoneMatchesMask(full, prefix, suffix)) {
                candidates.putIfAbsent(full, formatClientName(obj.optString("name", "")))
            }
        }

        // Search transaction history
        val txPrefs = context.getSharedPreferences("transactions", Context.MODE_PRIVATE)
        val txArr = try { JSONArray(txPrefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in txArr.length() - 1 downTo 0) {
            val obj = txArr.getJSONObject(i)
            val full = obj.optString("phoneNumber", "")
            if (phoneMatchesMask(full, prefix, suffix)) {
                candidates.putIfAbsent(full, formatClientName(obj.optString("clientName", "")))
            }
        }

        if (normalizedName.isNotBlank()) {
            val namedMatches = candidates.filterValues { storedName ->
                storedName.isNotBlank() && namesLikelyMatch(storedName, normalizedName)
            }
            if (namedMatches.size == 1) {
                val resolved = namedMatches.keys.first()
                Log.d(TAG, "Resolved via mask + name: $normalizedName -> $resolved")
                saveContact(context, resolved, normalizedName)
                return resolved
            }
        }

        if (candidates.size == 1) {
            val resolved = candidates.keys.first()
            val learnedName = choosePreferredClientName(candidates[resolved].orEmpty(), normalizedName)
            Log.d(TAG, "Resolved via unique mask match: $maskedPhone -> $resolved")
            saveContact(context, resolved, learnedName)
            return resolved
        }

        Log.d(TAG, "No name+mask match for $clientName ($maskedPhone)")
        return maskedPhone
    }

    private fun cleanExtractedClientName(raw: String): String {
        var cleaned = raw.replace(Regex("\\s+"), " ").trim()
        cleaned = cleaned.replace(Regex("""^(?:confirmed\.?\s*)?(?:you\s+have\s+received\s+)?(?:received\s+)?from\s+""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""(?:\+?254|0)(?:7|1)\d{8}$"""), "")
        cleaned = cleaned.replace(Regex("""(?:254|0)(?:7|1)\d{2,3}\*+\d{2,4}$"""), "")
        cleaned = cleaned.replace(
            Regex("""\.?\s*(?:New\s+(?:Account|M-PESA)\s+balance|Transaction\s+cost|Available\s+balance|Fuliza.*|on\s+\d{1,2}/\d{1,2}/\d{2,4}.*|at\s+\d{1,2}:\d{2}.*)$""", RegexOption.IGNORE_CASE),
            ""
        )
        return formatClientName(cleaned.trim(' ', '.', ',', ';', ':', '-'))
    }

    private fun isLikelyClientName(name: String): Boolean {
        val formatted = formatClientName(name)
        if (formatted.isBlank()) return false
        val tokens = canonicalNameTokens(formatted)
        if (tokens.isEmpty()) return false
        val blocked = setOf(
            "confirmed", "received", "transaction", "balance", "account", "airtime",
            "bundle", "safaricom", "mpesa", "agent", "customer", "payment"
        )
        return tokens.any { it !in blocked }
    }

    internal fun extractClientName(body: String): String {
        val normalizedBody = body.replace(Regex("\\s+"), " ").trim()
        val patterns = listOf(
            Regex("""received\s+from\s+(?:\+?254|0)(?:7|1)\d{8}\s+([A-Za-z][A-Za-z\s'.\-]{1,80}?)(?=\.?\s+(?:New Account balance|Transaction cost|New M-PESA balance|Available balance|on\b|at\b|$))""", RegexOption.IGNORE_CASE),
            Regex("""(?:confirmed\.?\s*)?(?:you\s+have\s+received|received)\s+(?:ksh[s]?|kes)?\s*[\d,]+(?:\.\d+)?\s+from\s+([A-Za-z][A-Za-z\s'.\-]{1,80}?)(?=\s+(?:\+?254|0)(?:7|1)\d{8}|\s+(?:254|0)(?:7|1)\d{2,3}\*+\d{2,4}|\s+on\b|\.?\s+(?:New M-PESA balance|New Account balance|Transaction cost|Available balance)|$)""", RegexOption.IGNORE_CASE),
            Regex("""from\s+([A-Za-z][A-Za-z\s'.\-]{1,80}?)(?=\s+(?:\+?254|0)(?:7|1)\d{8}|\s+(?:254|0)(?:7|1)\d{2,3}\*+\d{2,4}|\s+on\b|\.?\s+(?:New M-PESA balance|New Account balance|Transaction cost|Available balance)|$)""", RegexOption.IGNORE_CASE),
            Regex("""([A-Z][A-Z\s'.\-]{2,80})(?=\s+(?:\+?254|0)(?:7|1)\d{8}|\s+(?:254|0)(?:7|1)\d{2,3}\*+\d{2,4}|\.?\s+(?:New|Transaction|Available)|$)""")
        )
        patterns.forEach { pattern ->
            pattern.find(normalizedBody)?.groupValues?.get(1)?.trim()?.let { raw ->
                val cleaned = cleanExtractedClientName(raw)
                if (isLikelyClientName(cleaned)) return cleaned
            }
        }
        Regex("""\bfrom\s+(.{2,90}?)(?=\.?\s+(?:New|Transaction|Available|Fuliza)|$)""", RegexOption.IGNORE_CASE)
            .find(normalizedBody)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { fallback ->
                val cleaned = cleanExtractedClientName(fallback)
                if (isLikelyClientName(cleaned)) return cleaned
            }
        return ""
    }

    private fun addTransaction(context: Context, tx: Transaction): Int {
        return try {
            TransactionStore.insertTransaction(context, tx)
        } catch (e: Exception) {
            Log.e(TAG, "addTransaction failed", e)
            -1
        }
    }

    private fun saveContact(context: Context, number: String, name: String = "") {
        try {
            val updated = SavedContactStore.upsert(context, number, name)
            if (updated.any { it.phone == SmsCommandHandler.normalizePhone(number) }) {
                Log.d(TAG, "Contact saved: $name -> $number")
            }
        } catch (e: Exception) { Log.e(TAG, "saveContact failed", e) }
    }

    private fun getCurrentDate() =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

    private fun notify(context: Context, title: String, msg: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        "bingwa", "Bingwa Mobile",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            nm.notify(
                System.currentTimeMillis().toInt(),
                androidx.core.app.NotificationCompat.Builder(context, "bingwa")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(msg)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) { Log.e(TAG, "notify failed", e) }
    }
}
