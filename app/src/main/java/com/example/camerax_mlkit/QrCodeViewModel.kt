package com.example.camerax_mlkit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.common.Barcode

class QrCodeViewModel(barcode: Barcode) {

    /** QR 바운딩 박스(없을 수도 있음) */
    val boundingRect: Rect? = barcode.boundingBox

    /** 화면에 보여줄 텍스트 */
    val qrContent: String

    /** 미리 주입할 터치 콜백 */
    var qrCodeTouchCallback: (View, MotionEvent) -> Boolean = { _, _ -> false }

    init {
        if (barcode.valueType == Barcode.TYPE_URL) {
            val urlText = barcode.url?.url
            qrContent = urlText ?: (barcode.rawValue ?: "")
            if (urlText != null && boundingRect != null) {
                qrCodeTouchCallback = { v: View, e: MotionEvent ->
                    if (e.action == MotionEvent.ACTION_UP &&
                        boundingRect.contains(e.x.toInt(), e.y.toInt())
                    ) {
                        try {
                            v.context.startActivity(
                                Intent(Intent.ACTION_VIEW, urlText.toUri())
                            )
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
        } else {
            // URL 이 아니면 원문을 그대로 보여줌
            qrContent = barcode.rawValue ?: ""
            // url 이 아닐 땐 기본 no-op 리스너 유지
        }
    }
}
