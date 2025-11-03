package com.example.camerax_mlkit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.camerax_mlkit.security.WhitelistManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 결제 안내 노출의 단일 진입 게이트.
 * - 상태 소스: 지오펜스, 비콘, 신뢰 Wi-Fi
 * - 시연 정책: 지오펜스가 안 와도 비콘이 정상일 때는 들어온 걸로 취급
 */
object TriggerGate {

    private const val TAG = "TriggerGate"

    // ✨ 시연용: true면 지오펜스를 강제로 만족으로 간주
    private const val FORCE_GEOFENCE = true

    const val ACTION_PAY_PROMPT = "com.example.camerax_mlkit.ACTION_PAY_PROMPT"
    private const val CH_PAY_PROMPT = "pay_prompt"
    private const val NOTI_ID = 2025

    // ─────────────────────────────────────────────────────────────
    // Live 판단 파라미터
    // ─────────────────────────────────────────────────────────────
    /** 매장 후보로 간주할 "최근 탐지" 기준 (ms) — A/B 온오프 신뢰도에 직접 영향 */
    private const val LIVE_MAX_AGE_MS = 6_000L

    @Volatile private var onTrustedWifi: Boolean = false
    @Volatile private var inGeofence: Boolean = false
    @Volatile private var nearBeacon: Boolean = false
    @Volatile private var lastFenceId: String? = null

    data class BeaconMeta(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val locationId: String?,
        val merchantId: String?,
        val nonce: String?,
        val rssi: Int
    )

    // 매장 선택 UI에 넘길 용도
    data class UiStore(
        val locationId: String,
        val storeName: String
    )

    // ─────────────────────────────────────────────────────────────
    // 최근 감지 비콘(화이트리스트 통과분만) 저장소
    //   key: locationId
    //   value: DetectedBeacon(lastSeenMs 포함)
    // Eve는 화이트리스트에서 걸러지므로 애초에 들어오지 않음.
    // ─────────────────────────────────────────────────────────────
    private val detectedBeacons: ConcurrentHashMap<String, DetectedBeacon> = ConcurrentHashMap()

    data class DetectedBeacon(
        val locationId: String,
        val storeName: String,
        @Volatile var lastSeenMs: Long,
        @Volatile var rssi: Int?
    )

    /** 외부(BeaconForegroundService)에서 호출: 화이트리스트 통과 시 후보 업데이트 */
    fun addOrUpdateDetectedBeacon(locationId: String, storeName: String, rssi: Int?) {
        val now = System.currentTimeMillis()
        detectedBeacons.compute(locationId) { _, prev ->
            if (prev == null) DetectedBeacon(locationId, storeName, now, rssi)
            else {
                prev.lastSeenMs = now
                prev.rssi = rssi
                prev
            }
        }
    }

    /** 현재(신선도 기준) 살아있는 매장 후보 목록 */
    fun getUiCandidatesForStoreSelection(): List<UiStore> {
        val now = System.currentTimeMillis()
        return detectedBeacons.values
            .filter { now - it.lastSeenMs <= LIVE_MAX_AGE_MS }
            .distinctBy { it.locationId }
            .sortedBy { it.storeName }
            .map { UiStore(it.locationId, it.storeName) }
    }

    /** 테스트/디버그용: 전체 목록 조회 */
    fun getDetectedBeacons(): List<DetectedBeacon> = detectedBeacons.values.sortedBy { it.storeName }

    /** 후보 전체 초기화 */
    fun clearDetectedBeacons() { detectedBeacons.clear() }

    /** 최근 maxAgeMs 이내에 탐지된 '화이트리스트 비콘'의 locationId 집합 */
    fun liveWhitelistedLocationSet(ctx: Context, maxAgeMs: Long = LIVE_MAX_AGE_MS): Set<String> {
        val now = System.currentTimeMillis()
        val out = mutableSetOf<String>()
        for (v in detectedBeacons.values) {
            if (now - v.lastSeenMs <= maxAgeMs) out += v.locationId
        }
        return out
    }

    private val currentBeaconRef = AtomicReference<BeaconMeta?>(null)

    private var lastShownAt = 0L
    private const val COOLDOWN_MS = 3000L
    private const val BEACON_NEAR_TIMEOUT_MS = 15000L
    private var beaconNearUntil = 0L

    /**
     * 🔁 “다시 처음부터”를 위해 내부 상태를 초기화.
     * - 매장 후보/알림 쿨다운/근접 상태/지오펜스 캐시 등을 모두 리셋
     */
    @Synchronized
    fun resetForReentry() {
        Log.d(TAG, "resetForReentry()")
        detectedBeacons.clear()
        detectedNotiShown = false
        lastShownAt = 0L

        currentBeaconRef.set(null)
        nearBeacon = false
        beaconNearUntil = 0L

        // 지오펜스/와이파이 캐시도 비워준다(다시 들어오면 최신값으로 재셋)
        lastFenceId = null
        inGeofence = false
        onTrustedWifi = false
    }

    // QR 경로에서도 동일 정책
    fun allowedForQr(): Boolean = evaluatePolicy().first

    // ─── 지오펜스 ───────────────────────────
    fun onGeofenceChanged(ctx: Context, inZone: Boolean, fenceId: String?) {
        inGeofence = inZone
        lastFenceId = fenceId?.lowercase()

        val beaconLoc = currentBeaconRef.get()?.locationId?.lowercase()
        Log.d(
            TAG,
            "Geofence → in=$inGeofence fenceId=$lastFenceId " +
                    "beaconNear=$nearBeacon beaconLoc=$beaconLoc wifi=$onTrustedWifi"
        )

        maybeShow(ctx, reason = "GEOFENCE")
        if (!inZone) cancelHeadsUp(ctx)
    }

    // ─── 비콘 ───────────────────────────────
    fun setBeaconMeta(
        ctx: Context,
        uuid: String,
        major: Int,
        minor: Int,
        nonce: String?,
        rssi: Int
    ) {
        val entry = WhitelistManager.findBeacon(uuid, major, minor)
        nearBeacon = entry != null

        if (entry != null) {
            currentBeaconRef.set(
                BeaconMeta(
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    locationId = entry.locationId,
                    merchantId = entry.merchantId,
                    nonce = nonce,
                    rssi = rssi
                )
            )
            // ✅ 화이트리스트 통과건만 후보 반영(+신선도 업데이트)
            entry.locationId?.let { locId ->
                val name = entry.storeName ?: locId
                addOrUpdateDetectedBeacon(locId, name, rssi)
            }
            markBeaconNearForAWhile(ctx)
        } else {
            // 화이트리스트 미통과(Eve 등) → 현재 메타/근접 false
            currentBeaconRef.set(null)
            nearBeacon = false
            cancelHeadsUp(ctx)
        }

        val fenceLoc = lastFenceId?.lowercase()
        val beaconLoc = entry?.locationId?.lowercase()
        Log.d(
            TAG,
            "Beacon → near=$nearBeacon uuid=$uuid major=$major minor=$minor rssi=$rssi " +
                    "beaconLoc=$beaconLoc fenceLoc=$fenceLoc"
        )
    }

    private fun markBeaconNearForAWhile(ctx: Context) {
        beaconNearUntil = System.currentTimeMillis() + BEACON_NEAR_TIMEOUT_MS
        maybeShow(ctx, reason = "BEACON")
        Handler(Looper.getMainLooper()).postDelayed({
            if (System.currentTimeMillis() >= beaconNearUntil) {
                nearBeacon = false
                currentBeaconRef.set(null)
                cancelHeadsUp(ctx)
                Log.d(TAG, "Beacon near timeout → near=false")
            }
        }, BEACON_NEAR_TIMEOUT_MS)
    }

    // ─── Wi-Fi ─────────────────────────────
    fun setTrustedWifi(ok: Boolean, ctx: Context) {
        onTrustedWifi = ok
        if (!ok) {
            cancelHeadsUp(ctx)
        } else {
            maybeShow(ctx, reason = "WIFI")
        }
        Log.d(TAG, "TrustedWiFi → $onTrustedWifi")
    }

    fun onAppResumed(ctx: Context) {
        val (allow, beaconLoc, fenceLoc) = evaluatePolicy()
        Log.d(TAG, "onAppResumed → allow=$allow beaconLoc=$beaconLoc fenceLoc=$fenceLoc")
    }

    fun getCurrentBeacon(): BeaconMeta? = currentBeaconRef.get()

    // ─── 정책 평가 ─────────────────────────
    fun evaluatePolicy(): Triple<Boolean, String?, String?> {
        val beaconLoc = currentBeaconRef.get()?.locationId?.lowercase()
        val fenceLocRaw = lastFenceId?.lowercase()

        // 시연모드: 비콘이 있으면 그 비콘 위치로 지오펜스를 맞춘다
        val fenceLoc = if (FORCE_GEOFENCE) beaconLoc ?: fenceLocRaw else fenceLocRaw

        val geoOk = if (FORCE_GEOFENCE) true else inGeofence
        // 현재는 데모 편의: 비콘 또는 신뢰 Wi-Fi면 허용(지오펜스 매칭은 시연 시 메시지용으로만 로그)
        val allow = nearBeacon || onTrustedWifi
        return Triple(allow, beaconLoc, fenceLoc)
    }

    // ─── 팝업 노출 ─────────────────────────
    @Synchronized
    private fun maybeShow(ctx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShownAt <= COOLDOWN_MS) return

        val (allow, beaconLoc, fenceLoc) = evaluatePolicy()
        if (!allow) {
            Log.d(TAG, "Popup BLOCK → geo=$inGeofence beacon=$nearBeacon wifi=$onTrustedWifi beaconLoc=$beaconLoc fenceLoc=$fenceLoc")
            return
        }

        // 한 번 보여줬다면 중복 방지
        if (detectedNotiShown) {
            Log.d(TAG, "Popup skipped (already shown once)")
            return
        }

        // 이번이 첫 노출
        detectedNotiShown = true
        lastShownAt = now

        val message = when (reason) {
            "WIFI", "BEACON" -> "정상 매장이 감지되었습니다."
            "GEOFENCE"       -> "매장 반경에 진입했습니다."
            else             -> "결제 안내"
        }

        postHeadsUp(ctx, title = "결제 안내", message = message, reason = reason)

        if (isAppForeground()) {
            ctx.sendBroadcast(Intent(ACTION_PAY_PROMPT).apply {
                putExtra("reason", reason)
                putExtra("geo", inGeofence)
                putExtra("beacon", nearBeacon)
                putExtra("wifi", onTrustedWifi)
                putExtra("fenceId", fenceLoc ?: "unknown")
            })
        }
    }

    @Volatile private var detectedNotiShown = false

    // ─── 알림 유틸 ─────────────────────────
    private fun postHeadsUp(ctx: Context, title: String, message: String, reason: String) {
        ensureHighChannel(ctx)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skip notification")
            return
        }

        val intent = Intent(ctx, StoreSelectRouterActivity::class.java).apply {
            putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason)
            putExtra("geo", inGeofence)
            putExtra("beacon", nearBeacon)
            putExtra("wifi", onTrustedWifi)
            putExtra("fenceId", lastFenceId ?: "unknown")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pi = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(ctx, CH_PAY_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
            .also { NotificationManagerCompat.from(ctx).notify(NOTI_ID, it) }
    }

    fun cancelHeadsUp(ctx: Context) =
        NotificationManagerCompat.from(ctx).cancel(NOTI_ID)

    private fun ensureHighChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_PAY_PROMPT,
                    "결제 안내",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun isAppForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
