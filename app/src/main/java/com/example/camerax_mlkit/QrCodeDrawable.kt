package com.example.camerax_mlkit

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * A Drawable that handles displaying a QR Code's data and a bounding box around the QR code.
 */
class QrCodeDrawable(
    private val qrCodeViewModel: QrCodeViewModel
) : Drawable() {

    private val boundingRectPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 5f
        alpha = 200
    }

    private val contentRectPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
        alpha = 255
    }

    private val contentTextPaint = Paint().apply {
        color = Color.DKGRAY
        alpha = 255
        textSize = 36f
    }

    private val contentPadding = 25

    override fun draw(canvas: Canvas) {
        val box = qrCodeViewModel.boundingRect ?: return   // 바운딩 박스 없으면 그릴 것 없음
        val content = qrCodeViewModel.qrContent
        val textWidth = contentTextPaint.measureText(content).toInt()

        // 바운딩 박스
        canvas.drawRect(box, boundingRectPaint)

        // 텍스트 배경
        val bg = Rect(
            box.left,
            box.bottom + contentPadding / 2,
            box.left + textWidth + contentPadding * 2,
            box.bottom + contentTextPaint.textSize.toInt() + contentPadding
        )
        canvas.drawRect(bg, contentRectPaint)

        // 텍스트
        canvas.drawText(
            content,
            (box.left + contentPadding).toFloat(),
            (box.bottom + contentPadding * 2).toFloat(),
            contentTextPaint
        )
    }

    override fun setAlpha(alpha: Int) {
        boundingRectPaint.alpha = alpha
        contentRectPaint.alpha = alpha
        contentTextPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        boundingRectPaint.colorFilter = colorFilter
        contentRectPaint.colorFilter = colorFilter
        contentTextPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
