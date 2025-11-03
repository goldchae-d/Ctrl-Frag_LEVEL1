package com.example.camerax_mlkit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BeaconSelectionActivity : AppCompatActivity() {

    companion object {
        // Router → Selection 으로 전달받는 키들
        const val EXTRA_BEACON_NAMES = "BEACON_NAMES"     // Array<String> (보여줄 상호명 리스트)
        const val EXTRA_LOCATION_IDS = "LOCATION_IDS"     // Array<String> (각 항목의 locationId 리스트)

        // Selection → PaymentPromptActivity 로 전달하는 키들
        const val EXTRA_STORE_NAME   = "STORE_NAME"       // String (선택된 상호명)
        const val EXTRA_LOCATION_ID  = "LOCATION_ID"      // String (선택된 locationId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val names = intent.getStringArrayExtra(EXTRA_BEACON_NAMES) ?: emptyArray()
        val locs  = intent.getStringArrayExtra(EXTRA_LOCATION_IDS) ?: emptyArray()

        if (names.isEmpty() || locs.isEmpty() || names.size != locs.size) {
            finish(); return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("어느 매장에서 결제하시나요?")
            .setItems(names) { _, which ->
                val selName = names[which]
                val selLoc  = locs[which]

                // 선택 결과를 담아 결제화면으로 이동
                startActivity(Intent(this, PaymentPromptActivity::class.java).apply {
                    putExtra(EXTRA_STORE_NAME,  selName)
                    putExtra(EXTRA_LOCATION_ID, selLoc)
                })

                // (권장) 다음 노출에서 중복 방지
                TriggerGate.clearDetectedBeacons()
                finish()
            }
            .setOnDismissListener { finish() }
            .show()
    }
}
