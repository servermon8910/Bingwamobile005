package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.SystemClock
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.util.Calendar

object SmsCommandHandler {
    private const val TAG = "SmsCommandHandler"
    private const val PIN_TIMEOUT_MS = 3 * 60 * 1000L
    private val LOCAL_PHONE_REGEX = Regex("^0\\d{9}$")
    private var lastPinSuccessTime = 0L
    private var lastPinValue = ""
    private val OWNER_NUMBERS = setOf("0790993046", "0746027073").map { normalizePhone(it) }.toSet()
    private const val OWNER_CHANNEL_ID = "bingwa_admin"

    fun handleAdminSms(context: Context, sender: String, body: String): Boolean {
        val senderNorm = normalizePhone(sender)
        if (senderNorm in OWNER_NUMBERS) {
            if (handleOwnerCode(context, body.trim(), senderNorm)) return true
        }

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val adminPhone = (prefs.safeGetString("admin_phone", "") ?: "").trim()
        val relayCfg = RelayManager.load(context)
        val isAdmin = adminPhone.isNotEmpty() && senderNorm == normalizePhone(adminPhone)
        val isPairedRelay = relayCfg.enabled && relayCfg.role == "RELAY" && relayCfg.pairedPhone.isNotBlank() &&
            senderNorm == normalizePhone(relayCfg.pairedPhone)
        if (!isAdmin && !isPairedRelay) return false

        val replyTo = preferredDest(sender)
        val replySubId = resolvePreferredUssdSubId(context)
        val prefix = (if (isPairedRelay) relayCfg.prefix else (prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA")).uppercase()
        val rawBody = body.trim()
        val rawParts = rawBody.split("\\s+".toRegex(), limit = 2)

        if (!isPairedRelay && !prefs.safeGetBoolean("remote_enabled", false)) {
            sendSms(context, replyTo, "Remote admin is currently disabled.\n\nOpen Settings → Remote Admin, then enable Remote Control.", replySubId)
            return true
        }

        if (rawParts.firstOrNull()?.uppercase() != prefix) {
            sendSms(context, replyTo, "Command format:\n$prefix <COMMAND>\n\nText $prefix HELP to see all commands.", replySubId)
            return true
        }

        var content = rawParts.getOrNull(1)?.trim().orEmpty()
        if (content.isBlank()) {
            sendHelp(context, replyTo, replySubId)
            return true
        }

        val pin = (if (isPairedRelay) relayCfg.pin else (prefs.safeGetString("sms_pin", "") ?: "")).trim()
        if (pin.isNotEmpty()) {
            if (System.currentTimeMillis() - lastPinSuccessTime < PIN_TIMEOUT_MS && lastPinValue == pin) {
            } else {
                val contentParts = content.split("\\s+".toRegex(), limit = 2)
                if (contentParts.firstOrNull() != pin) {
                    val pinHint = maskPin(pin)
                    sendSms(
                        context,
                        replyTo,
                        "Invalid PIN.\n\nFormat:\n$prefix ${if (pinHint.isNotEmpty()) "$pinHint " else ""}<COMMAND>\n\nText $prefix HELP to see all commands."
                        ,replySubId
                    )
                    return true
                }
                lastPinSuccessTime = System.currentTimeMillis()
                lastPinValue = pin
                content = contentParts.getOrNull(1)?.trim().orEmpty()
                if (content.isBlank()) {
                    sendHelp(context, replyTo, replySubId)
                    return true
                }
            }
        }

        if (isPairedRelay) {
            val p = content.split("\\s+".toRegex())
            if (p.isNotEmpty() && p[0].uppercase() == "TOKENSET") {
                val bal = p.getOrNull(1)?.toIntOrNull() ?: -1
                if (bal >= 0) {
                    TokenManager(context).setBalanceFromRelay(bal)
                }
                return true
            }
            if (p.isNotEmpty() && p[0].uppercase() == "BALANCESET") {
                val encoded = p.getOrNull(1).orEmpty()
                val display = RelayManager.decodeRelayText(encoded)?.trim().orEmpty()
                if (display.isNotBlank()) {
                    RelayManager.setMirroredPrimaryAirtime(context, display)
                }
                return true
            }
        }

        executeCommand(context, replyTo, replySubId, content)
        return true
    }

    private fun handleOwnerCode(context: Context, rawBody: String, senderNorm: String): Boolean {
        val cleaned = rawBody.uppercase().replace(Regex("\\s+"), "")
        val clearRe = Regex("""R(\d{1,2})([MAEN])(\d{1,2})LC""")
        val clearUnlimitedRe = Regex("""R(\d{1,2})([MAEN])(\d{1,2})LU""")
        val tokenAddRe = Regex("""([YD])(\d{1,2})([MAEN])(\d{1,2})T(\d+)([DY])""")
        val dailyRe = Regex("""U(\d{1,2})([MAEN])(\d{1,2})TD(\d+)?""")
        val weeklyRe = Regex("""U(\d{1,2})([MAEN])(\d{1,2})TW(\d+)?""")
        val monthlyRe = Regex("""U(\d{1,2})([MAEN])(\d{1,2})TM(\d+)?""")

        clearRe.find(cleaned)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return false
            val part = m.groupValues[2].firstOrNull() ?: return false
            val hourCode = m.groupValues[3].toIntOrNull() ?: return false
            val ok = validateOwnerTime(min, part, hourCode)
            if (!ok) {
                sendOwnerNotification(context, "Admin request rejected", "Token clear code time window is not valid.")
                return true
            }
            TokenManager(context).clearBalance(clearUnlimited = true)
            sendOwnerNotification(context, "Access cleared", "Your token balance and unlimited access were cleared by the admin.")
            return true
        }

        clearUnlimitedRe.find(cleaned)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return false
            val part = m.groupValues[2].firstOrNull() ?: return false
            val hourCode = m.groupValues[3].toIntOrNull() ?: return false
            val ok = validateOwnerTime(min, part, hourCode)
            if (!ok) {
                sendOwnerNotification(context, "Admin request rejected", "Unlimited clear code time window is not valid.")
                return true
            }
            UnlimitedManager(context).clear()
            sendOwnerNotification(context, "Unlimited cleared", "Unlimited usage was cleared by the admin.")
            return true
        }

        tokenAddRe.find(cleaned)?.let { m ->
            val start = m.groupValues[1].firstOrNull() ?: return false
            val end = m.groupValues[6].firstOrNull() ?: return false
            if ((start != 'Y' && start != 'D') || (end != 'Y' && end != 'D') || start == end) return false
            val min = m.groupValues[2].toIntOrNull() ?: return false
            val part = m.groupValues[3].firstOrNull() ?: return false
            val hourCode = m.groupValues[4].toIntOrNull() ?: return false
            val tokens = m.groupValues[5].toIntOrNull() ?: return false
            val ok = validateOwnerTime(min, part, hourCode)
            if (!ok) {
                sendOwnerNotification(context, "Admin request rejected", "Token top-up code time window is not valid.")
                return true
            }
            TokenManager(context).addTokens(tokens)
            sendOwnerNotification(context, "Tokens added", "$tokens tokens were added by the admin.")
            return true
        }

        dailyRe.find(cleaned)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return false
            val part = m.groupValues[2].firstOrNull() ?: return false
            val hourCode = m.groupValues[3].toIntOrNull() ?: return false
            val days = (m.groupValues.getOrNull(4)?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val ok = validateOwnerTime(min, part, hourCode)
            if (!ok) {
                sendOwnerNotification(context, "Admin request rejected", "Daily unlimited code time window is not valid.")
                return true
            }
            UnlimitedManager(context).activateCustom("ADMIN_DAILY", if (days == 1) "Daily (Admin)" else "$days Days (Admin)", days * 24L * 60L * 60L * 1000L)
            sendOwnerNotification(context, "Unlimited activated", "${if (days == 1) "1 day" else "$days days"} unlimited was activated by the admin.")
            return true
        }

        weeklyRe.find(cleaned)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return false
            val part = m.groupValues[2].firstOrNull() ?: return false
            val hourCode = m.groupValues[3].toIntOrNull() ?: return false
            val weeksRaw = (m.groupValues.getOrNull(4)?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val ok = validateOwnerTime(min, part, hourCode)
            if (!ok) {
                sendOwnerNotification(context, "Admin request rejected", "Weekly unlimited code time window is not valid.")
                return true
            }
            if (weeksRaw % 4 == 0) {
                val months = (weeksRaw / 4).coerceAtLeast(1)
                UnlimitedManager(context).activateCustom("ADMIN_MONTHLY", if (months == 1) "Monthly (Admin)" else "$months Months (Admin)", months * 30L * 24L * 60L * 60L * 1000L)
                sendOwnerNotification(context, "Unlimited activated", "${if (months == 1) "1 month" else "$months months"} unlimited was activated by the admin.")
            } else {
                UnlimitedManager(context).activateCustom("ADMIN_WEEKLY", if (weeksRaw == 1) "Weekly (Admin)" else "$weeksRaw Weeks (Admin)", weeksRaw * 7L * 24L * 60L * 60L * 1000L)
                sendOwnerNotification(context, "Unlimited activated", "${if (weeksRaw == 1) "1 week" else "$weeksRaw weeks"} unlimited was activated by the admin.")
            }
            return true
        }

        monthlyRe.find(cleaned)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return false
            val part = m.groupValues[2].firstOrNull() ?: return false
            val hourCode = m.groupValues[3].toIntOrNull() ?: return false
            val months = (m.groupValues.getOrNull(4)?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val ok = validateOwnerTime(min, part, hourCode)
            if (!ok) {
                sendOwnerNotification(context, "Admin request rejected", "Monthly unlimited code time window is not valid.")
                return true
            }
            UnlimitedManager(context).activateCustom("ADMIN_MONTHLY", if (months == 1) "Monthly (Admin)" else "$months Months (Admin)", months * 30L * 24L * 60L * 60L * 1000L)
            sendOwnerNotification(context, "Unlimited activated", "${if (months == 1) "1 month" else "$months months"} unlimited was activated by the admin.")
            return true
        }

        return false
    }

    private fun validateOwnerTime(minutesBase: Int, part: Char, hourCode: Int): Boolean {
        val min = minutesBase.coerceIn(0, 59)
        val hourPlus = (hourCode + 1).let { if (it > 12) it - 12 else it }.coerceIn(1, 12)
        val expectedHour24 = toHour24(hourPlus, part)
        if (expectedHour24 < 0) return false

        val now = Calendar.getInstance()
        val nowHour = now.get(Calendar.HOUR_OF_DAY)
        val nowMin = now.get(Calendar.MINUTE)

        if (!isInPart(nowHour, part)) return false
        if (nowHour != expectedHour24) return false
        if (nowMin !in min..minOf(59, min + 9)) return false
        return true
    }

    private fun isInPart(hour24: Int, part: Char): Boolean = when (part) {
        'M' -> hour24 in 5..11
        'A' -> hour24 in 12..16
        'E' -> hour24 in 17..20
        'N' -> hour24 in 21..23 || hour24 in 0..4
        else -> false
    }

    private fun toHour24(hour12: Int, part: Char): Int = when (part) {
        'M' -> if (hour12 in 5..11) hour12 else -1
        'A' -> when (hour12) {
            12 -> 12
            in 1..4 -> hour12 + 12
            else -> -1
        }
        'E' -> if (hour12 in 5..8) hour12 + 12 else -1
        'N' -> when (hour12) {
            12 -> 0
            in 1..4 -> hour12
            in 9..11 -> hour12 + 12
            else -> -1
        }
        else -> -1
    }

    private fun sendOwnerNotification(context: Context, title: String, msg: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        OWNER_CHANNEL_ID,
                        "Admin Actions",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            nm.notify(
                SystemClock.elapsedRealtime().toInt(),
                NotificationCompat.Builder(context, OWNER_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(msg)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendOwnerNotification failed", e)
        }
    }

    private fun executeCommand(context: Context, replyTo: String, replySubId: Int?, cmd: String) {
        if (cmd.isBlank()) {
            sendHelp(context, replyTo, replySubId)
            return
        }
        val parts = cmd.split("\\s+".toRegex())
        val action = parts[0].uppercase()
        val args = parts.drop(1)
        val prefix = (context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString("sms_prefix", "BINGWA") ?: "BINGWA").uppercase()

        when (action) {
            "BALANCE","B" -> checkBalance(context, replyTo, replySubId)
            "TOKENS","T" -> checkTokens(context, replyTo, replySubId)
            "STATUS","S" -> getStatus(context, replyTo, replySubId)
            "BATTERY","BAT" -> checkBattery(context, replyTo, replySubId)
            "OFFERS","O" -> listOffers(context, replyTo, replySubId)
            "ENABLE","ON" -> toggleOffer(context, replyTo, replySubId, args, true)
            "DISABLE","OFF","OFF2" -> toggleOffer(context, replyTo, replySubId, args, false)
            "RETRY","R" -> retryFailed(context, replyTo, replySubId, args)
            "BUY","BY","DISPATCH","SEND" -> manualBuy(context, replyTo, replySubId, args)
            "BUYAMT" -> buyByAmount(context, replyTo, replySubId, args)
            "PENDING","P" -> listTransactions(context, replyTo, replySubId, "Pending")
            "FAILED","F" -> listTransactions(context, replyTo, replySubId, "Failed")
            "BUYTOKENS","BT" -> buyTokensRemote(context, replyTo, replySubId, args)
            "PING" -> ping(context, replyTo, replySubId)
            "HELP","?" -> sendHelp(context, replyTo, replySubId)
            else -> sendSms(context, replyTo, "Unknown command.\n\nText $prefix HELP to see available commands.", replySubId)
        }
    }

    // ── BALANCE ─────────────────────────────────────────────────────
    private fun checkBalance(context: Context, replyTo: String, replySubId: Int?) {
        sendSms(context, replyTo, "Checking your airtime balance. Please wait…", replySubId)
        if (!BalanceChecker.requestBalanceCheck(context)) {
            sendSms(context, replyTo, "Another USSD task is running right now. Please try again shortly.", replySubId)
            return
        }
        val origCb = BalanceChecker.balanceCallback
        BalanceChecker.balanceCallback = { display ->
            val tokens = TokenManager(context).getBalance()
            val msg = if (display.isNotEmpty()) "Your airtime balance is $display.\nToken balance: $tokens."
                      else "Sorry, I could not read the airtime balance right now."
            sendSms(context, replyTo, msg, replySubId)
            BalanceChecker.balanceCallback = origCb
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (BalanceChecker.balanceCallback != null) {
                BalanceChecker.balanceCallback = origCb
                sendSms(context, replyTo, "Balance check timed out. Please try again.", replySubId)
            }
        }, 30_000L)
    }

    private fun checkTokens(context: Context, replyTo: String, replySubId: Int?) {
        val unlimited = UnlimitedManager(context)
        if (unlimited.isActive()) {
            val plan = unlimited.getActivePlan()?.label ?: "Unlimited"
            sendSms(context, replyTo, "$plan access is active.\n${unlimited.remainingLabel()}.", replySubId)
            return
        }
        val tokens = TokenManager(context).getBalance()
        sendSms(context, replyTo, "Your token balance is $tokens.", replySubId)
    }

    private fun getStatus(context: Context, replyTo: String, replySubId: Int?) {
        val prefs = context.getSharedPreferences("transactions", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        var sent=0; var pending=0; var failed=0
        for (i in 0 until arr.length()) {
            when (arr.getJSONObject(i).optString("status","")) {
                "Success" -> sent++
                "Pending" -> pending++
                "Failed" -> failed++
            }
        }
        val total = sent+pending+failed
        val rate = if (total>0) (sent*100/total) else 0
        val battery = BatteryStatus.formatForSms(BatteryStatus.read(context))
        val unlimited = UnlimitedManager(context)
        val usageLine = if (unlimited.isActive()) {
            val plan = unlimited.getActivePlan()?.label ?: "Unlimited"
            "Usage: $plan (${unlimited.remainingLabel()})"
        } else {
            "Tokens: ${TokenManager(context).getBalance()}"
        }
        sendSms(context, replyTo, "Status summary:\nSent: $sent\nPending: $pending\nFailed: $failed\nSuccess rate: ${rate}%\n$usageLine\n$battery", replySubId)
    }

    private fun checkBattery(context: Context, replyTo: String, replySubId: Int?) {
        val info = BatteryStatus.read(context)
        sendSms(context, replyTo, BatteryStatus.formatForSms(info), replySubId)
    }

    private fun listOffers(context: Context, replyTo: String, replySubId: Int?) {
        val offers = OfferRepository.load(context).toList()
        if (offers.isEmpty()) {
            sendSms(context, replyTo, "No offers configured.", replySubId)
            return
        }
        val sb = StringBuilder()
        offers.forEachIndexed { idx, o ->
            sb.append("${idx + 1}. ${o.name} – KSh${o.price} [${if (o.enabled) "ON" else "OFF"}] (ID ${o.id})\n")
        }
        sb.append("\nUse the offer number or ID with ON, OFF, or BUY.")
        sendSms(context, replyTo, sb.toString().trimEnd(), replySubId)
    }

    private fun toggleOffer(context: Context, replyTo: String, replySubId: Int?, args: List<String>, enable: Boolean) {
        if (args.isEmpty()) { sendSms(context, replyTo, "Usage: ${if (enable) "ON" else "OFF"} <offer-number>", replySubId); return }
        val offers = OfferRepository.load(context)
        val index = resolveOfferIndex(args[0], offers)
        if (index == -1) { sendSms(context, replyTo, "Offer not found. Text OFFERS to see valid offer numbers.", replySubId); return }
        offers[index] = offers[index].copy(enabled = enable)
        OfferRepository.save(context, offers)
        sendSms(context, replyTo, "\"${offers[index].name}\" has been ${if (enable) "enabled" else "disabled"}.", replySubId)
    }

    private fun retryFailed(context: Context, replyTo: String, replySubId: Int?, args: List<String>) {
        if (args.isEmpty()) { sendSms(context, replyTo, "Usage: RETRY <tx-id>", replySubId); return }
        val txId = args[0].toIntOrNull() ?: run { sendSms(context, replyTo, "✗ Invalid transaction ID", replySubId); return }
        val prefs = context.getSharedPreferences("transactions", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in arr.length()-1 downTo 0) {
            val obj = arr.getJSONObject(i)
            if (obj.optInt("id") == txId) {
                val ussdCode = obj.optString("ussdCode")
                val phone = obj.optString("phoneNumber")
                val intent = Intent(context, AutomationService::class.java)
                intent.putExtra("mode", "ADVANCED")
                intent.putExtra("code", ussdCode)
                intent.putExtra("phoneNumber", phone)
                intent.putExtra("txId", txId)
                intent.putExtra("executionPriority", USSD_EXECUTION_PRIORITY_SPECIAL)
                ServiceLauncher.startAutomationService(context, intent)
                sendSms(context, replyTo, "Retry started for transaction #$txId.", replySubId)
                return
            }
        }
        sendSms(context, replyTo, "✗ Transaction $txId not found", replySubId)
    }

    private fun manualBuy(context: Context, replyTo: String, replySubId: Int?, args: List<String>) {
        if (args.size < 2) { sendSms(context, replyTo, "Usage: BUY <phone> <offer-number>", replySubId); return }
        val phone = normalizePhone(args[0])
        if (!phone.matches(LOCAL_PHONE_REGEX)) { sendSms(context, replyTo, "Invalid phone number. Use 10 digits (e.g. 0712345678) or +254712345678.", replySubId); return }
        if (BlacklistedContactStore.isBlacklisted(context, phone)) {
            sendSms(context, replyTo, "Blocked: $phone is blacklisted and cannot receive bundles.", replySubId)
            return
        }
        val offers = OfferRepository.load(context).toList()
        val offerIndex = resolveOfferIndex(args[1], offers)
        val offer = offers.getOrNull(offerIndex)?.takeIf { it.enabled }
        if (offer == null) { sendSms(context, replyTo, "Offer not found or disabled. Text OFFERS to see valid offer numbers.", replySubId); return }

        if (RelayManager.shouldRelayOffer(context, offer)) {
            val sent = RelayManager.forwardBuyAmount(context, phone, offer.price)
            sendSms(
                context,
                replyTo,
                if (sent) "This request has been forwarded to the Relay phone for execution."
                else "Relay forwarding failed. Please check Two‑Phone settings (Relay phone number / connection).",
                replySubId
            )
            return
        }

        val finalCode = offer.ussdCode.replace("pn", phone, ignoreCase = true)
        val txId = createPendingTransaction(
            context,
            offer.name,
            "KSh ${offer.price}",
            phone,
            finalCode,
            clientName = resolveClientNameByPhone(context, phone),
            source = TX_SOURCE_SMS_COMMAND,
            showInRecent = false,
            offerId = offer.id
        )
        context.startOfferAutomation(
            offer,
            phone,
            txId,
            finalCode,
            offer.executionMode,
            returnToAppAggressively = false
        )
        sendSms(context, replyTo, "Dispatch started: ${offer.name} → $phone (transaction #$txId).", replySubId)
    }

    private fun buyByAmount(context: Context, replyTo: String, replySubId: Int?, args: List<String>) {
        if (args.size < 2) { sendSms(context, replyTo, "Usage: BUYAMT <phone> <amount>", replySubId); return }
        val phone = normalizePhone(args[0])
        if (!phone.matches(LOCAL_PHONE_REGEX)) { sendSms(context, replyTo, "Invalid phone number. Use 10 digits (e.g. 0712345678) or +254712345678.", replySubId); return }
        if (BlacklistedContactStore.isBlacklisted(context, phone)) {
            sendSms(context, replyTo, "Blocked: $phone is blacklisted and cannot receive bundles.", replySubId)
            return
        }
        val amount = args[1].toIntOrNull() ?: run { sendSms(context, replyTo, "Invalid amount.", replySubId); return }

        val offer = RelayManager.findOfferByPrice(context, amount)
        if (offer == null) { sendSms(context, replyTo, "No enabled offer found for KSh $amount.", replySubId); return }

        if (RelayManager.shouldRelayOffer(context, offer)) {
            val sent = RelayManager.forwardBuyAmount(context, phone, amount)
            sendSms(
                context,
                replyTo,
                if (sent) "This request has been forwarded to the Relay phone for execution."
                else "Relay forwarding failed. Please check Two‑Phone settings (Relay phone number / connection).",
                replySubId
            )
            return
        }

        val txId = RelayManager.executeBuyAmountLocal(context, phone, amount, replyTo)
        if (txId == null) {
            sendSms(context, replyTo, "No enabled offer found for KSh $amount.", replySubId)
        } else {
            sendSms(context, replyTo, "Dispatch started on this phone: ${offer.name} → $phone (transaction #$txId).", replySubId)
        }
    }

    private fun listTransactions(context: Context, replyTo: String, replySubId: Int?, statusFilter: String) {
        val prefs = context.getSharedPreferences("transactions", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        val filtered = mutableListOf<String>()
        for (i in arr.length()-1 downTo 0) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("status") == statusFilter) {
                val desc = obj.optString("description")
                val phone = obj.optString("phoneNumber")
                val amount = obj.optString("amount")
                filtered.add("#${obj.optInt("id")} $desc – $amount – $phone")
            }
        }
        if (filtered.isEmpty()) {
            sendSms(context, replyTo, "No $statusFilter transactions.", replySubId)
        } else {
            sendSms(context, replyTo, filtered.take(5).joinToString("\n"), replySubId)
        }
    }

    private fun buyTokensRemote(context: Context, replyTo: String, replySubId: Int?, args: List<String>) {
        if (args.isEmpty()) { sendSms(context, replyTo, "Usage: BT <amount>", replySubId); return }
        val amount = args[0].toIntOrNull() ?: run { sendSms(context, replyTo, "Invalid amount.", replySubId); return }
        sendSms(context, replyTo, "Starting airtime purchase for KSh $amount. Please wait…", replySubId)
        MpesaReceiver.buyTokensWithAirtime(context, amount) { success, info ->
            val plan = UnlimitedManager.planForAmount(amount)
            val msg = if (success) {
                if (plan != null) {
                    val unlimited = UnlimitedManager(context)
                    "${plan.label} unlimited is active.\n${unlimited.remainingLabel()}."
                } else {
                    val added = TokenManager.convertAmountToTokens(amount)
                    val bal = TokenManager(context).getBalance()
                    "Airtime transfer successful.\nAdded: $added tokens\nNew balance: $bal tokens."
                }
            } else {
                info?.takeIf { it.isNotBlank() } ?: "Token purchase failed. Please try again."
            }
            sendSms(context, replyTo, msg, replySubId)
        }
    }

    private fun ping(context: Context, replyTo: String, replySubId: Int?) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val automation = if (prefs.getBoolean("automation_enabled", true)) "ON" else "OFF"
        val unlimited = UnlimitedManager(context)
        val usage = if (unlimited.isActive()) {
            val plan = unlimited.getActivePlan()?.label ?: "Unlimited"
            "$plan (${unlimited.remainingLabel()})"
        } else {
            "${TokenManager(context).getBalance()} tokens"
        }
        val airtime = BalanceChecker.currentBalanceStr.ifBlank { "Unknown" }
        val battery = BatteryStatus.formatForSms(BatteryStatus.read(context))
        val version = appVersionName(context)
        val versionPart = if (version.isNotBlank()) " v$version" else ""
        sendSms(context, replyTo, "Bingwa Mobile$versionPart\nAutomation: $automation\nAirtime: $airtime\nUsage: $usage\n$battery", replySubId)
    }

    private fun sendHelp(context: Context, replyTo: String, replySubId: Int?) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val prefix = (prefs.getString("sms_prefix", "BINGWA") ?: "BINGWA").uppercase()
        val pin = (prefs.getString("sms_pin", "") ?: "").trim()
        val pinHint = if (pin.isNotEmpty()) maskPin(pin) else ""

        val intro = buildString {
            append("Remote admin commands are accepted only from the Admin Phone number saved in Settings.\n\n")
            append("Command format:\n")
            append("$prefix ")
            if (pinHint.isNotEmpty()) append("$pinHint ")
            append("<COMMAND>\n\n")
            append("Example:\n$prefix ")
            if (pinHint.isNotEmpty()) append("$pinHint ")
            append("STATUS\n\n")
        }

        val menu = """
            Checks:
            STATUS (S) – summary of sent, pending, failed, usage, and battery
            PING – quick health/status check
            BALANCE (B) – airtime and token balance
            TOKENS (T) – token or unlimited status
            BATTERY (BAT) – phone battery level

            Offers:
            OFFERS (O) – list offers with their number and ID
            ON <offer> – enable an offer by number or ID
            OFF <offer> – disable an offer by number or ID
            BUY <phone> <offer> – dispatch using an offer number or ID
            BUYAMT <phone> <amount> – dispatch by amount

            Transactions:
            P – latest pending transactions
            F – latest failed transactions
            RETRY <tx-id> – retry a failed transaction

            Wallet:
            BT <amount> – buy tokens using airtime
            HELP – this message
        """.trimIndent()

        sendSms(context, replyTo, (intro + menu).trimEnd(), replySubId)
    }

    private fun resolveOfferIndex(reference: String, offers: List<OfferItem>): Int {
        val numeric = reference.toIntOrNull() ?: return -1
        val idIndex = offers.indexOfFirst { it.id == numeric }
        return if (idIndex != -1) idIndex else offers.indices.firstOrNull { it + 1 == numeric } ?: -1
    }

    internal fun sendSms(context: Context, phone: String, message: String, preferredSubId: Int? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SEND_SMS permission not granted")
            return
        }
        try {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val adminSubId = prefs.getInt("admin_sms_sim_id", -1)
            val notifySubId = prefs.getInt("notify_sim_id", -1)
            val ussdSubId = resolvePreferredUssdSubId(context) ?: -1
            val subId = listOf(preferredSubId ?: -1, ussdSubId, adminSubId, notifySubId).firstOrNull { it != -1 } ?: -1

            val managers = buildList {
                if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    runCatching { add(SmsManager.getSmsManagerForSubscriptionId(subId)) }
                }
                add(SmsManager.getDefault())
            }.distinctBy { it.hashCode() }

            val candidates = buildList {
                add(phone.trim())
                add(preferredDest(phone))
                add(normalizePhoneForSms(phone))
            }.distinct().filter { it.isNotBlank() }

            managers.forEach { mgr ->
                candidates.forEach { dest ->
                    try {
                        val parts = mgr.divideMessage(message)
                        mgr.sendMultipartTextMessage(dest, null, parts, null, null)
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "SMS send failed dest=$dest", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed", e)
        }
    }

    internal fun normalizePhone(phone: String): String {
        val raw = phone.trim().replace("+", "").replace(Regex("\\D+"), "")
        val normalized = when {
            raw.startsWith("00") -> raw.drop(2)
            else -> raw
        }
        return when {
            normalized.startsWith("254") && normalized.length >= 12 -> {
                val local = normalized.drop(3)
                when {
                    local.startsWith("0") && local.length == 10 -> local
                    local.length == 9 -> "0$local"
                    else -> local
                }
            }
            normalized.length == 9 && (normalized.startsWith("7") || normalized.startsWith("1")) -> "0$normalized"
            normalized.length == 10 && normalized.startsWith("0") -> normalized
            else -> normalized
        }
    }

    private fun normalizePhoneForSms(phone: String): String {
        val raw = phone.trim().replace("+", "").replace(Regex("\\D+"), "")
        val normalized = when {
            raw.startsWith("00") -> raw.drop(2)
            else -> raw
        }
        return when {
            normalized.startsWith("254") && normalized.length >= 12 -> normalized.take(12)
            normalized.length == 10 && normalized.startsWith("0") -> "254${normalized.drop(1)}"
            normalized.length == 9 && (normalized.startsWith("7") || normalized.startsWith("1")) -> "254$normalized"
            else -> normalized
        }
    }

    private fun preferredDest(phone: String): String = normalizePhone(phone)

    private fun maskPin(pin: String): String {
        val trimmed = pin.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 2) return "*".repeat(trimmed.length)
        return "*".repeat(trimmed.length - 2) + trimmed.takeLast(2)
    }

    private fun appVersionName(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            (info.versionName ?: "").trim()
        } catch (_: Exception) {
            ""
        }
    }
}
