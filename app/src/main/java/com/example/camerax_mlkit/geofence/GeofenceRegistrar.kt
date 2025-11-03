package com.example.camerax_mlkit.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * 지오펜스 등록/해제 전담 모듈.
 * - requestId 는 화이트리스트의 locationId 와 일치해야 함.
 *   예) "store_duksung_a", "store_duksung_b"
 */
class GeofenceRegistrar(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceRegistrar"

        // ✅ 반드시 화이트리스트와 동일 (소문자 권장)
        const val FENCE_A_ID = "store_duksung_a"
        const val FENCE_B_ID = "store_duksung_b"

        // ✅ Level 2 - 덕성여대 차미리사관 기반 두 지점
        private const val A_LAT = 37.653398    // 차미리사관
        private const val A_LNG = 127.016859
        private const val B_LAT = 37.651080    // 하나누리관
        private const val B_LNG = 127.019482

        private const val RADIUS_M = 200f
    }

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context.applicationContext)

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            .setAction("com.example.camerax_mlkit.GEOFENCE_EVENT") // ✅ Manifest/Receiver 와 동일
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildGeofence(id: String, lat: Double, lng: Double, radius: Float): Geofence =
        Geofence.Builder()
            .setRequestId(id) // ✅ whitelist.locationId 와 동일해야 TriggerGate 매칭 통과
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or
                        Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(10_000) // DWELL 판정 지연(10s, 필요시 조정)
            .build()

    private fun buildRequest(geofences: List<Geofence>): GeofencingRequest =
        GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                        GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            .addGeofences(geofences)
            .build()

    /**
     * 권한 전제:
     * - ACCESS_FINE_LOCATION
     * - (백그라운드 필요 시) ACCESS_BACKGROUND_LOCATION
     */
    @RequiresPermission(
        anyOf = [
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    fun registerDefaultFences() {
        val list = listOf(
            buildGeofence(FENCE_A_ID, A_LAT, A_LNG, RADIUS_M),
            buildGeofence(FENCE_B_ID, B_LAT, B_LNG, RADIUS_M),
        )
        val request = buildRequest(list)

        // 기존 것 정리 후 재등록(시연 안정성)
        geofencingClient.removeGeofences(pendingIntent()).addOnCompleteListener {
            geofencingClient.addGeofences(request, pendingIntent())
                .addOnSuccessListener { Log.i(TAG, "Geofences registered ✅: $list") }
                .addOnFailureListener { e -> Log.e(TAG, "Register failed", e) }
        }
    }

    fun unregisterAll() {
        geofencingClient.removeGeofences(pendingIntent())
            .addOnSuccessListener { Log.i(TAG, "Geofences unregistered") }
            .addOnFailureListener { e -> Log.e(TAG, "Unregister failed", e) }
    }
}
