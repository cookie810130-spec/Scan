package com.store.inventoryscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ScanActivity : AppCompatActivity() {

    private enum class Mode {
        POINT,
        INVENTORY
    }

    private var mode = Mode.POINT

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var barcodeScanner: BarcodeScanner? = null

    private var successPlayer: MediaPlayer? = null
    private var failPlayer: MediaPlayer? = null

    private var isScanning = false
    private var isProcessingFrame = false
    private var lastScanTime = 0L

    /*
     * ============================================================
     * ZXing-C++ 第二引擎
     * ============================================================
     *
     * 真正的 BarcodeReader
     * 放在 ZxingCppDecoder.kt。
     *
     * 這裡只負責掃描執行緒與補掃時間控制。
     */

    private val scanExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor()

    private var lastZxingCppAttemptTime = 0L

    /*
     * ============================================================
     * View
     * ============================================================
     */

    private lateinit var previewView: PreviewView
    private lateinit var etQty: EditText
    private lateinit var etManualCode: EditText

    private lateinit var btnScan: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnReport: MaterialButton
    private lateinit var btnRefreshDb: MaterialButton
    private lateinit var btnZoom: MaterialButton

    /*
     * Zoom 模式
     *
     * false：
     * CameraX 自動決定解析度
     * 不主動放大
     *
     * true：
     * ImageAnalysis 目標 1280x720
     * 並套用數位 Zoom
     */

    private var zoomEnabled = false

    private lateinit var tvStatus: TextView
    private lateinit var tvLastItem: TextView
    private lateinit var tvDbStatus: TextView
    private lateinit var tvSummary: TextView

    private lateinit var recyclerView: RecyclerView

    private lateinit var layoutScannerSection: View
    private lateinit var layoutReportSection: View
    private lateinit var layoutInventorySection: View

    private lateinit var tvReportSummary: TextView
    private lateinit var tvReportContent: LinearLayout
    private lateinit var btnBackToScan: MaterialButton

    private lateinit var inventoryContent: LinearLayout
    private lateinit var tvInventorySummary: TextView
    private lateinit var etInventoryManualCode: EditText
    private lateinit var inventoryPreviewView: PreviewView

    private val barcodeMap =
        mutableMapOf<String, ItemInfo>()

    private val scannedRecords =
        linkedMapOf<String, ScanRecord>()

    private val inventoryRecords =
        linkedMapOf<String, ScanRecord>()

    private lateinit var adapter: ScanAdapter

    private val cacheFileName =
        "product_cache.json"

    @Volatile
    private var cloudLoading = false

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

    private val GAS_WEB_APP_URL =
        "https://script.google.com/macros/s/AKfycbxD84499eLT9602gFVbCsKHrFAUgGYvOayHH9uNRc79HYD4sAQZYCuOA-j2KypNnLx1/exec"

    companion object {

        private const val REQUEST_CAMERA = 1001

        private const val MAX_RETRY = 3

        /*
         * ZXing-C++ DataBar 補掃最短間隔。
         *
         * ML Kit 是主要引擎。
         * 只有 ML Kit 沒找到條碼時，
         * 才讓 ZXing-C++ 進行補掃。
         */

        private const val ZXING_CPP_INTERVAL_MS = 250L

        /*
         * ========================================================
         * 使用者設定
         * ========================================================
         */

        private const val PREFS_NAME =
            "scan_settings"

        private const val PREF_ZOOM_ENABLED =
            "zoom_enabled"
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_scan
        )

        bindViews()
        setupRecycler()
        setupButtons()
        setupSounds()

        /*
         * 讀取上次的 Zoom 設定。
         *
         * 使用者開啟一次後，
         * 下次開 App 仍然保持。
         */

        zoomEnabled =
            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            ).getBoolean(
                PREF_ZOOM_ENABLED,
                false
            )

        updateZoomButton()

        /*
         * ========================================================
         * Engine 1：Google ML Kit
         * ========================================================
         *
         * 只啟用一維條碼。
         *
         * 不啟用：
         * QR
         * Data Matrix
         * PDF417
         * Aztec
         *
         * DataBar 交給 ZXing-C++。
         */

        val oneDimensionalOptions =
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E
                )
                .build()

        barcodeScanner =
            BarcodeScanning.getClient(
                oneDimensionalOptions
            )

        loadProductCache()

        if (hasCameraPermission()) {

            startCamera()

        } else {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA
                ),
                REQUEST_CAMERA
            )
        }

        fetchCloudData()

        updateModeUi()
    }

    private fun bindViews() {

        previewView =
            findViewById(
                R.id.previewView
            )

        etQty =
            findViewById(
                R.id.etQty
            )

        etManualCode =
            findViewById(
                R.id.etManualCode
            )

        btnScan =
            findViewById(
                R.id.btnScan
            )

        btnClear =
            findViewById(
                R.id.btnClear
            )

        btnReport =
            findViewById(
                R.id.btnReport
            )

        btnRefreshDb =
            findViewById(
                R.id.btnRefreshDb
            )

        btnZoom =
            findViewById(
                R.id.btnZoom
            )

        tvStatus =
            findViewById(
                R.id.tvStatus
            )

        tvLastItem =
            findViewById(
                R.id.tvLastItem
            )

        tvDbStatus =
            findViewById(
                R.id.tvDbStatus
            )

        tvSummary =
            findViewById(
                R.id.tvSummary
            )

        recyclerView =
            findViewById(
                R.id.recyclerView
            )

        layoutScannerSection =
            findViewById(
                R.id.layoutScannerSection
            )

        layoutReportSection =
            findViewById(
                R.id.layoutReportSection
            )

        layoutInventorySection =
            findViewById(
                R.id.layoutInventorySection
            )

        tvReportSummary =
            findViewById(
                R.id.tvReportSummary
            )

        tvReportContent =
            findViewById(
                R.id.tvReportContent
            )

        btnBackToScan =
            findViewById(
                R.id.btnBackToScan
            )

        inventoryContent =
            findViewById(
                R.id.inventoryContent
            )

        tvInventorySummary =
            findViewById(
                R.id.tvInventorySummary
            )

        etInventoryManualCode =
            findViewById(
                R.id.etInventoryManualCode
            )

        inventoryPreviewView =
            findViewById(
                R.id.inventoryPreviewView
            )
    }

    private fun setupRecycler() {

        adapter =
            ScanAdapter(
                emptyList()
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        recyclerView.adapter =
            adapter
    }

    private fun setupButtons() {

        findViewById<MaterialButton>(
            R.id.btnModePoint
        ).setOnClickListener {

            if (mode != Mode.POINT) {

                mode = Mode.POINT

                showPointMode()
            }
        }

        findViewById<MaterialButton>(
            R.id.btnModeInventory
        ).setOnClickListener {

            if (mode != Mode.INVENTORY) {

                mode = Mode.INVENTORY

                showInventoryMode()
            }
        }

        /*
         * 點貨掃描：
         *
         * 按下 → 開始
         * 放開 → 停止
         */

        setupHoldToScan(
            btnScan
        )

        btnClear.setOnClickListener {
            clearRecords()
        }

        btnReport.setOnClickListener {
            showReportPage()
        }

        btnRefreshDb.setOnClickListener {
            fetchCloudData(true)
        }

        /*
         * Zoom 開關
         */

        btnZoom.setOnClickListener {

            zoomEnabled =
                !zoomEnabled

            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )
                .edit()
                .putBoolean(
                    PREF_ZOOM_ENABLED,
                    zoomEnabled
                )
                .apply()

            updateZoomButton()

            /*
             * 切換 Zoom 模式後，
             * 重新建立 CameraX ImageAnalysis。
             */

            stopScanning()

            if (
                mode ==
                Mode.INVENTORY
            ) {

                startInventoryCamera()

            } else {

                startCamera()
            }
        }

        btnBackToScan.setOnClickListener {
            showPointMode()
        }

        etManualCode.setOnEditorActionListener {
                _,
                actionId,
                event ->

            val enter =
                actionId ==
                    android.view.inputmethod.EditorInfo
                        .IME_ACTION_DONE ||
                (
                    event?.keyCode ==
                        KeyEvent.KEYCODE_ENTER &&
                    event.action ==
                        KeyEvent.ACTION_DOWN
                )

            if (enter) {

                handleManualCode()

                true

            } else {

                false
            }
        }

        etInventoryManualCode
            .setOnEditorActionListener {
                    _,
                    actionId,
                    event ->

                val enter =
                    actionId ==
                        android.view.inputmethod.EditorInfo
                            .IME_ACTION_DONE ||
                    (
                        event?.keyCode ==
                            KeyEvent.KEYCODE_ENTER &&
                        event.action ==
                            KeyEvent.ACTION_DOWN
                    )

                if (enter) {

                    handleInventoryManualCode()

                    true

                } else {

                    false
                }
            }

        /*
         * 盤點掃描：
         *
         * 同樣改成按住掃描。
         */

        setupHoldToScan(
            findViewById(
                R.id.btnInventoryScan
            )
        )

        findViewById<MaterialButton>(
            R.id.btnInventoryGenerate
        ).setOnClickListener {
            generateInventoryPdf()
        }

        findViewById<MaterialButton>(
            R.id.btnInventoryClear
        ).setOnClickListener {
            clearInventory()
        }
    }

    /*
     * ============================================================
     * 按住掃描
     * ============================================================
     *
     * ACTION_DOWN：
     * 開始掃描
     *
     * ACTION_UP：
     * 放開 → 停止
     *
     * ACTION_CANCEL：
     * 系統取消觸控 → 停止
     *
     * 條碼成功時，
     * onBarcodeDetected() 前面已經會 stopScanning()。
     */

    private fun setupHoldToScan(
        button: MaterialButton
    ) {

        button.setOnTouchListener {
                view,
                event ->

            when (
                event.actionMasked
            ) {

                MotionEvent.ACTION_DOWN -> {

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants
                            .VIRTUAL_KEY
                    )

                    triggerScan()

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    stopScanning()

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants
                            .VIRTUAL_KEY_RELEASE
                    )

                    true
                }

                else -> {

                    true
                }
            }
        }
    }

    /*
     * ============================================================
     * Zoom 按鈕文字
     * ============================================================
     */

    private fun updateZoomButton() {

        btnZoom.text =
            if (zoomEnabled) {

                "Zoom：ON"

            } else {

                "Zoom：OFF"
            }
    }

    /*
     * ============================================================
     * CameraX ImageAnalysis
     * ============================================================
     *
     * OFF：
     * 使用 CameraX 自動決定解析度。
     *
     * ON：
     * 目標 1280x720。
     */

    private fun buildImageAnalysis():
        ImageAnalysis {

        val builder =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    ImageAnalysis
                        .STRATEGY_KEEP_ONLY_LATEST
                )

        if (zoomEnabled) {

            builder.setTargetResolution(
                Size(
                    1280,
                    720
                )
            )
        }

        return builder.build()
    }

    /*
     * ============================================================
     * 套用 Zoom
     * ============================================================
     *
     * 使用 CameraX 官方 CameraControl。
     *
     * 最大倍率依手機硬體而定。
     *
     * ON：
     * 最多使用 2x
     *
     * OFF：
     * 回到 1x
     */

    private fun applyZoomMode() {

        val cam =
            camera
                ?: return

        val zoomState =
            cam.cameraInfo
                .zoomState
                .value

        val maxZoom =
            zoomState
                ?.maxZoomRatio
                ?: 1f

        val targetZoom =
            if (zoomEnabled) {

                minOf(
                    2f,
                    maxZoom
                )

            } else {

                1f
            }

        cam.cameraControl
            .setZoomRatio(
                targetZoom
            )
    }

    // ============================================================
    // Part 2：音效、鍵盤、模式切換、相機啟動
    // ============================================================

    private fun setupSounds() {
        try {
            successSound = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
        } catch (_: Exception) {
            successSound = null
        }
    }

    private fun playSuccessSound() {
        try {
            successSound?.let {
                if (it.isPlaying) it.seekTo(0)
                it.start()
            }
        } catch (_: Exception) {
        }
    }

    private fun playFailSound() {
        try {
            val tone = MediaPlayer.create(
                this,
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            )
            tone?.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            tone?.start()
        } catch (_: Exception) {
        }
    }

    // ============================================================
    // 鍵盤掃描器輸入
    // ============================================================

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            val code = scannerBuffer.toString()

            if (code.isNotBlank()) {
                scannerBuffer.clear()
                handleManualCode(code.trim())
            }

            return true
        }

        val unicodeChar = event?.unicodeChar ?: 0

        if (unicodeChar != 0) {
            scannerBuffer.append(unicodeChar.toChar())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // ============================================================
    // 點貨模式
    // ============================================================

    private fun showPointMode() {

        currentMode = Mode.POINT

        layoutPoint.visibility = View.VISIBLE
        layoutInventory.visibility = View.GONE

        btnModePoint.isSelected = true
        btnModeInventory.isSelected = false

        tvLastItem.visibility = View.VISIBLE

        updateModeUi()

        startCamera()
    }

    // ============================================================
    // 庫存模式
    // ============================================================

    private fun showInventoryMode() {

        currentMode = Mode.INVENTORY

        layoutPoint.visibility = View.GONE
        layoutInventory.visibility = View.VISIBLE

        btnModePoint.isSelected = false
        btnModeInventory.isSelected = true

        tvLastItem.visibility = View.GONE

        updateModeUi()

        startInventoryCamera()
    }

    // ============================================================
    // 更新模式 UI
    // ============================================================

    private fun updateModeUi() {

        when (currentMode) {

            Mode.POINT -> {

                btnScan.text =
                    if (isScanning) "掃描中…" else "開始掃描"

            }

            Mode.INVENTORY -> {

                btnInventoryScan.text =
                    if (isScanning) "掃描中…" else "掃描"

            }
        }

        updateZoomButton()
    }

    // ============================================================
    // 點貨模式相機
    // ============================================================

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            try {

                val cameraProvider =
                    cameraProviderFuture.get()

                cameraProvider.unbindAll()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageAnalysis = buildImageAnalysis()

                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                applyZoomMode(camera)

                updateModeUi()

            } catch (e: Exception) {

                Toast.makeText(
                    this,
                    "相機啟動失敗：${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ============================================================
    // 庫存模式相機
    // ============================================================

    private fun startInventoryCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            try {

                val cameraProvider =
                    cameraProviderFuture.get()

                cameraProvider.unbindAll()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                imageAnalysis = buildImageAnalysis()

                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                applyZoomMode(camera)

                updateModeUi()

            } catch (e: Exception) {

                Toast.makeText(
                    this,
                    "相機啟動失敗：${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ============================================================
    // Part 3：掃描控制
    //
    // 操作方式：
    // 1. 按住「開始掃描」→ 開始掃描
    // 2. 放開按鈕 → 停止掃描
    // 3. 掃描成功 → 立即停止
    //
    // ML Kit → ZXing-C++ DataBar 雙引擎維持不變
    // ============================================================

    private fun triggerScan() {

        if (isScanning) {
            return
        }

        isScanning = true
        isProcessingFrame = false

        // 每次重新開始掃描時，重新允許 ZXing-C++ fallback
        lastZxingCppAttemptTime = 0L

        when (currentMode) {

            Mode.POINT -> {
                btnScan.text = "掃描中…"
            }

            Mode.INVENTORY -> {
                btnInventoryScan.text = "掃描中…"
            }
        }

        imageAnalysis?.clearAnalyzer()

        imageAnalysis?.setAnalyzer(scanExecutor) { imageProxy ->
            processImage(imageProxy)
        }

        updateModeUi()
    }


    // ============================================================
    // 停止掃描
    // ============================================================

    private fun stopScanning() {

        isScanning = false
        isProcessingFrame = false

        imageAnalysis?.clearAnalyzer()

        when (currentMode) {

            Mode.POINT -> {
                btnScan.text = "開始掃描"
            }

            Mode.INVENTORY -> {
                btnInventoryScan.text = "掃描"
            }
        }

        updateModeUi()
    }


    // ============================================================
    // 掃描狀態檢查
    // ============================================================

    private fun isScanActive(): Boolean {
        return isScanning && imageAnalysis != null
    }
    // ============================================================
    // Part 4：影像分析
    //
    // 掃描順序：
    //
    // ① ML Kit
    //       ↓
    //    找到 1D 條碼 → 成功
    //
    //       ↓ 找不到
    //
    // ② ZXing-C++ DataBar
    //       ↓
    //    找到 → 成功
    //
    //       ↓
    //
    //    繼續掃下一張
    //
    // 只處理 1D 條碼，不加入 QR / Data Matrix / PDF417 等 2D
    // ============================================================

    private fun processImage(imageProxy: ImageProxy) {

        if (!isScanning) {
            imageProxy.close()
            return
        }

        if (isProcessingFrame) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image

        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true

        val rotationDegrees =
            imageProxy.imageInfo.rotationDegrees

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            rotationDegrees
        )

        // ========================================================
        // 第一引擎：ML Kit
        // ========================================================

        barcodeScanner.process(inputImage)

            .addOnSuccessListener { barcodes ->

                if (!isScanning) {
                    return@addOnSuccessListener
                }

                var found = false

                for (barcode in barcodes) {

                    val rawValue =
                        barcode.rawValue?.trim()

                    if (rawValue.isNullOrEmpty()) {
                        continue
                    }

                    /*
                     * 只接受 1D Barcode。
                     *
                     * ML Kit 的 supported formats 已經在
                     * setupBarcodeScanner() 限定為：
                     *
                     * CODE_128
                     * CODE_39
                     * CODE_93
                     * CODABAR
                     * EAN_13
                     * EAN_8
                     * ITF
                     * UPC_A
                     * UPC_E
                     */

                    found = true

                    runOnUiThread {

                        if (!isScanning) {
                            return@runOnUiThread
                        }

                        onBarcodeDetected(rawValue)
                    }

                    break
                }

                /*
                 * ML Kit 沒有找到有效條碼。
                 *
                 * 交給 ZXing-C++ DataBar fallback。
                 */
                if (!found && isScanning) {
                    tryZxingCppFallback(imageProxy)
                }

            }

            .addOnFailureListener {

                /*
                 * ML Kit 發生分析錯誤時，
                 * 不直接停止掃描。
                 *
                 * 改交給 ZXing-C++ fallback。
                 */

                if (isScanning) {
                    tryZxingCppFallback(imageProxy)
                }
            }

            .addOnCompleteListener {

                /*
                 * 注意：
                 *
                 * 如果這一幀已經交給 ZXing fallback，
                 * fallback 會自己負責最後的 close。
                 *
                 * 因此這裡不能直接 close。
                 */

                if (!isScanning) {
                    isProcessingFrame = false
                }
            }
    }


    // ============================================================
    // ZXing-C++ DataBar fallback
    // ============================================================

    private fun tryZxingCppFallback(
        imageProxy: ImageProxy
    ) {

        if (!isScanning) {
            imageProxy.close()
            isProcessingFrame = false
            return
        }

        /*
         * ZXing-C++ 不需要每一張影像都跑。
         *
         * 降低 CPU 負載，避免雙引擎同時一直吃滿 CPU。
         */
        val now = System.currentTimeMillis()

        if (now - lastZxingCppAttemptTime < 250L) {

            imageProxy.close()
            isProcessingFrame = false

            return
        }

        lastZxingCppAttemptTime = now

        scanExecutor.execute {

            try {

                if (!isScanning) {
                    return@execute
                }

                val result =
                    scanWithZxingCpp(imageProxy)

                if (!result.isNullOrBlank()) {

                    runOnUiThread {

                        if (!isScanning) {
                            return@runOnUiThread
                        }

                        onBarcodeDetected(
                            result.trim()
                        )
                    }
                }

            } catch (_: Exception) {

                // ZXing fallback 失敗時不顯示錯誤，
                // 繼續等待下一張影像。

            } finally {

                try {
                    imageProxy.close()
                } catch (_: Exception) {
                }

                isProcessingFrame = false
            }
        }
    }
    /*
     * ============================================================
     * 條碼找到後
     * ============================================================
     *
     * 點貨模式：
     *   使用 etQty 的數量
     *
     * 盤點模式：
     *   直接加入盤點清單
     *
     * 數量允許：
     *   1
     *   5
     *   -1
     *   -5
     *
     * 0 無效
     */

    private fun onBarcodeDetected(
        code: String
    ) {

        val item =
            findItem(code)

        /*
         * ------------------------------
         * 盤點模式
         * ------------------------------
         */

        if (mode == Mode.INVENTORY) {

            if (item != null) {

                addInventoryItem(item)

                playSuccessSound()

                tvStatus.text =
                    "✓ 已加入：${item.name}"

            } else {

                playFailSound()

                Toast.makeText(
                    this,
                    "查無此條碼：$code",
                    Toast.LENGTH_SHORT
                ).show()

                tvStatus.text =
                    "資料庫沒有此條碼"
            }

            return
        }

        /*
         * ------------------------------
         * 點貨模式
         * ------------------------------
         */

        val qty =
            getPointQuantity()

        /*
         * 數量無效時，不進行點貨。
         */

        if (qty == null) {
            return
        }

        if (item != null) {

            playSuccessSound()

            recordItem(
                item.customCode,
                item.intlCode,
                item.name,
                qty,
                item.storage
            )

            tvStatus.text =
                "✓ 掃描成功　${formatQuantity(qty)}"

            /*
             * 成功後恢復預設數量 1。
             */

            etQty.setText("1")

        } else {

            /*
             * 點貨模式找不到商品：
             *
             * 保留目前專案原本功能，
             * 顯示建立商品視窗。
             *
             * GAS 邏輯不在這裡修改。
             */

            playFailSound()

            tvStatus.text =
                "資料庫沒有此條碼"

            showCreateItemDialog(
                code
            )
        }
    }


    /*
     * ============================================================
     * 點貨模式取得數量
     * ============================================================
     *
     * 允許：
     *
     *   1
     *   5
     *   -1
     *   -5
     *
     * 不允許：
     *
     *   0
     *   空白
     *   非數字
     */

    private fun getPointQuantity(): Int? {

        val text =
            etQty.text
                .toString()
                .trim()

        if (text.isEmpty()) {

            Toast.makeText(
                this,
                "請輸入數量",
                Toast.LENGTH_SHORT
            ).show()

            etQty.requestFocus()

            return null
        }

        val qty =
            text.toIntOrNull()

        if (qty == null) {

            Toast.makeText(
                this,
                "數量格式錯誤",
                Toast.LENGTH_SHORT
            ).show()

            etQty.requestFocus()

            return null
        }

        if (qty == 0) {

            Toast.makeText(
                this,
                "0 無效，請輸入 +1、-1 或其他非 0 數量",
                Toast.LENGTH_SHORT
            ).show()

            etQty.requestFocus()

            return null
        }

        return qty
    }


    /*
     * ============================================================
     * 數量顯示
     * ============================================================
     *
     * 正數：
     *   1  → +1
     *   5  → +5
     *
     * 負數：
     *   -1 → -1
     *   -5 → -5
     */

    private fun formatQuantity(
        qty: Int
    ): String {

        return if (qty > 0) {
            "+$qty"
        } else {
            qty.toString()
        }
    }


    /*
     * ============================================================
     * 手動輸入 / USB 條碼掃描器
     * ============================================================
     */

    private fun handleManualCode() {

        val code =
            etManualCode.text
                .toString()
                .trim()

        if (code.isEmpty()) {

            Toast.makeText(
                this,
                "請輸入自編碼或條碼",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val item =
            findItem(code)

        /*
         * ------------------------------
         * 找到商品
         * ------------------------------
         */

        if (item != null) {

            /*
             * 盤點模式
             */

            if (mode == Mode.INVENTORY) {

                addInventoryItem(item)

                playSuccessSound()

                etManualCode.text.clear()

                etManualCode.requestFocus()

                return
            }

            /*
             * 點貨模式
             */

            val qty =
                getPointQuantity()

            if (qty == null) {
                return
            }

            playSuccessSound()

            recordItem(
                item.customCode,
                item.intlCode,
                item.name,
                qty,
                item.storage
            )

            tvStatus.text =
                "✓ 手動輸入成功　${formatQuantity(qty)}"

            etQty.setText("1")

            etManualCode.text.clear()

            etManualCode.requestFocus()

        } else {

            /*
             * 找不到商品
             */

            playFailSound()

            tvStatus.text =
                "資料庫沒有此編碼"

            Toast.makeText(
                this,
                "查無此編碼：$code",
                Toast.LENGTH_SHORT
            ).show()

            etManualCode.selectAll()
        }
    }


    /*
     * ============================================================
     * 記錄點貨
     * ============================================================
     *
     * storage：
     *   GAS 的「儲區」
     *
     * 只顯示在「最後點貨」卡片。
     *
     * 不會加入盤點清單。
     */

    private fun recordItem(
        c: String,
        i: String,
        n: String,
        q: Int,
        storage: String = ""
    ) {

        val t =
            SimpleDateFormat(
                "yyyy/MM/dd HH:mm:ss",
                Locale.TAIWAN
            ).format(Date())

        /*
         * 已存在：
         *   累加本次數量
         *
         * 不存在：
         *   建立新紀錄
         */

        scannedRecords[c]?.apply {

            qty += q
            lastTime = t

        } ?: run {

            scannedRecords[c] =
                ScanRecord(
                    customCode = c,
                    intlCode = i,
                    name = n,
                    qty = q,
                    lastTime = t,
                    storage = storage
                )
        }

        val r =
            scannedRecords[c]!!

        /*
         * 儲區沒有資料時顯示 —
         */

        val displayStorage =
            r.storage
                .trim()
                .ifEmpty {
                    "—"
                }

        /*
         * ------------------------------
         * 最後點貨
         * ------------------------------
         *
         * 只在這張卡片顯示儲區。
         */

        tvLastItem.text =
            "最後點貨\n" +
            "${r.name}\n" +
            "自編碼：${r.customCode}　" +
            "儲區：$displayStorage　" +
            "累計：${r.qty} 件"

        /*
         * 更新下面的點貨清單
         */

        refreshList()
    }

