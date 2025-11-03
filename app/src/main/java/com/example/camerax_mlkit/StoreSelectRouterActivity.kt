// app/src/main/java/com/example/camerax_mlkit/StoreSelectRouterActivity.kt
package com.example.camerax_mlkit

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * 결제 플로우 단일 진입점(알림/QR 공통).
 * - 짧은 폴링으로 후보를 모아 2개 이상 모이거나 타임아웃 시 선택 다이얼로그 표시
 * - 리스트 맨 아래에 "📷 카메라 사용하기" 항목 추가 → 선택 시 우리 앱의 Plain 카메라 실행
 * - ✅ 후보가 0개일 때도 Toast/종료 대신 곧바로 Plain 카메라로 전환
 */
class StoreSelectRouterActivity : AppCompatActivity() {

    private val DEADLINE_MS = 2_500L      // 최대 대기 시간
    private val TICK_MS = 250L            // 폴링 간격
    private val handler = Handler(Looper.getMainLooper())
    private var startAt = 0L
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAt = android.os.SystemClock.uptimeMillis()
        tick()
    }

    private fun tick() {
        if (finished) return

        val cands = TriggerGate.getUiCandidatesForStoreSelection()
        Log.d("StoreSelectRouter", "snapshot=" + cands.joinToString { it.storeName })

        val enough = cands.size >= 2
        val timeout = (android.os.SystemClock.uptimeMillis() - startAt) >= DEADLINE_MS

        if (enough || timeout) {
            finished = true

            // ✅ 후보가 0개면 곧바로 'Plain 카메라'로 전환
            if (cands.isEmpty()) {
                openPlainCamera()
                finish()
                return
            }

            // 후보 배열
            val names = ArrayList(cands.map { it.storeName })
            val locs  = ArrayList(cands.map { it.locationId })

            // 🔹 맨 아래에 "카메라 사용하기" 추가 (우리 앱의 Plain 카메라)
            names.add("📷 카메라 사용하기")
            locs.add("__camera__")  // 구분 토큰

            // 선택 다이얼로그 표시
            android.app.AlertDialog.Builder(this)
                .setTitle("결제하실 매장을 선택하세요")
                .setItems(names.toTypedArray()) { _, which ->
                    val chosenName = names[which]
                    val chosenLoc  = locs[which]

                    if (chosenLoc == "__camera__") {
                        openPlainCamera()
                        finish()
                    } else {
                        // 선택된 매장만 PaymentPromptActivity로 전달
                        startActivity(Intent(this, PaymentPromptActivity::class.java).apply {
                            putExtra(
                                PaymentPromptActivity.EXTRA_TRIGGER,
                                intent.getStringExtra(PaymentPromptActivity.EXTRA_TRIGGER) ?: "USER"
                            )
                            putExtra("geo", intent.getBooleanExtra("geo", false))
                            putExtra("beacon", intent.getBooleanExtra("beacon", false))
                            putExtra("wifi", intent.getBooleanExtra("wifi", false))
                            putExtra("fenceId", intent.getStringExtra("fenceId") ?: "unknown")

                            putStringArrayListExtra(
                                "extra_store_names",
                                arrayListOf(chosenName)
                            )
                            putStringArrayListExtra(
                                "extra_locations",
                                arrayListOf(chosenLoc)
                            )
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                        finish()
                    }
                }
                .setCancelable(true)
                .show()

        } else {
            handler.postDelayed({ tick() }, TICK_MS)
        }
    }

    /** ✅ 우리 앱의 CameraX 화면을 ‘일반카메라(Plain)’ 모드로 전환 */
    private fun openPlainCamera() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("plainCamera", true)
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
