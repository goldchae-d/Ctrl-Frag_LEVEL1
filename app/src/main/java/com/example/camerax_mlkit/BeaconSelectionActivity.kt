package com.example.camerax_mlkit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BeaconSelectionActivity : AppCompatActivity() {

    companion object {
        // Router → Selection 으로 전달받는 키들 (그대로 유지)
        const val EXTRA_BEACON_NAMES = "BEACON_NAMES"     // Array<String>
        const val EXTRA_LOCATION_IDS = "LOCATION_IDS"     // Array<String>
        // ✅ Selection → PaymentPromptActivity 로 넘기는 키는 PaymentPromptActivity의 상수를 사용하도록 변경
        // (여기서는 더 이상 별도의 키를 정의하지 않음)
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

                // ✅ 선택 결과를 PaymentPromptActivity 정의 키로 전달
                startActivity(Intent(this, PaymentPromptActivity::class.java).apply {
                    putExtra(PaymentPromptActivity.EXTRA_STORE_NAME,  selName)
                    putExtra(PaymentPromptActivity.EXTRA_LOCATION_ID, selLoc)
                    // (선택) merchantId를 쓰고 싶다면 여기서 같이 넘기도록 확장 가능:
                    // putExtra(PaymentPromptActivity.EXTRA_MERCHANT_ID, merchantIdFor(selLoc))
                })

                TriggerGate.clearDetectedBeacons()
                finish()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    // (선택) locationId → merchantId 매핑이 필요하다면 여기에 보조 함수 추가 가능
    // private fun merchantIdFor(locationId: String): String = ...
}
