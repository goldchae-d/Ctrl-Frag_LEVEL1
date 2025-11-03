// app/src/main/java/com/example/camerax_mlkit/BeaconForegroundService.kt
package com.example.camerax_mlkit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.camerax_mlkit.security.WhitelistManager
import java.util.UUID

/**
 * 비콘 스캔을 포그라운드에서 유지하고, 광고 프레임을 받을 때마다
 * TriggerGate.setBeaconMeta(...) 및 후보 집계(addOrUpdateDetectedBeacon)를 수행.
 */
class BeaconForegroundService : Service() {

    companion object {
        private const val CH_SCAN = "beacon_scan"
        private const val CH_FULL = "beacon_fullscreen"
        private const val FG_ID = 1000

        fun start(ctx: Context) {
            val i = Intent(ctx, BeaconForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BeaconForegroundService::class.java))
        }
    }

    private lateinit var monitor: BeaconMonitor

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    try {
                        if (::monitor.isInitialized) {
                            monitor.stop()
                            monitor.start()
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        startForeground(FG_ID, buildOngoingNotification())

        // 화이트리스트 로드
        WhitelistManager.load(applicationContext)

        // BT 리시버
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // BeaconMonitor 초기화
        monitor = BeaconMonitor(
            context = this,
            targetNamePrefix = null,
            ibeaconUuid = UUID.fromString("74278BDA-B644-4520-8F0C-720EAF059935"),
            rssiThreshold = -90,
            minTriggerIntervalMs = 5_000L,
            exitTimeoutMs = 5_000L,
            onNear = { /* TriggerGate가 내부에서 팝업 판정 */ },
            onFar = {
                // 광고가 끊기면 헤드업 닫기(중복 방지)
                TriggerGate.cancelHeadsUp(applicationContext)
            },
            onFrame = { uuid, major, minor, nonce, rssi ->
                // 메타 업데이트 (팝업 판정 포함)
                TriggerGate.setBeaconMeta(
                    ctx = applicationContext,
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    nonce = nonce,
                    rssi = rssi
                )

                // 화이트리스트 매칭 → 후보 집계 맵 갱신
                val matched = WhitelistManager.findBeacon(uuid, major, minor)
                if (matched != null) {
                    val locId = matched.locationId
                    if (locId == null) {
                        android.util.Log.d(
                            "BeaconFS",
                            "frame uuid=$uuid major=$major minor=$minor rssi=$rssi wl=TRUE but no locationId"
                        )
                    } else {
                        TriggerGate.addOrUpdateDetectedBeacon(
                            locationId = locId,
                            storeName  = matched.storeName ?: locId,
                            rssi       = rssi
                        )
                        android.util.Log.d(
                            "BeaconFS",
                            "frame uuid=$uuid major=$major minor=$minor rssi=$rssi wl=TRUE store=${matched.storeName} loc=$locId"
                        )
                    }
                } else {
                    android.util.Log.d(
                        "BeaconFS",
                        "frame uuid=$uuid major=$major minor=$minor rssi=$rssi wl=FALSE"
                    )
                }
            }
        )

        monitor.start()
    }

    override fun onDestroy() {
        try { unregisterReceiver(btReceiver) } catch (_: Throwable) { }
        if (::monitor.isInitialized) monitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_SCAN, "Beacon scanning", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_FULL, "Payment prompt", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun buildOngoingNotification(): Notification {
        val contentPI = PendingIntent.getActivity(
            this, 1,
            Intent(this, PaymentPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_SCAN)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("비콘 스캔 중")
            .setContentText("매장 근접 여부를 감지하고 있습니다.")
            .setOngoing(true)
            .setContentIntent(contentPI)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun safeNotify(n: Notification) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()) {
            try { nm.notify(2001, n) } catch (_: SecurityException) { }
        }
    }
}
