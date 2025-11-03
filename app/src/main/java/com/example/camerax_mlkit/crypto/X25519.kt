package com.example.camerax_mlkit.crypto

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

object X25519 {
    private val rnd = SecureRandom()

    @Suppress("ArrayInDataClass") // ByteArray라 equals/hashCode 경고 무시
    data class EphemeralKeyPair(val pub: ByteArray, val priv: ByteArray)

    /** Curve25519 에페메럴 키쌍 생성 (priv 32B, pub 32B) */
    fun generateEphemeral(): EphemeralKeyPair {
        val priv = ByteArray(X25519.SCALAR_SIZE)   // 32
        val pub  = ByteArray(X25519.POINT_SIZE)    // 32
        X25519.generatePrivateKey(rnd, priv)
        X25519.generatePublicKey(priv, 0, pub, 0)  // void 반환
        return EphemeralKeyPair(pub, priv)
    }

    /** 공유비밀 q = priv ⊗ peerPub (32B) */
    fun sharedSecret(priv: ByteArray, peerPub: ByteArray): ByteArray {
        require(priv.size == X25519.SCALAR_SIZE)   { "priv must be 32B" }
        require(peerPub.size == X25519.POINT_SIZE) { "peerPub must be 32B" }
        val out = ByteArray(X25519.POINT_SIZE)     // 32
        // BouncyCastle 1.78.1에선 반환형이 void → 예외 없이 완료되면 성공
        X25519.scalarMult(priv, 0, peerPub, 0, out, 0)
        return out
    }
}
