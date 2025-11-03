// app/src/main/java/com/example/camerax_mlkit/geofence/GeofenceBroadcastReceiver.kt
package com.example.camerax_mlkit.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.camerax_mlkit.TriggerGate
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Fused Location의 지오펜스 이벤트를 받아 TriggerGate에 전달한다.
 *
 * - 액션: "com.example.camerax_mlkit.GEOFENCE_EVENT"
 * - ENTER/DWELL → inZone=true
 * - EXIT        → inZone=false
 * - 여러 지점(Level 2)을 대비해 실제 트리거된 requestId 중 첫 번째를 fenceId로 전달
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION = "com.example.camerax_mlkit.GEOFENCE_EVENT"
        private const val TAG = "GeofenceBR"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            Log.w(TAG, "Ignored intent action=${intent.action}")
            return
        }

        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.e(TAG, "GeofencingEvent is null")
            return
        }
        if (event.hasError()) {
            Log.e(TAG, "Geofencing error: ${event.errorCode}")
            return
        }

        val ids: List<String> = event.triggeringGeofences?.map { it.requestId } ?: emptyList()
        val fenceId: String = ids.firstOrNull()?.lowercase() ?: "unknown" // ✅ 일관성 유지

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER,
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                Log.d(TAG, "ENTER/DWELL → fenceId=$fenceId, ids=$ids")
                TriggerGate.onGeofenceChanged(context.applicationContext, true, fenceId)
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.d(TAG, "EXIT → fenceId=$fenceId, ids=$ids")
                TriggerGate.onGeofenceChanged(context.applicationContext, false, fenceId)
            }

            else -> Log.d(TAG, "UNKNOWN transition=${event.geofenceTransition} → ids=$ids")
        }
    }
}
