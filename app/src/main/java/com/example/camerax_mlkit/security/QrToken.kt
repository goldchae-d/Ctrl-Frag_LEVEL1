package com.example.camerax_mlkit.security

import android.util.Base64
import org.json.JSONObject

data class QrPayload(
    val merchantId: String,
    val locationId: String,
    val nonce: String,
    val expiry: Long
)

object QrToken {
    // QR 문자열: base64url(payloadJson) + "." + base64url(signatureBytes)
    fun parse(raw: String): Pair<QrPayload, ByteArray>? {
        val parts = raw.split(".")
        if (parts.size != 2) return null

        val payloadJsonStr = String(Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP))
        val sigBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)

        val o = JSONObject(payloadJsonStr)
        val payload = QrPayload(
            merchantId = o.getString("merchant_id"),
            locationId = o.getString("location_id"),
            nonce = o.getString("nonce"),
            expiry = o.getLong("expiry")
        )
        return payload to sigBytes
    }

    fun normalizedMessageForSign(payload: QrPayload): ByteArray {
        // 시연 안정성을 위해 canonical ordering으로 직렬화
        val s = JSONObject()
            .put("merchant_id", payload.merchantId)
            .put("location_id", payload.locationId)
            .put("nonce", payload.nonce)
            .put("expiry", payload.expiry)
            .toString()
        return s.toByteArray(Charsets.UTF_8)
    }
}
