package com.example.camerax_mlkit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { com.example.camerax_mlkit.security.WhitelistManager.load(this) } catch (_: Throwable) {}
        // (선택) 지오/비콘 알림 채널
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("pay_prompt", "결제 안내", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("beacon_scan", "Beacon scanning", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("beacon_fullscreen", "Payment prompt", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
            // 기존 proximity 채널은 실제로 안 쓰니 제거하거나 유지해도 무방
        }

    }
}
