package com.example.camerax_mlkit

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camerax_mlkit.databinding.ActivityMainBinding
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.ContentValues
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {

    // ───────── Camera / ML Kit ─────────
    private lateinit var viewBinding: ActivityMainBinding
    private var cameraExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var cameraController: LifecycleCameraController? = null

    // 인앱 스캐너/일반카메라 플래그
    private var scannerOnlyMode = false         // "openScanner" (인앱 스캐너 전용)
    private var plainCameraMode = false         // "plainCamera" (우리 앱 내 일반카메라 모드)

    // ───────── Geofence ─────────
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var settingsClient: SettingsClient

    // 자동 라우팅 중복/폭주 방지
    private var lastAutoRouteMs = 0L
    private var isRouting = false

    // 결제 여부 다이얼로그 상태
    @Volatile private var payChoiceDialogShowing = false

    /** TriggerGate → (브로드캐스트) → 여기서 라우터로 연결 */
    private val payPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TriggerGate.ACTION_PAY_PROMPT) return

            val reason  = intent.getStringExtra("reason") ?: "USER"
            val geo     = intent.getBooleanExtra("geo", false)
            val beacon  = intent.getBooleanExtra("beacon", false)
            val wifi    = intent.getBooleanExtra("wifi", false)
            val fenceId = intent.getStringExtra("fenceId") ?: "unknown"

            Log.d(TAG, "PAY_PROMPT(broadcast) → reason=$reason geo=$geo beacon=$beacon wifi=$wifi fence=$fenceId")

            // ✅ plainCamera 모드에선 라우팅 금지
            if (plainCameraMode) return

            // ✅ 후보 집계/선택은 라우터가 담당
            routeToStoreSelection(reason, geo, beacon, wifi, fenceId)
        }
    }

    /** BT/GPS 상태 변경 감지 → 켜졌을 때 다시 라우팅 */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (st == BluetoothAdapter.STATE_ON) {
                        routeToStoreSelectionSoon("BT_ON")
                    }
                }
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    if (isLocationEnabled()) {
                        routeToStoreSelectionSoon("GPS_ON")
                    }
                }
            }
        }
    }

    // 지오펜스 PendingIntent
    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(GEOFENCE_ACTION).setClass(
            this,
            com.example.camerax_mlkit.geofence.GeofenceBroadcastReceiver::class.java
        )
        val flags = if (Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(this, GEOFENCE_REQ_CODE, intent, flags)
    }

    // BLE 권한 런처
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val scan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_SCAN] ?: false) else true
        val connect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_CONNECT] ?: false) else true
        if (fine && scan && connect) {
            BeaconForegroundService.start(this)
        } else {
            Toast.makeText(this, "BLE 권한 거부(비콘 감지 비활성화)", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore */ }

    // BT 활성화 요청 런처 (성공/실패와 무관하게 복귀 시 재라우팅)
    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            routeToStoreSelectionSoon("BT_ON_FROM_DIALOG")
        }

    // ───────── Lifecycle ─────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WifiTrigger.start(this)
        ensurePostNotificationsPermission()

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        scannerOnlyMode = intent.getBooleanExtra("openScanner", false)
        plainCameraMode = intent.getBooleanExtra("plainCamera", false)

        viewBinding.cameraCaptureButton.setOnClickListener { takePhoto() }

        if (allPermissionsGranted()) startCameraSafely()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        geofencingClient = LocationServices.getGeofencingClient(this)
        settingsClient   = LocationServices.getSettingsClient(this)

        ensureLocationPermission {
            ensureLocationSettings {
                addOrUpdateDuksungGeofence()
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        ensureBlePermissions()

        scheduleInitialRoutingIfNeeded()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newScannerOnly = intent?.getBooleanExtra("openScanner", false) ?: false
        val newPlain       = intent?.getBooleanExtra("plainCamera", false) ?: false

        if (newScannerOnly && !scannerOnlyMode) {
            scannerOnlyMode = true
            Toast.makeText(this, "인앱 스캐너 모드로 전환되었습니다.", Toast.LENGTH_SHORT).show()
        }
        if (newPlain && !plainCameraMode) {
            plainCameraMode = true
            Toast.makeText(this, "일반카메라 모드로 전환되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TriggerGate.ACTION_PAY_PROMPT)
        ContextCompat.registerReceiver(
            this,
            payPromptReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val sf = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        registerReceiver(stateReceiver, sf)
    }

    override fun onResume() {
        super.onResume()
        TriggerGate.onAppResumed(applicationContext)
        scheduleInitialRoutingIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(payPromptReceiver) } catch (_: IllegalArgumentException) {}
        try { unregisterReceiver(stateReceiver) } catch (_: IllegalArgumentException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cameraExecutor?.shutdown() } catch (_: Throwable) {}
        barcodeScanner?.close()
        barcodeScanner = null
        cameraController = null
    }

    // ───────── Camera / QR ─────────
    private fun startCameraSafely() {
        if (cameraController != null && barcodeScanner != null) return

        val controller = LifecycleCameraController(baseContext)
        val previewView: PreviewView = viewBinding.viewFinder

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)

        controller.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(this),
            MlKitAnalyzer(
                listOf(scanner),
                COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this)
            ) { result: MlKitAnalyzer.Result? ->
                val barcodeResults = result?.getValue(scanner)
                if (barcodeResults.isNullOrEmpty() || barcodeResults.first() == null) {
                    previewView.overlay?.clear()
                    return@MlKitAnalyzer
                }

                val raw = barcodeResults[0].rawValue ?: return@MlKitAnalyzer

                // 1) plainCamera 모드면 일반카메라 동작
                if (plainCameraMode) {
                    handleAsPlainCamera(raw)
                    return@MlKitAnalyzer
                }

                // 2) BT/GPS 꺼짐 → 결제 여부 선택지
                if (!isBtOn() || !isLocationEnabled()) {
                    showPayChoiceDialog(raw)
                    return@MlKitAnalyzer
                }

                // 3) 정상 컨텍스트면 결제 플로우
                if (!scannerOnlyMode && !TriggerGate.allowedForQr()) return@MlKitAnalyzer
                startPaymentPrompt(raw)
            }
        )
        controller.bindToLifecycle(this)
        previewView.controller = controller

        cameraController = controller
        barcodeScanner = scanner
    }

    /** 일반카메라 동작: URL은 열고, 그 외는 토스트 */
    private fun handleAsPlainCamera(raw: String) {
        if (isUrl(raw)) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw))) }
            catch (_: Exception) { Toast.makeText(this, "URL 열기 실패", Toast.LENGTH_SHORT).show() }
        } else {
            Toast.makeText(this, "일반 QR: $raw", Toast.LENGTH_SHORT).show()
        }
    }

    /** ✅ 우리 앱의 Plain 카메라로 전환(매장/결제창의 '카메라 사용하기'와 동일 동작) */
    private fun openPlainCameraFromHere() {
        // 자기 자신을 SINGLE_TOP으로 재실행 → onNewIntent()에서 plainCameraMode=true 세팅
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("plainCamera", true)
        )
    }

    /** BT/GPS 꺼짐 상태에서 QR 인식 시 뜨는 선택지 다이얼로그 */
    private fun showPayChoiceDialog(raw: String) {
        if (payChoiceDialogShowing) return
        payChoiceDialogShowing = true

        val items = arrayOf("결제를 진행(블루투스 켜기)", "카메라 사용하기")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("결제하시겠습니까? 블루투스가 꺼져 있습니다.")
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> { // 결제를 진행(블루투스 켜기)
                        openBluetoothEnableScreen()
                    }
                    1 -> { // ✅ 카메라 사용하기 → 우리 앱 Plain 카메라로
                        openPlainCameraFromHere()
                    }
                }
                dialog.dismiss()
            }
            .setOnDismissListener { payChoiceDialogShowing = false }
            .setCancelable(true)
            .show()
    }

    /** 가능한 경우 ACTION_REQUEST_ENABLE, 불가하면 설정화면으로 */
    private fun openBluetoothEnableScreen() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "이 기기는 블루투스를 지원하지 않습니다.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            btEnableLauncher.launch(intent)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            catch (_: Exception) {
                Toast.makeText(this, "블루투스 설정 화면을 열 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startPaymentPrompt(qrCode: String) {
        if (plainCameraMode) return
        startActivity(
            Intent(this, PaymentPromptActivity::class.java)
                .putExtra(PaymentPromptActivity.EXTRA_QR_CODE, qrCode)
                .putExtra(PaymentPromptActivity.EXTRA_TRIGGER, "USER")
        )
    }

    private fun isUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true)

    private fun takePhoto() {
        val controller = cameraController ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "사진 촬영 실패: ${exc.message}", exc)
                    Toast.makeText(baseContext, "사진 촬영 실패", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "사진 저장 성공: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    // ───────── 자동 라우팅 유틸 ─────────
    private fun isBtOn(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun shouldAutoRouteNow(): Boolean {
        if (plainCameraMode) return false
        if (!isBtOn() || !isLocationEnabled()) return false
        val now = System.currentTimeMillis()
        return (now - lastAutoRouteMs) > 1000 && !isRouting
    }

    private fun scheduleInitialRoutingIfNeeded() {
        if (!shouldAutoRouteNow()) return
        isRouting = true
        viewBinding.root.postDelayed({
            try {
                routeToStoreSelection(
                    reason = "APP_START",
                    geo = true, beacon = true,
                    wifi = TriggerGate.allowedForQr(),
                    fenceId = "unknown"
                )
            } finally {
                lastAutoRouteMs = System.currentTimeMillis()
                isRouting = false
            }
        }, 600L)
    }

    private fun routeToStoreSelectionSoon(reason: String) {
        if (plainCameraMode) return
        isRouting = true
        viewBinding.root.postDelayed({
            try {
                routeToStoreSelection(
                    reason = reason,
                    geo = true, beacon = true,
                    wifi = TriggerGate.allowedForQr(),
                    fenceId = "unknown"
                )
            } finally {
                lastAutoRouteMs = System.currentTimeMillis()
                isRouting = false
            }
        }, 500L)
    }

    private fun routeToStoreSelection(
        reason: String,
        geo: Boolean,
        beacon: Boolean,
        wifi: Boolean,
        fenceId: String
    ) {
        if (plainCameraMode) return
        startActivity(
            Intent(this@MainActivity, StoreSelectRouterActivity::class.java).apply {
                putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason)
                putExtra("geo", geo)
                putExtra("beacon", beacon)
                putExtra("wifi", wifi)
                putExtra("fenceId", fenceId)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    // ───────── Geofence helpers ─────────
    private fun ensureLocationSettings(onReady: () -> Unit) {
        val req = LocationSettingsRequest.Builder()
            .addLocationRequest(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
                    .build()
            )
            .build()

        settingsClient.checkLocationSettings(req)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try { e.startResolutionForResult(this, RC_RESOLVE_LOCATION) }
                    catch (t: Throwable) {
                        Log.e(TAG, "Location settings resolution 실패", t)
                        Toast.makeText(this, "위치 설정을 켜주세요.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.e(TAG, "Location settings check 실패", e)
                    Toast.makeText(this, "위치 설정을 켜주세요.", Toast.LENGTH_LONG).show()
                }
            }
    }

    /** ✅ 덕성여대 시연: store_duksung_a + store_duksung_b 지점 등록 */
    @SuppressLint("MissingPermission")
    private fun addOrUpdateDuksungGeofence() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "지오펜스 등록 스킵: 위치 권한 미승인")
            return
        }

        val geofences = listOf(
            buildGeofence(
                id = "store_duksung_a",
                lat = 37.65326,
                lng = 127.01640,
                radius = 120f
            ),
            buildGeofence(
                id = "store_duksung_b",
                lat = 37.65390,
                lng = 127.01690,
                radius = 120f
            )
        )

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                        GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            .addGeofences(geofences)
            .build()

        geofencingClient.removeGeofences(geofencePendingIntent()).addOnCompleteListener {
            geofencingClient.addGeofences(request, geofencePendingIntent())
                .addOnSuccessListener {
                    Log.i(TAG, "✅ 지오펜스 등록 완료: $geofences")
                    Toast.makeText(this, "지오펜스 등록 완료!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    val code = (e as? ApiException)?.statusCode
                    Log.e(TAG, "❌ 지오펜스 등록 실패 code=$code", e)
                    if (e is SecurityException) {
                        Log.e(TAG, "권한 문제: 위치/백그라운드 위치 확인 필요")
                    }
                    Toast.makeText(this, "지오펜스 실패: ${code ?: e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun buildGeofence(id: String, lat: Double, lng: Double, radius: Float): Geofence =
        Geofence.Builder()
            .setRequestId(id.lowercase())
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or
                        Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(6_000)
            .build()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ───────── Permissions ─────────
    private fun ensureBlePermissions() {
        val needS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION).apply {
            if (needS) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        val missing = required.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) blePermissionLauncher.launch(required.toTypedArray())
        else BeaconForegroundService.start(this)
    }

    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val p = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(p)
            }
        }
    }

    private fun ensureLocationPermission(onGranted: () -> Unit = {}) {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted || !coarseGranted) {
            ActivityCompat.requestPermissions(this, LOCATION_PERMISSIONS, REQUEST_CODE_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(this, "백그라운드 위치 허용이 필요하면 설정에서 ‘항상 허용’을 선택하세요.", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        REQUEST_CODE_BACKGROUND_LOCATION
                    )
                    return
                }
            }
        }
        onGranted()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSIONS -> {
                if (allPermissionsGranted()) startCameraSafely()
                else { Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show(); finish() }
            }
            REQUEST_CODE_LOCATION -> {
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    ensureLocationPermission {
                        ensureLocationSettings {
                            addOrUpdateDuksungGeofence()
                            scheduleInitialRoutingIfNeeded()
                        }
                    }
                } else {
                    Toast.makeText(this, "위치 권한이 필요합니다(지오펜싱).", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_CODE_BACKGROUND_LOCATION -> {
                val bgGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!bgGranted) Toast.makeText(this, "백그라운드 위치 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ───────── Const ─────────
    companion object {
        private const val TAG = "CameraX-MLKit"

        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_LOCATION = 11
        private const val REQUEST_CODE_BACKGROUND_LOCATION = 12
        private const val RC_RESOLVE_LOCATION = 2001

        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        private const val GEOFENCE_ACTION = "com.example.camerax_mlkit.GEOFENCE_EVENT"
        private const val GEOFENCE_REQ_CODE = 1001
    }
}
