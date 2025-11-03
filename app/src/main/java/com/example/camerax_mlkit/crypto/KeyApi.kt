// KeyApi.kt
package com.example.camerax_mlkit.crypto

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class PubResp(
    @Json(name = "server_pub_b64") val serverPubB64: String
)

data class EcdhReq(
    @Json(name = "client_pub_b64") val clientPubB64: String,
    @Json(name = "nonce")         val nonce: String,
    @Json(name = "device_id")     val deviceId: String,
    @Json(name = "app_ver")       val appVer: String
)

data class EcdhResp(
    @Json(name = "key_id")           val keyId: String,
    @Json(name = "ttl_sec")          val ttlSec: Int,
    @Json(name = "server_pub_b64")   val serverPubB64: String,
    @Json(name = "server_nonce_b64") val serverNonceB64: String
)

interface KeyApi {
    // 필요 없으면 지우거나 ↓ 경고 무시
    @Suppress("unused")
    @GET("/v1/keys/pub")
    suspend fun getPub(): PubResp

    @POST("/v1/keys/ecdh")
    suspend fun ecdh(@Body req: EcdhReq): EcdhResp
}
