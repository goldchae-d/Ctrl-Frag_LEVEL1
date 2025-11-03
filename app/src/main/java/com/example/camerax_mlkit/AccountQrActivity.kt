package com.example.camerax_mlkit

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class AccountQrActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 잠금화면에서도 보이게 하려면(선택)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_qr)

        // 필요 시 코드로 다른 이미지 교체도 가능
        findViewById<ImageView>(R.id.imgQr).apply {
            // setImageResource(R.drawable.account_qr) // 기본은 레이아웃에서 지정됨
            setOnClickListener {
                // 전체화면 토글, 닫기 등 원하는 액션(선택)
                (it.parent as? View)?.performClick()
            }
        }

        // 바깥 영역 탭하면 닫기(선택)
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }
    }
}
