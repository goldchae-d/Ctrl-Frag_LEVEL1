// app/src/main/java/com/example/camerax_mlkit/BeaconTriggerReceiver.kt
package com.example.camerax_mlkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 내부 브로드캐스트로 "비콘 근접" 이벤트를 수신해 TriggerGate로 위임한다.
 *
 * 발신 예시:
 * sendBroadcast(
 *   Intent(BeaconTriggerReceiver.ACTION_BEACON_NEAR).setPackage(packageName)
 * )
 *
 * 매니페스트:
 * <receiver
 *   android:name=".BeaconTriggerReceiver"
 *   android:exported="false" />
 */
class BeaconTriggerReceiver : BroadcastReceiver() {

    companion object {
        /** 내부 전용 비콘 근접 액션 */
        const val ACTION_BEACON_NEAR = "com.example.camerax_mlkit.BEACON_NEAR"

        private const val TAG = "BeaconTriggerBR"
        /** 연속 트리거 남발 방지 */
        private const val COOLDOWN_MS = 3000L

        @Volatile private var lastAt = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_BEACON_NEAR) return

        // (보안) 앱 내부에서 setPackage(packageName)로 보낸 것만 허용
        val samePackage = intent.`package`.isNullOrEmpty() || intent.`package` == context.packageName
        if (!samePackage) {
            Log.w(TAG, "Ignored external broadcast: action=$action, from=${intent.`package`}")
            return
        }

        // (안정성) 중복 트리거 쿨다운
        val now = System.currentTimeMillis()
        if (now - lastAt < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown: ignored")
            return
        }
        lastAt = now

        Log.d(TAG, "Beacon NEAR broadcast received → setBeacon(true) & maybe show")

        // ✅ TriggerGate에 onBeaconNear()가 없으므로
        //    1) 비콘 상태를 true로 세팅
        //    2) 즉시 팝업 노출 평가(앱 전면/후면 상관없이 정책 검사)
        TriggerGate.onAppResumed(context.applicationContext)
    }
}
