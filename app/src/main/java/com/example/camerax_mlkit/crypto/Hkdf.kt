// Hkdf.kt
package com.example.camerax_mlkit.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hkdf {
    private const val HMAC_ALG = "HmacSHA256"

    fun deriveIKM(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        // HKDF-Extract
        val prk = hmac(salt, ikm)
        // HKDF-Expand
        var t = ByteArray(0)
        val out = ByteArray(len)
        var pos = 0
        var counter = 1
        while (pos < len) {
            val mac = Mac.getInstance(HMAC_ALG)
            mac.init(SecretKeySpec(prk, HMAC_ALG))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val l = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, l)
            pos += l
            counter++
        }
        return out
    }
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(key, HMAC_ALG))
        return mac.doFinal(data)
    }
}
