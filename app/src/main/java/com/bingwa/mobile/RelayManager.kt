package com.bingwa.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RelayManager {
    private const val TAG = "RelayManager"
    private const val HOTSPOT_PORT = 8765
    private const val PREFS_NAME = "app_settings"
    private const val KEY_LAST_GOOD_RELAY_IP = "relay_last_good_ip"
    private const val KEY_RELAY_PRIMARY_AIRTIME = "relay_primary_airtime"
    private const val HOTSPOT_CONNECT_TIMEOUT_MS = 1_500
    private const val HOTSPOT_READ_TIMEOUT_MS = 1_800
    private val HOTSPOT_IP_FALLBACKS = listOf(
        "192.168.43.1",
        "192.168.49.1",
        "192.168.44.1",
        "192.168.137.1",
        "192.168.0.1",
        "192.168.1.1"
    )

    enum class HotspotLinkState { DISABLED, CHECKING, CONNECTED, DISCONNECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val _hotspotState = MutableStateFlow(HotspotLinkState.DISABLED)
    val hotspotState: StateFlow<HotspotLinkState> = _hotspotState.asStateFlow()
    private var observedPrefs: SharedPreferences? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun shutdown() {
        runCatching { observedPrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        observedPrefs = null
        prefsListener = null
        monitorJob?.cancel()
        monitorJob = null
        scope.coroutineContext[Job]?.cancel()
    }

    data class Config(
        val enabled: Boolean,
        val role: String,
        val method: String,
        val pairedPhone: String,
        val relayIp: String,
        val relayIpAuto: Boolean,
        val prefix: String,
        val pin: String,
        val sendResultsSms: Boolean
    )

    private val defaultConfig = Config(
        enabled = false,
        role = "PRIMARY",
        method = "SMS",
        pairedPhone = "",
        relayIp = "",
        relayIpAuto = false,
        prefix = "BINGWA",
        pin = "",
        sendResultsSms = true
    )
    private val _configState = MutableStateFlow(defaultConfig)
    val configState: StateFlow<Config> = _configState.asStateFlow()
    private val _mirroredPrimaryAirtime = MutableStateFlow("")
    val mirroredPrimaryAirtime: StateFlow<String> = _mirroredPrimaryAirtime.asStateFlow()

    fun load(context: Context): Config {
        val appCtx = context.applicationContext
        runCatching { observeConfig(appCtx) }
            .onFailure { Log.e(TAG, "Unable to observe relay config", it) }
        _mirroredPrimaryAirtime.value = getStoredMirroredPrimaryAirtime(appCtx)
        clearTemporaryRelayMirrorsIfInactive(appCtx, _configState.value)
        return _configState.value
    }

    private fun observeConfig(context: Context) {
        if (observedPrefs != null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        runCatching {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
                if (
                    key == null ||
                    key == "two_phone_enabled" ||
                    key == "two_phone_role" ||
                    key == "relay_method" ||
                    key == "paired_phone" ||
                    key == "relay_ip" ||
                    key == "relay_ip_auto" ||
                    key == "relay_prefix" ||
                    key == "relay_pin" ||
                    key == "relay_send_results" ||
                    key == "sms_prefix" ||
                    key == "sms_pin"
                ) {
                    val cfg = readConfig(sharedPrefs)
                    _configState.value = cfg
                    clearTemporaryRelayMirrorsIfInactive(context, cfg)
                }
                if (key == null || key == KEY_RELAY_PRIMARY_AIRTIME) {
                    _mirroredPrimaryAirtime.value =
                        sharedPrefs.safeGetString(KEY_RELAY_PRIMARY_AIRTIME, "")?.trim().orEmpty()
                }
            }
            val cfg = readConfig(prefs)
            _configState.value = cfg
            _mirroredPrimaryAirtime.value = prefs.safeGetString(KEY_RELAY_PRIMARY_AIRTIME, "")?.trim().orEmpty()
            clearTemporaryRelayMirrorsIfInactive(context, cfg)
            prefs.registerOnSharedPreferenceChangeListener(listener)
            observedPrefs = prefs
            prefsListener = listener
        }.onFailure {
            Log.e(TAG, "Relay config observation failed", it)
        }
    }

    private fun readConfig(prefs: SharedPreferences): Config =
        Config(
            enabled = prefs.safeGetBoolean("two_phone_enabled", false),
            role = (prefs.safeGetString("two_phone_role", "PRIMARY") ?: "PRIMARY").uppercase(),
            method = (prefs.safeGetString("relay_method", "SMS") ?: "SMS").uppercase(),
            pairedPhone = (prefs.safeGetString("paired_phone", "") ?: "").trim(),
            relayIp = (prefs.safeGetString("relay_ip", "") ?: "").trim(),
            relayIpAuto = prefs.safeGetBoolean("relay_ip_auto", false),
            prefix = (prefs.safeGetString("relay_prefix", prefs.safeGetString("sms_prefix", "BINGWA")) ?: "BINGWA").trim().uppercase(),
            pin = (prefs.safeGetString("relay_pin", prefs.safeGetString("sms_pin", "")) ?: "").trim(),
            sendResultsSms = prefs.safeGetBoolean("relay_send_results", true)
        )

    fun isPrimary(context: Context): Boolean = load(context).let { it.enabled && it.role == "PRIMARY" }

    fun isRelay(context: Context): Boolean = load(context).let { it.enabled && it.role == "RELAY" }

    fun getMirroredPrimaryAirtime(context: Context): String {
        load(context)
        return _mirroredPrimaryAirtime.value.ifBlank { getStoredMirroredPrimaryAirtime(context.applicationContext) }
    }

    fun setMirroredPrimaryAirtime(context: Context, airtimeDisplay: String) {
        val clean = airtimeDisplay.trim()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RELAY_PRIMARY_AIRTIME, clean)
            .apply()
        _mirroredPrimaryAirtime.value = clean
    }

    fun clearTemporaryRelayMirrors(context: Context) {
        val appCtx = context.applicationContext
        TokenManager(appCtx).clearTemporaryRelayBalance()
        setMirroredPrimaryAirtime(appCtx, "")
    }

    fun encodeRelayText(value: String): String =
        Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

    fun decodeRelayText(value: String): String? =
        runCatching {
            String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull()

    fun startRelayHotspotService(context: Context): Boolean =
        ServiceLauncher.startForegroundServiceSafely(
            context = context,
            intent = android.content.Intent(context, HotspotRelayService::class.java),
            label = "Relay hotspot service"
        )

    fun stopRelayHotspotService(context: Context) {
        context.stopService(android.content.Intent(context, HotspotRelayService::class.java))
    }

    fun shouldRelayOffer(context: Context, offer: OfferItem): Boolean {
        val cfg = load(context)
        return cfg.enabled && cfg.role == "PRIMARY" && offer.targetDevice.uppercase() == "RELAY"
    }

    fun startHotspotMonitor(context: Context) {
        if (monitorJob?.isActive == true) return
        val appCtx = context.applicationContext
        monitorJob = scope.launch {
            var consecutiveFailures = 0
            var lastState: HotspotLinkState = _hotspotState.value
            while (isActive) {
                try {
                    val cfg = load(appCtx)
                    if (!cfg.enabled || cfg.role != "PRIMARY" || cfg.method != "HOTSPOT") {
                        _hotspotState.value = HotspotLinkState.DISABLED
                        consecutiveFailures = 0
                        delay(15_000L)
                        continue
                    }
                    _hotspotState.value = HotspotLinkState.CHECKING
                    val ok = pingHotspotRelay(appCtx, cfg)
                    _hotspotState.value = if (ok) HotspotLinkState.CONNECTED else HotspotLinkState.DISCONNECTED
                    val newState = _hotspotState.value
                    if (newState == HotspotLinkState.CONNECTED && lastState != HotspotLinkState.CONNECTED) {
                        syncTokenBalance(appCtx, TokenManager(appCtx).getBalance())
                        syncPrimaryAirtimeBalance(appCtx, BalanceChecker.currentBalanceStr)
                    }
                    lastState = newState
                    consecutiveFailures = if (ok) 0 else (consecutiveFailures + 1).coerceAtMost(4)
                    val waitMs = if (ok) {
                        6_000L
                    } else {
                        2_500L + (consecutiveFailures * 2_500L)
                    }
                    delay(waitMs)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Hotspot monitor iteration failed", error)
                    _hotspotState.value = HotspotLinkState.DISCONNECTED
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(4)
                    delay(5_000L)
                }
            }
        }
    }

    fun stopHotspotMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        _hotspotState.value = HotspotLinkState.DISABLED
    }

    fun findOfferByPrice(context: Context, amount: Int): OfferItem? {
        return OfferRepository.findByPrice(context, amount)
    }

    private fun hotspotCandidates(context: Context, cfg: Config): List<String> {
        val resolved = when {
            cfg.relayIpAuto -> getWifiGatewayIp(context)
            cfg.relayIp.isNotBlank() -> cfg.relayIp
            else -> null
        }
        val lastGoodIp = getLastGoodRelayIp(context)
        return buildList {
            if (cfg.relayIp.isNotBlank()) add(cfg.relayIp)
            if (!lastGoodIp.isNullOrBlank()) add(lastGoodIp)
            if (!resolved.isNullOrBlank()) add(resolved)
            addAll(buildSubnetCandidates(resolved))
            addAll(buildSubnetCandidates(cfg.relayIp))
            addAll(buildSubnetCandidates(lastGoodIp))
            addAll(getCurrentWifiSubnetCandidates(context))
            addAll(HOTSPOT_IP_FALLBACKS)
        }.filter { isValidHotspotIp(it) }.distinct()
    }

    private fun isValidHotspotIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun pingHotspotRelay(context: Context, cfg: Config): Boolean {
        val candidates = hotspotCandidates(context, cfg).filter { isValidHotspotIp(it) }
        if (candidates.isEmpty()) return false
        for (ip in candidates) {
            val ok = sendHotspotCommand(context, cfg, ip, "PING")?.startsWith("OK") == true
            if (ok) return true
        }
        return false
    }

    fun forwardBuyAmount(context: Context, phone: String, amount: Int): Boolean {
        val cfg = load(context)
        if (!cfg.enabled || cfg.role != "PRIMARY") return false
        if (cfg.method != "HOTSPOT" && cfg.pairedPhone.isBlank()) return false
        if (amount <= 0) return false

        return when (cfg.method) {
            "HOTSPOT" -> {
                val candidates = hotspotCandidates(context, cfg)
                if (candidates.isEmpty()) return false
                scope.launch {
                    for (ip in candidates) {
                        val ok = sendHotspotCommand(context, cfg, ip, "BUYAMT $phone $amount")?.startsWith("OK") == true
                        if (ok) break
                    }
                }
                true
            }
            else -> {
                val full = buildString {
                    append(cfg.prefix)
                    append(' ')
                    if (cfg.pin.isNotBlank()) {
                        append(cfg.pin)
                        append(' ')
                    }
                    append("BUYAMT ")
                    append(phone)
                    append(' ')
                    append(amount)
                }
                SmsCommandHandler.sendSms(context, cfg.pairedPhone, full)
                true
            }
        }
    }

    fun syncTokenBalance(context: Context, tokenBalance: Int) {
        val cfg = load(context)
        if (!cfg.enabled || cfg.role != "PRIMARY") return
        if (cfg.method != "HOTSPOT" && cfg.pairedPhone.isBlank()) return

        when (cfg.method) {
            "HOTSPOT" -> {
                val candidates = hotspotCandidates(context, cfg)
                if (candidates.isEmpty()) return
                scope.launch {
                    for (ip in candidates) {
                        val ok = sendHotspotCommand(context, cfg, ip, "TOKENSET $tokenBalance")?.startsWith("OK") == true
                        if (ok) break
                    }
                }
            }
            else -> {
                val full = buildString {
                    append(cfg.prefix)
                    append(' ')
                    if (cfg.pin.isNotBlank()) {
                        append(cfg.pin)
                        append(' ')
                    }
                    append("TOKENSET ")
                    append(tokenBalance)
                }
                SmsCommandHandler.sendSms(context, cfg.pairedPhone, full)
            }
        }
    }

    fun syncPrimaryAirtimeBalance(context: Context, airtimeDisplay: String) {
        val cfg = load(context)
        val clean = airtimeDisplay.trim()
        if (!cfg.enabled || cfg.role != "PRIMARY" || clean.isBlank()) return
        if (cfg.method != "HOTSPOT" && cfg.pairedPhone.isBlank()) return

        when (cfg.method) {
            "HOTSPOT" -> {
                val candidates = hotspotCandidates(context, cfg)
                if (candidates.isEmpty()) return
                val encoded = encodeRelayText(clean)
                scope.launch {
                    for (ip in candidates) {
                        val ok = sendHotspotCommand(context, cfg, ip, "BALANCESET $encoded")?.startsWith("OK") == true
                        if (ok) break
                    }
                }
            }
            else -> {
                val encoded = encodeRelayText(clean)
                val full = buildString {
                    append(cfg.prefix)
                    append(' ')
                    if (cfg.pin.isNotBlank()) {
                        append(cfg.pin)
                        append(' ')
                    }
                    append("BALANCESET ")
                    append(encoded)
                }
                SmsCommandHandler.sendSms(context, cfg.pairedPhone, full)
            }
        }
    }

    @SuppressLint("WifiManagerPotentialLeak")
    private fun getWifiGatewayIp(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcp = wm.dhcpInfo ?: return null
        val gw = dhcp.gateway
        if (gw == 0) return null
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(gw).array()
        return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
    }

    @SuppressLint("WifiManagerPotentialLeak")
    private fun getCurrentWifiIp(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val address = runCatching { wm.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
        if (address == 0) return null
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(address).array()
        return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
    }

    private fun buildSubnetCandidates(ip: String?): List<String> {
        if (ip.isNullOrBlank()) return emptyList()
        val parts = ip.split('.')
        if (parts.size != 4) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return listOf(1, 2, 20, 29, 43, 44, 49, 100, 129, 137, 200)
            .map { "$prefix.$it" }
    }

    private fun getCurrentWifiSubnetCandidates(context: Context): List<String> =
        buildSubnetCandidates(getCurrentWifiIp(context))

    private fun getLastGoodRelayIp(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_GOOD_RELAY_IP, "")?.trim().orEmpty()

    private fun getStoredMirroredPrimaryAirtime(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RELAY_PRIMARY_AIRTIME, "")?.trim().orEmpty()

    private fun clearTemporaryRelayMirrorsIfInactive(context: Context, cfg: Config) {
        if (cfg.enabled && cfg.role == "RELAY") return
        clearTemporaryRelayMirrors(context)
    }

    private fun rememberGoodRelayIp(context: Context, ip: String) {
        if (ip.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GOOD_RELAY_IP, ip)
            .apply()
    }

    private fun sendHotspotCommand(context: Context, cfg: Config, ip: String, command: String): String? =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, HOTSPOT_PORT), HOTSPOT_CONNECT_TIMEOUT_MS)
                socket.soTimeout = HOTSPOT_READ_TIMEOUT_MS
                BufferedWriter(OutputStreamWriter(socket.getOutputStream())).use { out ->
                    BufferedReader(InputStreamReader(socket.getInputStream())).use { inp ->
                        val payload = JSONObject()
                            .put("pin", cfg.pin)
                            .put("cmd", command)
                            .toString()
                        out.write(payload)
                        out.write("\n")
                        out.flush()
                        val response = inp.readLine()?.trim()
                        if (!response.isNullOrBlank() && response.startsWith("OK")) {
                            rememberGoodRelayIp(context, ip)
                        }
                        response
                    }
                }
            }
        }.getOrNull()

    fun executeBuyAmountLocal(context: Context, phone: String, amount: Int, resultDestPhone: String?): Int? {
        if (BlacklistedContactStore.isBlacklisted(context, phone)) return null
        val offer = findOfferByPrice(context, amount) ?: return null
        val finalCode = offer.ussdCode.replace("pn", phone, ignoreCase = true)
        val txId = createPendingTransaction(
            context,
            offer.name,
            "KSh $amount",
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
        if (resultDestPhone != null) RelayResultTracker.trackAndReply(context, txId, resultDestPhone)
        return txId
    }
}
