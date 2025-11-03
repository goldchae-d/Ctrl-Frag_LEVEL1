package com.example.camerax_mlkit.security

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object SignatureVerifier {
    private fun parsePemPublicKey(pem: String): PublicKey {
        val clean = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.decode(clean, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    fun verifyEcdsaP256(pubKeyPem: String, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val pub = parsePemPublicKey(pubKeyPem)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            sig.update(message)
            sig.verify(signature)
        } catch (_: Throwable) { false }
    }
}
