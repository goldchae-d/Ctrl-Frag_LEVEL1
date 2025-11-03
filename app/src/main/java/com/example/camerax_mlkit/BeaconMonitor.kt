package com.example.camerax_mlkit

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.*

class BeaconMonitor(
    private val context: Context,
    private val targetNamePrefix: String? = null,          // 기기 이름 프리픽스 매칭(옵션)
    private val targetServiceUuid: ParcelUuid? = null,     // Service UUID 필터(옵션)
    private val ibeaconUuid: UUID? = null,                 // iBeacon UUID 매칭(옵션)
    private val rssiThreshold: Int = -70,                  // 근접 트리거용 RSSI 임계치
    private val minTriggerIntervalMs: Long = 30_000L,      // onNear 중복 방지 간격
    private val exitTimeoutMs: Long = 5_000L,              // EXIT 판정 타임아웃
    private val onNear: () -> Unit,                        // 근접(ENTER) 트리거
    private val onFar: (() -> Unit)? = null,               // 이탈(EXIT) 콜백(옵션)
    private val onFrame: ((uuid: String, major: Int, minor: Int, nonce: String?, rssi: Int) -> Unit)? = null
) {
    private var lastTriggerTs = 0L
    private var scanner: BluetoothLeScanner? = null
    private var started = false

    // 마지막으로 "관심 광고"를 본 시각 (RSSI 임계와 무관)
    @Volatile private var lastSeenMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val exitChecker = object : Runnable {
        override fun run() {
            if (!started) return
            val now = System.currentTimeMillis()
            if (now - lastSeenMs >= exitTimeoutMs) {
                onFar?.invoke()
                lastSeenMs = now // 다음 ENTER 대비
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun hasBlePermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val scanOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else true
        val connOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true
        return fine && scanOk && connOk
    }

    private fun buildFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()
        if (targetServiceUuid != null) {
            filters += ScanFilter.Builder().setServiceUuid(targetServiceUuid).build()
        }
        // iBeacon(ManufacturerData)와 이름 프리픽스는 콜백 내부에서 검사
        return filters
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it) } }
        override fun onScanFailed(errorCode: Int) { /* no-op */ }
    }

    private fun handle(res: ScanResult) {
        val record = res.scanRecord ?: return

        // 1) 이름 프리픽스
        val nameOk = targetNamePrefix?.let { prefix ->
            val n = record.deviceName
            n?.startsWith(prefix, ignoreCase = true) == true
        } ?: true

        // 2) iBeacon 파싱
        var parsedOk = false
        var uuidStr: String? = null
        var major = -1
        var minor = -1

        val data = record.getManufacturerSpecificData(0x004C) // Apple company ID
        if (data != null && data.size >= 23) {
            // iBeacon: 0x02 0x15 | 16B UUID | 2B Major | 2B Minor | 1B TxPower
            val isIBeacon = (data[0].toInt() == 0x02) && (data[1].toInt() == 0x15)
            if (isIBeacon) {
                val uuidBytes = data.copyOfRange(2, 18)
                uuidStr = bytesToUuidString(uuidBytes)
                // big-endian
                major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
                minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
                parsedOk = true
            }
        }

        // 3) 타깃 UUID 매칭(옵션)
        val iBeaconOk = if (ibeaconUuid != null) {
            parsedOk && runCatching { UUID.fromString(uuidStr) == ibeaconUuid }.getOrDefault(false)
        } else {
            nameOk
        }

        if (nameOk && iBeaconOk) {
            // 관심 광고를 봄 → 시간 갱신
            lastSeenMs = System.currentTimeMillis()
            val rssi = res.rssi

            // 프레임 콜백: TriggerGate.setBeaconMeta(...)에서 화이트리스트 매칭/정책 판정
            if (parsedOk && uuidStr != null) {
                onFrame?.invoke(uuidStr!!, major, minor, /* nonce */ null, rssi)
            }

            // 근접(ENTER) 트리거
            if (rssi >= rssiThreshold) maybeTrigger()
        }
    }

    private fun maybeTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTs >= minTriggerIntervalMs) {
            lastTriggerTs = now
            onNear()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        if (!hasBlePermissions()) return

        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        val filters = buildFilters()
        scanner = bt.bluetoothLeScanner
        scanner?.startScan(filters, settings, callback)

        lastSeenMs = System.currentTimeMillis()
        handler.post(exitChecker)

        started = true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        scanner?.stopScan(callback)
        handler.removeCallbacks(exitChecker)
        started = false
    }

    private fun bytesToUuidString(b: ByteArray): String {
        require(b.size == 16)
        fun hex(i: Int) = String.format("%02X", b[i])
        val s = buildString { for (i in 0 until 16) append(hex(i)) }
        return "${s.substring(0,8)}-${s.substring(8,12)}-${s.substring(12,16)}-${s.substring(16,20)}-${s.substring(20,32)}"
    }
}
