// app/src/main/java/com/example/camerax_mlkit/PaymentPromptActivity.kt
package com.example.camerax_mlkit

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.camerax_mlkit.crypto.RetrofitProvider
import com.example.camerax_mlkit.crypto.SessionKeyManager
import com.example.camerax_mlkit.security.QrToken
import com.example.camerax_mlkit.security.SecureQr
import com.example.camerax_mlkit.security.SignatureVerifier
import com.example.camerax_mlkit.security.WhitelistManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

class PaymentPromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_CODE = "extra_qr_code"
        const val EXTRA_TITLE   = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TRIGGER = "extra_trigger"
        const val EXTRA_STORE_NAME  = "STORE_NAME"
        const val EXTRA_LOCATION_ID = "LOCATION_ID"
        private const val TAG   = "PaymentPromptActivity"
    }

    private var dialog: BottomSheetDialog? = null
    private var sheetView: View? = null
    private var latestQrText: String? = null

    private var selectedStoreName: String? = null
    private var selectedLocationId: String? = null
    private var fenceId: String = "unknown"

    // ✅ 재등장 방지 가드
    private var selectionHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WhitelistManager.load(this)

        // ── 트리거/컨텍스트 수집 ──
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
        val geo     = intent.getBooleanExtra("geo", false)
        val beacon  = intent.getBooleanExtra("beacon", false)
        val wifiOk  = TriggerGate.allowedForQr()
        fenceId     = intent.getStringExtra("fenceId") ?: "unknown"

        // ✅ (1) BT/GPS 하드가드 — 여기!
        if (!isBtOn() || !isLocationOn()) {
            Log.d(TAG, "blocked: BT/GPS OFF")
            Toast.makeText(this, "블루투스/위치가 꺼져 있어 결제창을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // 정책: (지오∧비콘) OR (신뢰 Wi-Fi) OR (USER)
        val allow = ((geo && beacon) || wifiOk || trigger == "USER")
        if (!allow) { finish(); return }

        // (선택 강화) 지오펜스/비콘 불일치 차단
        if (geo && beacon) {
            val loc = TriggerGate.getCurrentBeacon()?.locationId
            if (loc != null && fenceId != "unknown" && loc != fenceId) {
                Toast.makeText(this, "지점 불일치: 결제를 진행할 수 없습니다.", Toast.LENGTH_LONG).show()
                finish(); return
            }
        }

        // 잠금화면에서도 표시
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // ① 매장 선택 먼저
        handleStoreSelectionFromIntent(intent)

        // ② 선택 반영해 토큰 생성
        lifecycleScope.launch {
            try {
                val (kid, sk) = SessionKeyManager.ensureKey(this@PaymentPromptActivity, RetrofitProvider.keyApi)
                val sid = SessionIdProvider.get(this@PaymentPromptActivity)
                val meta  = TriggerGate.getCurrentBeacon()
                val entry = meta?.let { WhitelistManager.findBeacon(it.uuid, it.major, it.minor) }
                val locId = selectedLocationId ?: (entry?.locationId ?: fenceId)
                val merchantId = entry?.merchantId ?: "merchant_unknown"

                val qrText = SecureQr.buildEncryptedToken(
                    kid, sk, sid, merchantId, null,
                    extra = mapOf("type" to "account", "location_id" to locId, "fence_id" to fenceId)
                )
                latestQrText = qrText
                setTokenTextIfPresent(qrText)
            } catch (_: Throwable) {
                latestQrText = null
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        fenceId = intent?.getStringExtra("fenceId") ?: fenceId

        // ✅ (2) newIntent 시에도 즉시 가드
        if (!isBtOn() || !isLocationOn()) {
            Log.d(TAG, "blocked(newIntent): BT/GPS OFF")
            Toast.makeText(this, "블루투스/위치가 꺼져 있어 결제창을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        handleStoreSelectionFromIntent(intent)
    }

    /** 상태 확인 함수 */
    private fun isBtOn(): Boolean =
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    private fun isLocationOn(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    /** 우리 앱의 CameraX 화면을 ‘일반카메라(Plain)’ 모드로 전환 */
    private fun openPlainCamera() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("plainCamera", true)
        )
        finish()
    }

    /**
     * 매장 선택 처리
     * - 후보가 0개면: ✅ 곧바로 Plain 카메라 전환
     * - 후보가 1개면: 다이얼로그 없이 즉시 처리
     * - 후보가 2개 이상이면: 다이얼로그 표시
     * - 여기서는 “카메라 사용하기” 항목을 **추가하지 않음** (라우터에서 이미 처리)
     */
    private fun handleStoreSelectionFromIntent(intent: Intent?) {
        if (selectionHandled) return

        // 0) ⬇️ 먼저, BeaconSelectionActivity에서 단일로 넘어온 케이스를 처리
        val singleName = intent?.getStringExtra(EXTRA_STORE_NAME)
        val singleLoc  = intent?.getStringExtra(EXTRA_LOCATION_ID)
        if (!singleName.isNullOrBlank() && !singleLoc.isNullOrBlank()) {
            selectionHandled = true
            selectedStoreName  = singleName
            selectedLocationId = singleLoc
            openPaymentForStore(singleLoc, singleName)
            return
        }

        // 1) 기존: 배열 기반 라우터 케이스 처리 (변경 없음)
        val storeNames  = intent?.getStringArrayListExtra("extra_store_names") ?: arrayListOf()
        val locationIds = intent?.getStringArrayListExtra("extra_locations")   ?: arrayListOf()

        if (storeNames.isEmpty() || locationIds.isEmpty()) {
            openPlainCamera()
            return
        }

        if (storeNames.size == 1 && locationIds.size == 1) {
            selectionHandled = true
            val onlyName = storeNames[0]
            val onlyLoc  = locationIds[0]

            if (onlyLoc == "__camera__") {
                openPlainCamera()
                return
            } else {
                selectedStoreName  = onlyName
                selectedLocationId = onlyLoc
                openPaymentForStore(onlyLoc, onlyName)
                return
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("결제하실 매장을 선택하세요")
            .setItems(storeNames.toTypedArray()) { _, which ->
                if (selectionHandled) return@setItems
                selectionHandled = true

                val chosenName = storeNames.getOrNull(which)
                val chosenLoc  = locationIds.getOrNull(which)

                if (chosenLoc == "__camera__") {
                    openPlainCamera()
                    return@setItems
                }
                selectedStoreName  = chosenName
                selectedLocationId = chosenLoc
                openPaymentForStore(chosenLoc, chosenName)
            }
            .setCancelable(false)
            .show()
    }



    private fun openPaymentForStore(locationId: String?, storeName: String?) {
        showOrExpandPayChooser(
            title = storeName ?: getString(R.string.title_pay),
            message = getString(R.string.subtitle_pay)
        )
    }

    // ── 결제 바텀시트 (카메라 버튼도 plainCamera로 동작) ──
    private fun showOrExpandPayChooser(title: String, message: String) {
        dialog?.let { d ->
            d.findViewById<TextView>(R.id.tvTitle)?.text = title
            d.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
                sheetView = it
            }
            latestQrText?.let { setTokenTextIfPresent(it) }
            return
        }

        val d = BottomSheetDialog(this)
        d.setContentView(R.layout.dialog_pay_chooser)
        d.setDismissWithAnimation(true)
        d.setOnShowListener {
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED; sheetView = it }

            d.findViewById<TextView>(R.id.tvTitle)?.text = title
            d.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            latestQrText?.let { setTokenTextIfPresent(it) }

            d.findViewById<View>(R.id.btnKakao)?.setOnClickListener { d.dismiss(); showKakaoPreview() }
            d.findViewById<View>(R.id.btnNaver)?.setOnClickListener { d.dismiss(); showNaverPreview() }
            d.findViewById<View>(R.id.btnToss )?.setOnClickListener { d.dismiss(); openTtareungi(); finish() }
            d.findViewById<View>(R.id.btnInApp)?.setOnClickListener { d.dismiss(); openPlainCamera() }
            d.findViewById<View>(R.id.btnCancel)?.setOnClickListener { d.dismiss(); finish() }
        }
        d.setOnCancelListener { finish() }
        d.show()
        dialog = d
    }

    /** 레이아웃에 tvToken id가 있을 때만 표시 */
    private fun setTokenTextIfPresent(text: String) {
        val tv = sheetView?.findViewById<TextView?>(R.id.tvToken) ?: return
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    // (미리보기/검증 유틸 등)
    private fun showPreview(@DrawableRes imgRes: Int, onClick: () -> Unit) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_qr_preview, null, false)
        dialog.setContentView(v)
        val img = v.findViewById<ImageView>(R.id.imgPreview)
        img.setImageResource(imgRes)
        img.setOnClickListener { onClick() }
        img.setOnLongClickListener { view ->
            view.alpha = 0.4f
            Toast.makeText(this, "QR을 분석 중입니다…", Toast.LENGTH_SHORT).show()
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            val bmp = BitmapFactory.decodeResource(resources, imgRes)
            val image = InputImage.fromBitmap(bmp, 0)
            scanner.process(image)
                .addOnSuccessListener { list ->
                    view.alpha = 1f
                    val raw = list.firstOrNull()?.rawValue
                    if (raw == null) { Toast.makeText(this, "QR을 인식하지 못했습니다.", Toast.LENGTH_SHORT).show(); return@addOnSuccessListener }
                    val isSecureCandidate = raw.contains(".")
                    val ok = if (isSecureCandidate) verifyQrAgainstContext(raw) else false
                    if (ok) { dialog.dismiss(); Toast.makeText(this, "검증 통과", Toast.LENGTH_SHORT).show(); showOrExpandPayChooser(getString(R.string.title_pay), getString(R.string.subtitle_pay)) }
                    else if (raw.startsWith("http://") || raw.startsWith("https://")) { dialog.dismiss(); startActivity(Intent(Intent.ACTION_VIEW, raw.toUri())) }
                    else { Toast.makeText(this, "검증 실패 또는 미지원 QR", Toast.LENGTH_SHORT).show() }
                }
                .addOnFailureListener { view.alpha = 1f; Toast.makeText(this, "분석 실패", Toast.LENGTH_SHORT).show() }
            true
        }
        dialog.setOnCancelListener { }
        dialog.show()
    }

    private fun showKakaoPreview() {
        val resId = qrFor(PayProvider.KAKAO, selectedLocationId, selectedStoreName)
        showPreview(resId) {}
    }

    private fun showNaverPreview() {
        val resId = qrFor(PayProvider.NAVER, selectedLocationId, selectedStoreName)
        showPreview(resId) {}
    }


    private fun openTtareungi() {
        val pkg = "com.dki.spb_android"
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) { startActivity(launch); return }
        try { startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())) }
        catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri())) }
    }

    private fun verifyQrAgainstContext(rawQr: String): Boolean {
        val parsed = QrToken.parse(rawQr) ?: return false
        val (payload, sig) = parsed
        val meta = TriggerGate.getCurrentBeacon() ?: return false
        val pubPem = WhitelistManager.getMerchantPubKey(payload.merchantId) ?: return false
        if (!SignatureVerifier.verifyEcdsaP256(pubPem, QrToken.normalizedMessageForSign(payload), sig)) return false
        val beaconLoc = meta.locationId ?: return false
        if (payload.locationId != beaconLoc) return false
        if (fenceId != "unknown" && payload.locationId != fenceId) return false
        val beaconNonce = meta.nonce ?: return false
        if (payload.nonce != beaconNonce) return false
        val nowSec = System.currentTimeMillis() / 1000
        return payload.expiry >= nowSec
    }

    override fun onDestroy() {
        dialog?.setOnShowListener(null)
        dialog?.setOnCancelListener(null)
        dialog = null
        sheetView = null
        super.onDestroy()
    }

    // ⬇️ 추가: 결제수단 구분용
    private enum class PayProvider { NAVER, KAKAO}

    // ⬇️ 추가: 매장 식별자를 A/B 키로 정규화
    private fun normalizeStoreKey(locationId: String?, storeName: String?): String {
        // 1) locationId 우선
        locationId?.let { id ->
            if (id.equals("A", true)) return "A"
            if (id.equals("B", true)) return "B"
            if (id.contains("loc-a", true)) return "A"
            if (id.contains("loc-b", true)) return "B"
        }
        // 2) storeName fallback
        val name = storeName ?: return "A"
        return when {
            name.contains("Bob", true) -> "B"
            name.contains("B (", true) -> "B"  // "B (Bob식당)" 같은 패턴
            else -> "A"
        }
    }

    // ⬇️ 추가: 결제수단 × 매장키 → 리소스 매핑
    @DrawableRes
    private fun qrFor(
        provider: PayProvider,
        locationId: String?,
        storeName: String?
    ): Int {
        val key = normalizeStoreKey(locationId, storeName)
        return when (provider) {
            PayProvider.NAVER -> when (key) {
                "B" -> R.drawable.naver_qr_b
                else -> R.drawable.naver_qr_a
            }
            PayProvider.KAKAO -> when (key) {
                "B" -> R.drawable.kakao_qr_b
                else -> R.drawable.kakao_qr_a
            }
        }
    }


}
