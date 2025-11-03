package com.example.camerax_mlkit.security

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH/HKDF로 얻은 세션키(32B, AES-256)를 사용해
 * QR 토큰을 생성하는 유틸리티.
 *
 * 출력 형식:  kid=<kid>&ct=<b64url(iv||ciphertext)>
 *  - AES/GCM/NoPadding (IV 12B, TAG 16B)
 *  - AAD = kid (무결성 바인딩)
 *  - payload는 JSON 문자열을 UTF-8로 인코딩해 암호화
 *
 * 기본 페이로드 필드:
 *   v        : 버전(1)
 *   sid      : 세션 ID
 *   mid      : merchantId
 *   ts       : 생성 시각(ms)
 *   amt      : (옵션) 금액
 *   + extra  : (옵션) 부가정보 (예: type, location_id, nonce 등)
 *
 * 사용 예:
 *   val token = SecureQr.buildEncryptedToken(
 *       kid = kid, sessionKey = sk, sessionId = sid,
 *       merchantId = entry?.merchantId ?: "merchant_duksung",
 *       amount = null,
 *       extra = mapOf("type" to "account", "location_id" to locId, "nonce" to beaconNonce)
 *   )
 */
object SecureQr {

    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128               // GCM tag = 16 bytes
    private val rnd = SecureRandom()

    /**
     * QR 토큰 생성 (가장 일반적인 엔트리 포인트)
     */
    fun buildEncryptedToken(
        kid: String,
        sessionKey: ByteArray,            // 32 bytes (AES-256)
        sessionId: String,
        merchantId: String,
        amount: Long? = null,
        extra: Map<String, Any?> = emptyMap()
    ): String {
        require(sessionKey.size == 32) { "sessionKey must be 32 bytes (AES-256)" }

        // 1) JSON payload 구성
        val payloadJson = JSONObject().apply {
            put("v", 1)
            put("sid", sessionId)
            put("mid", merchantId)
            put("ts", System.currentTimeMillis())
            if (amount != null) put("amt", amount)
            // extra(예: type, location_id, nonce, expiry 등) 주입
            for ((k, v) in extra) put(k, v)
        }.toString()

        val payloadBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)

        // 2) AES-GCM 암호화 (IV 12B / AAD = kid)
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(kid.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(payloadBytes) // = cipher || tag

        // 3) iv||ciphertext 를 URL-safe Base64 (no padding)로 인코딩
        val ctB64 = Base64.encodeToString(
            iv + ciphertext,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        // 4) 최종 QR 문자열
        return "kid=$kid&ct=$ctB64"
    }

    /**
     * location_id/nonce를 간편히 포함하고 싶을 때 쓰는 헬퍼.
     *  - expirySec: 토큰 유효기간(초 단위 epoch). null이면 넣지 않음.
     */
    fun buildEncryptedTokenWithLocation(
        kid: String,
        sessionKey: ByteArray,
        sessionId: String,
        merchantId: String,
        locationId: String,
        nonce: String? = null,
        amount: Long? = null,
        expirySec: Long? = null,
        extra: Map<String, Any?> = emptyMap()
    ): String {
        val mergedExtra = HashMap<String, Any?>(extra.size + 3).apply {
            putAll(extra)
            put("type", extra["type"] ?: "account")
            put("location_id", locationId)
            if (nonce != null) put("nonce", nonce)
            if (expirySec != null) put("expiry", expirySec)
        }
        return buildEncryptedToken(
            kid = kid,
            sessionKey = sessionKey,
            sessionId = sessionId,
            merchantId = merchantId,
            amount = amount,
            extra = mergedExtra
        )
    }
}
