package com.example.camerax_mlkit.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcm {
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12            // NIST 권장
    private const val TAG_BITS = 128         // 16B tag
    private val rnd = SecureRandom()

    private fun requireKeyLen(key: ByteArray) {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16/24/32 bytes"
        }
    }

    /** ciphertext = b64url( IV || CT||TAG ) */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): String {
        requireKeyLen(key)
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)

        val out = ByteArray(iv.size + ct.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(ct, 0, this, iv.size, ct.size)
        }
        return Base64.encodeToString(out, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /** 입력: b64url( IV || CT||TAG ) → 평문 */
    fun open(key: ByteArray, tokenB64url: String, aad: ByteArray? = null): ByteArray {
        requireKeyLen(key)
        val all = Base64.decode(tokenB64url, Base64.URL_SAFE or Base64.NO_WRAP)
        require(all.size > IV_LEN) { "Invalid token" }

        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw SecurityException("Invalid token or AAD (auth tag mismatch)", e)
        }
    }
}
