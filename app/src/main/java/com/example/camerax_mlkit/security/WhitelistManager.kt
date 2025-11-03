// app/src/main/java/com/example/camerax_mlkit/security/WhitelistManager.kt
package com.example.camerax_mlkit.security

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object WhitelistManager {
    private const val TAG  = "WhitelistManager"
    private const val FILE = "whitelist.json"

    /**
     * 화이트리스트에 등록된 비콘 1개 정보
     *
     * - locationId : 지오펜스 / QR 토큰과 맞춰볼 매장 ID
     * - merchantId : 결제(가맹점) 식별자
     * - pubkeyPem  : 그 가맹점이 서명한 토큰 검증용 공개키
     * - storeName  : ✅ 사용자에게 보여줄 이름 (A(효정식당), B(은채식당) 등)
     */
    data class BeaconEntry(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val locationId: String?,
        val merchantId: String?,
        val pubkeyPem: String?,
        val storeName: String?
    )

    // uuid|major|minor → BeaconEntry
    private val map = ConcurrentHashMap<String, BeaconEntry>()

    // merchantId → pubkeyPem
    private val merchantKey = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    private fun key(uuid: String, major: Int, minor: Int): String =
        "${uuid.uppercase()}|$major|$minor"

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        try {
            // assets/whitelist.json 읽기
            val text = ctx.assets.open(FILE)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val root = JSONObject(text)
            val arr  = root.optJSONArray("beacons") ?: return

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)

                val uuid  = o.getString("uuid")
                val major = o.getInt("major")
                val minor = o.getInt("minor")

                // ✅ locationId / location_id 둘 다 허용
                val locId = when {
                    o.has("locationId")  -> o.optString("locationId", "")
                    o.has("location_id") -> o.optString("location_id", "")
                    else                 -> ""
                }.takeIf { it.isNotBlank() }

                // ✅ merchantId / merchant_id 둘 다 허용
                val merch = when {
                    o.has("merchantId")  -> o.optString("merchantId", "")
                    o.has("merchant_id") -> o.optString("merchant_id", "")
                    else                 -> ""
                }.takeIf { it.isNotBlank() }

                // ✅ pubkey도 없을 수 있으니까 같은 패턴
                val pub = o.optString("pubkey", "")
                    .takeIf { it.isNotBlank() }

                // ✅ 이번에 추가한 storeName
                val store = o.optString("storeName", "")
                    .takeIf { it.isNotBlank() }

                val entry = BeaconEntry(
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    locationId = locId,
                    merchantId = merch,
                    pubkeyPem = pub,
                    storeName = store
                )

                // 비콘 키로 넣기
                map[key(uuid, major, minor)] = entry

                // 가맹점 공개키 맵에도 넣기 (merchantId랑 pubkey 둘 다 있어야)
                if (merch != null && pub != null) {
                    merchantKey[merch] = pub
                }
            }

            loaded = true
            Log.d(TAG, "whitelist loaded: ${map.size} beacons, ${merchantKey.size} merchants")
        } catch (t: Throwable) {
            Log.e(TAG, "whitelist load failed", t)
        }
    }

    /** 비콘 1개 찾기 */
    fun findBeacon(uuid: String, major: Int, minor: Int): BeaconEntry? =
        map[key(uuid, major, minor)]

    /** 가맹점 공개키 찾기 */
    fun getMerchantPubKey(merchantId: String): String? =
        merchantKey[merchantId]
}
