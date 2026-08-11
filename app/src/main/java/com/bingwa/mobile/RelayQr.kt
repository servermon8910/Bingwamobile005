package com.bingwa.mobile

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface

@Keep
data class RelayQrPayload(
    val type: String = TYPE,
    val version: Int = 1,
    val relayPhone: String = "",
    val relayPrefix: String = "BINGWA",
    val relayPin: String = "",
    val relayIp: String = "",
    val relayIps: List<String> = emptyList(),
    val sendResultsSms: Boolean = true
) {
    companion object {
        const val TYPE = "hotspot-relay"
    }
}

object RelayQrCodec {
    private const val PREFIX = "BINGWA-RELAY:"
    private val gson = Gson()

    fun createPayload(
        pairedPhone: String,
        relayPrefix: String,
        relayPin: String,
        relayIp: String,
        sendResultsSms: Boolean
    ): RelayQrPayload {
        val ips = buildList {
            if (relayIp.isNotBlank()) add(relayIp.trim())
            addAll(findLocalIpv4Candidates())
        }.distinct()

        return RelayQrPayload(
            relayPhone = pairedPhone.trim(),
            relayPrefix = relayPrefix.trim().uppercase().ifBlank { "BINGWA" },
            relayPin = relayPin.filter(Char::isDigit).take(6),
            relayIp = ips.firstOrNull().orEmpty(),
            relayIps = ips,
            sendResultsSms = sendResultsSms
        )
    }

    fun encode(payload: RelayQrPayload): String = PREFIX + gson.toJson(payload)

    fun decode(rawValue: String): RelayQrPayload? {
        val payloadJson = rawValue.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val parsed = try {
            gson.fromJson(payloadJson, RelayQrPayload::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } ?: return null
        if (parsed.type != RelayQrPayload.TYPE) return null

        val ips = buildList {
            if (parsed.relayIp.isNotBlank()) add(parsed.relayIp.trim())
            addAll(parsed.relayIps.map { it.trim() }.filter { it.isNotBlank() })
        }.distinct()

        return parsed.copy(
            relayPhone = parsed.relayPhone.trim(),
            relayPrefix = parsed.relayPrefix.trim().uppercase().ifBlank { "BINGWA" },
            relayPin = parsed.relayPin.filter(Char::isDigit).take(6),
            relayIp = ips.firstOrNull().orEmpty(),
            relayIps = ips
        )
    }

    fun createBitmap(content: String, size: Int = 900): Bitmap {
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
        )
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun findLocalIpv4Candidates(): List<String> {
        val addresses = buildList {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            interfaces.forEach { network ->
                val ips = runCatching { network.inetAddresses?.toList().orEmpty() }.getOrDefault(emptyList())
                ips.forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        add(address.hostAddress ?: return@forEach)
                    }
                }
            }
        }.distinct()

        return addresses.sortedBy(::priority)
    }

    private fun priority(ip: String): Int = when {
        ip.startsWith("192.168.43.") -> 0
        ip.startsWith("192.168.49.") -> 1
        ip.startsWith("192.168.") -> 2
        ip.startsWith("10.") -> 3
        ip.startsWith("172.") -> 4
        else -> 5
    }
}
