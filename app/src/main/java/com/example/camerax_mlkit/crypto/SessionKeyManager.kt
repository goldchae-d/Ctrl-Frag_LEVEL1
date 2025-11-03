// app/src/main/java/com/example/camerax_mlkit/crypto/SessionKeyManager.kt
package com.example.camerax_mlkit.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SessionKeyManager {
    private var key: ByteArray? = null
    private var expAt: Long = 0L
    private var kid: String? = null
    private val mu = Mutex()

    suspend fun ensureKey(ctx: Context, api: KeyApi): Pair<String, ByteArray> =
        mu.withLock {
            val now = System.currentTimeMillis()
            if (key != null && now < expAt) return kid!! to key!!

            // 1) 에페메럴 키쌍
            val eph = X25519.generateEphemeral()

            // 2) 요청: clientPubB64 / nonce / deviceId / appVer (camelCase)
            val nonce = java.util.UUID.randomUUID().toString()
            val deviceId = Settings.Secure
                .getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown"

            val resp = api.ecdh(
                EcdhReq(
                    clientPubB64 = Base64.encodeToString(
                        eph.pub, Base64.URL_SAFE or Base64.NO_WRAP
                    ),
                    nonce = nonce,
                    deviceId = deviceId,
                    appVer = getAppVersion(ctx)
                )
            )

            // 3) 응답 필드도 camelCase 사용
            val serverNonce = Base64.decode(resp.serverNonceB64, Base64.URL_SAFE)
            val serverPub   = Base64.decode(resp.serverPubB64,   Base64.URL_SAFE)

            val shared = X25519.sharedSecret(eph.priv, serverPub)

            // ByteArray + ByteArray 모호성 회피: plus(ByteArray) 명시 호출
            val salt = nonce.toByteArray(Charsets.UTF_8).plus(serverNonce)

            val sessionKey = Hkdf.deriveIKM(
                ikm = shared,
                salt = salt,
                info = "qr-aead".toByteArray(),
                len = 32
            )

            // 4) 캐시
            key  = sessionKey
            kid  = resp.keyId
            expAt = now + ((resp.ttlSec - 10).coerceAtLeast(1) * 1000L)

            kid!! to sessionKey
        }

    fun clear() {
        key = null; kid = null; expAt = 0L
    }

    private fun getAppVersion(ctx: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager
                    .getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
                    .versionName ?: "unknown"
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
            }
        } catch (_: Exception) { "unknown" }
    }
}
