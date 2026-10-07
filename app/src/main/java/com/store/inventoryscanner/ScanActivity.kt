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
import android.view.Gravity
import android.view.KeyEvent
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
import java.util.concurrent.Executors
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

    // ============================================================
    // ZXing-C++ 第二引擎
    // 只在 ML Kit 沒有找到條碼時補掃 DataBar
    // ============================================================
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var lastZxingCppAttemptTime = 0L

    private var successPlayer: MediaPlayer? = null
    private var failPlayer: MediaPlayer? = null

    private var isScanning = false
    private var isProcessingFrame = false
    private var lastScanTime = 0L

    private lateinit var previewView: PreviewView
    private lateinit var etQty: EditText
    private lateinit var etManualCode: EditText

    private lateinit var btnScan: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnReport: MaterialButton
    private lateinit var btnRefreshDb: MaterialButton

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

    private val barcodeMap = mutableMapOf<String, ItemInfo>()

    private val scannedRecords =
        linkedMapOf<String, ScanRecord>()

    private val inventoryRecords =
        linkedMapOf<String, ScanRecord>()

    private lateinit var adapter: ScanAdapter

    private val cacheFileName = "product_cache.json"

    @Volatile
    private var cloudLoading = false

    private val client = OkHttpClient.Builder()
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

        // ZXing-C++ 最短補掃間隔
        private const val ZXING_CPP_INTERVAL_MS = 250L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_scan)

        bindViews()
        setupRecycler()
        setupButtons()
        setupSounds()

        // ========================================================
        // Engine 1：Google ML Kit
        //
        // 僅啟用一維條碼
        // 不啟用 QR / Data Matrix / PDF417 等二維碼
        // ========================================================

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
            BarcodeScanning.getClient(oneDimensionalOptions)

        loadProductCache()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA
            )
        }

        fetchCloudData()
        updateModeUi()
    }

    private fun bindViews() {

        previewView =
            findViewById(R.id.previewView)

        etQty =
            findViewById(R.id.etQty)

        etManualCode =
            findViewById(R.id.etManualCode)

        btnScan =
            findViewById(R.id.btnScan)

        btnClear =
            findViewById(R.id.btnClear)

        btnReport =
            findViewById(R.id.btnReport)

        btnRefreshDb =
            findViewById(R.id.btnRefreshDb)

        tvStatus =
            findViewById(R.id.tvStatus)

        tvLastItem =
            findViewById(R.id.tvLastItem)

        tvDbStatus =
            findViewById(R.id.tvDbStatus)

        tvSummary =
            findViewById(R.id.tvSummary)

        recyclerView =
            findViewById(R.id.recyclerView)

        layoutScannerSection =
            findViewById(R.id.layoutScannerSection)

        layoutReportSection =
            findViewById(R.id.layoutReportSection)

        layoutInventorySection =
            findViewById(R.id.layoutInventorySection)

        tvReportSummary =
            findViewById(R.id.tvReportSummary)

        tvReportContent =
            findViewById(R.id.tvReportContent)

        btnBackToScan =
            findViewById(R.id.btnBackToScan)

        inventoryContent =
            findViewById(R.id.inventoryContent)

        tvInventorySummary =
            findViewById(R.id.tvInventorySummary)

        etInventoryManualCode =
            findViewById(R.id.etInventoryManualCode)

        inventoryPreviewView =
            findViewById(R.id.inventoryPreviewView)
    }

    private fun setupRecycler() {

        adapter =
            ScanAdapter(emptyList())

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

        btnScan.setOnClickListener {
            triggerScan()
        }

        btnClear.setOnClickListener {
            clearRecords()
        }

        btnReport.setOnClickListener {
            showReportPage()
        }

        btnRefreshDb.setOnClickListener {
            fetchCloudData(true)
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
                        android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
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

        etInventoryManualCode.setOnEditorActionListener {
                _,
                actionId,
                event ->

            val enter =
                actionId ==
                        android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
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

        findViewById<MaterialButton>(
            R.id.btnInventoryScan
        ).setOnClickListener {
            triggerScan()
        }

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

    private fun setupSounds() {

        try {

            successPlayer =
                MediaPlayer.create(
                    this,
                    R.raw.success
                )

            failPlayer =
                MediaPlayer.create(
                    this,
                    R.raw.fail
                )

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    private fun play(
        player: MediaPlayer?,
        recreate: Int
    ): MediaPlayer? {

        return try {

            if (player != null) {

                if (player.isPlaying) {
                    player.pause()
                }

                player.seekTo(0)
                player.start()

                player

            } else {

                MediaPlayer
                    .create(this, recreate)
                    ?.also {
                        it.start()
                    }
            }

        } catch (e: Exception) {

            e.printStackTrace()

            try {

                player?.release()

                MediaPlayer
                    .create(this, recreate)
                    ?.also {
                        it.start()
                    }

            } catch (_: Exception) {

                null
            }
        }
    }

    private fun playSuccessSound() {
        successPlayer =
            play(
                successPlayer,
                R.raw.success
            )
    }

    private fun playFailSound() {
        failPlayer =
            play(
                failPlayer,
                R.raw.fail
            )
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {

        return when (keyCode) {

            KeyEvent.KEYCODE_VOLUME_UP -> {

                camera?.let { cam ->

                    if (cam.cameraInfo.hasFlashUnit()) {

                        val isTorchOn =
                            cam.cameraInfo.torchState.value ==
                                    TorchState.ON

                        cam.cameraControl.enableTorch(
                            !isTorchOn
                        )
                    }
                }

                true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {

                triggerScan()

                true
            }

            else -> {
                super.onKeyDown(
                    keyCode,
                    event
                )
            }
        }
    }

    private fun showPointMode() {

        mode = Mode.POINT

        stopScanning()

        layoutInventorySection.visibility =
            View.GONE

        layoutReportSection.visibility =
            View.GONE

        layoutScannerSection.visibility =
            View.VISIBLE

        tvStatus.text =
            "請按「開始掃描」"

        startCamera()

        updateModeUi()
    }

    private fun showInventoryMode() {

        mode = Mode.INVENTORY

        stopScanning()

        layoutReportSection.visibility =
            View.GONE

        layoutScannerSection.visibility =
            View.GONE

        layoutInventorySection.visibility =
            View.VISIBLE

        tvInventorySummary.text =
            "已掃描 ${inventoryRecords.size} 項"

        renderInventoryList()

        startInventoryCamera()

        updateModeUi()
    }

    private fun updateModeUi() {

        val point =
            findViewById<MaterialButton>(
                R.id.btnModePoint
            )

        val inv =
            findViewById<MaterialButton>(
                R.id.btnModeInventory
            )

        if (mode == Mode.POINT) {

            point.alpha = 1f
            inv.alpha = 0.55f

        } else {

            point.alpha = 0.55f
            inv.alpha = 1f
        }
    }

    private fun startInventoryCamera() {

        // 同一個 CameraX 預覽，
        // 只把 Preview surface 換到盤點頁 PreviewView。

        if (!hasCameraPermission()) {
            return
        }

        val future =
            ProcessCameraProvider
                .getInstance(this)

        future.addListener({

            try {

                val provider =
                    future.get()

                cameraProvider =
                    provider

                val preview =
                    Preview.Builder()
                        .build()

                preview.setSurfaceProvider(
                    inventoryPreviewView.surfaceProvider
                )

                imageAnalysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                provider.unbindAll()

                camera =
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )

                tvStatus.text =
                    "盤點模式：請按「掃描」"

            } catch (e: Exception) {

                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleManualCode() {

        val code =
            etManualCode
                .text
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

        val qty =
            etQty
                .text
                .toString()
                .toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1

        if (item != null) {

            playSuccessSound()

            recordItem(
                item.customCode,
                item.intlCode,
                item.name,
                qty
            )

            tvStatus.text =
                "✓ 手動輸入成功　+$qty"

            etQty.setText("1")

            etManualCode.text.clear()

            etManualCode.requestFocus()

        } else {

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

    private fun handleInventoryManualCode() {

        val code =
            etInventoryManualCode
                .text
                .toString()
                .trim()

        if (code.isEmpty()) {
            return
        }

        val item =
            findItem(code)

        if (item != null) {

            addInventoryItem(item)

            playSuccessSound()

            etInventoryManualCode.text.clear()

            etInventoryManualCode.requestFocus()

        } else {

            playFailSound()

            Toast.makeText(
                this,
                "查無此編碼：$code",
                Toast.LENGTH_SHORT
            ).show()

            etInventoryManualCode.selectAll()
        }
    }

        private fun triggerScan() {

        if (
            layoutReportSection.visibility == View.VISIBLE ||
            isScanning ||
            imageAnalysis == null
        ) {
            return
        }

        val now =
            System.currentTimeMillis()

        if (now - lastScanTime < 800) {
            return
        }

        lastScanTime = now

        isScanning = true
        isProcessingFrame = false

        // 每次開始新的掃描，
        // 讓 ZXing-C++ 可以立即進行第一次補掃。
        lastZxingCppAttemptTime = 0L

        if (mode == Mode.INVENTORY) {

            tvStatus.text =
                "盤點：正在尋找一維條碼..."

        } else {

            tvStatus.text =
                "正在尋找條碼..."
        }

        // 使用獨立掃描執行緒，
        // 避免 ZXing-C++ 阻塞 UI thread。
        imageAnalysis?.setAnalyzer(scanExecutor) {
            processImage(it)
        }
    }

    private fun processImage(proxy: ImageProxy) {

        if (!isScanning || isProcessingFrame) {

            proxy.close()

            return
        }

        val image =
            proxy.image

        if (image == null) {

            proxy.close()

            return
        }

        isProcessingFrame = true

        val inputImage =
            InputImage.fromMediaImage(
                image,
                proxy.imageInfo.rotationDegrees
            )

        barcodeScanner
            ?.process(inputImage)

            // ====================================================
            // Engine 1：Google ML Kit
            // ====================================================
            .addOnSuccessListener(scanExecutor) { bars ->

                if (!isScanning) {
                    return@addOnSuccessListener
                }

                val mlKitCode =
                    bars
                        .asSequence()
                        .mapNotNull {
                            it.rawValue?.trim()
                        }
                        .firstOrNull {
                            it.isNotEmpty()
                        }

                // ------------------------------------------------
                // ML Kit 找到條碼
                // ------------------------------------------------

                if (!mlKitCode.isNullOrBlank()) {

                    stopScanning()

                    runOnUiThread {

                        onBarcodeDetected(
                            mlKitCode
                        )
                    }

                    return@addOnSuccessListener
                }

                // ------------------------------------------------
                // ML Kit 沒找到
                //
                // 交給 ZXing-C++ 補掃 DataBar
                // ------------------------------------------------

                tryZxingCpp(proxy)
            }

            // ====================================================
            // ML Kit 發生錯誤
            // 仍然嘗試 ZXing-C++
            // ====================================================
            .addOnFailureListener(scanExecutor) {

                if (isScanning) {

                    tryZxingCpp(proxy)
                }
            }

            // ====================================================
            // 本 frame 的 ML Kit 工作結束
            // ====================================================
            .addOnCompleteListener(scanExecutor) {

                isProcessingFrame = false

                // CameraX ImageProxy 必須確實 close。
                proxy.close()
            }

            ?: run {

                isProcessingFrame = false

                proxy.close()
            }
    }

    private fun stopScanning() {

        isScanning = false

        isProcessingFrame = false

        imageAnalysis?.clearAnalyzer()
    }

    // ============================================================
    // Engine 2：ZXing-C++
    //
    // 主要用途：
    // GS1 DataBar
    // DataBar Omnidirectional
    // DataBar Stacked
    // DataBar Limited
    // DataBar Expanded
    // 等 ML Kit 不支援的一維 DataBar
    // ============================================================

    private fun tryZxingCpp(
        proxy: ImageProxy
    ) {

        if (!isScanning) {
            return
        }

        val now =
            System.currentTimeMillis()

        // 不讓 ZXing-C++ 每一幀都執行。
        //
        // CameraX 可能每秒產生很多 frame，
        // 250ms 至少讓補掃保持一定間隔。
        if (
            now - lastZxingCppAttemptTime <
            ZXING_CPP_INTERVAL_MS
        ) {
            return
        }

        lastZxingCppAttemptTime = now

        try {

            val code =
                ZxingCppDecoder.decode(proxy)

            if (
                !code.isNullOrBlank() &&
                isScanning
            ) {

                stopScanning()

                runOnUiThread {

                    onBarcodeDetected(
                        code.trim()
                    )
                }
            }

        } catch (_: Exception) {

            // 本次 DataBar 補掃失敗。
            //
            // 不顯示錯誤，
            // 下一個 CameraX frame 會繼續嘗試。
        }
    }

    private fun onBarcodeDetected(
        code: String
    ) {

        val item =
            findItem(code)

        // ========================================================
        // 盤點模式
        // ========================================================

        if (mode == Mode.INVENTORY) {

            if (item != null) {

                addInventoryItem(item)

                playSuccessSound()

            } else {

                playFailSound()

                Toast.makeText(
                    this,
                    "查無此條碼：$code",
                    Toast.LENGTH_SHORT
                ).show()
            }

            return
        }

        // ========================================================
        // 一般點貨模式
        // ========================================================

        val qty =
            etQty
                .text
                .toString()
                .toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1

        if (item != null) {

            playSuccessSound()

            recordItem(
                item.customCode,
                item.intlCode,
                item.name,
                qty
            )

            tvStatus.text =
                "✓ 掃描成功　+$qty"

            etQty.setText("1")

        } else {

            playFailSound()

            tvStatus.text =
                "資料庫沒有此條碼"

            showCreateItemDialog(code)
        }
    }

    private fun findItem(
        raw: String
    ): ItemInfo? {

        val code =
            raw.trim()

        // --------------------------------------------------------
        // 1. 完全相同
        // --------------------------------------------------------

        barcodeMap[code]?.let {
            return it
        }

        // --------------------------------------------------------
        // 2. 純數字條碼
        // --------------------------------------------------------

        if (code.matches(Regex("\\d+"))) {

            // 12 位條碼前面補 0
            if (code.length == 12) {

                barcodeMap["0$code"]?.let {
                    return it
                }
            }

            // 去掉前導 0
            barcodeMap[
                code
                    .trimStart('0')
                    .ifEmpty { "0" }
            ]?.let {
                return it
            }

            // 自編碼不足 6 位時補 0
            barcodeMap[
                code.padStart(
                    6,
                    '0'
                )
            ]?.let {
                return it
            }
        }

        return null
    }

    // ============================================================
    // 一般點貨紀錄
    // ============================================================

    private fun recordItem(
        c: String,
        i: String,
        n: String,
        q: Int
    ) {

        val t =
            SimpleDateFormat(
                "yyyy/MM/dd HH:mm:ss",
                Locale.TAIWAN
            ).format(Date())

        scannedRecords[c]
            ?.apply {

                qty += q

                lastTime = t
            }
            ?: run {

                scannedRecords[c] =
                    ScanRecord(
                        c,
                        i,
                        n,
                        q,
                        t
                    )
            }

        val r =
            scannedRecords[c]!!

        tvLastItem.text =
            "最後點貨\n" +
            "${r.name}\n" +
            "自編碼：${r.customCode}　" +
            "累計：${r.qty} 件"

        refreshList()
    }

    private fun refreshList() {

        val list =
            scannedRecords
                .values
                .sortedBy {
                    it.customCode
                }

        adapter.updateData(list)

        tvSummary.text =
            "品項 ${list.size} 種　｜　" +
            "總數 ${list.sumOf { it.qty }} 件"
    }

    private fun clearRecords() {

        AlertDialog.Builder(this)

            .setTitle("清空點貨紀錄")

            .setMessage(
                "確定要清空目前所有點貨紀錄嗎？"
            )

            .setPositiveButton(
                "確定清空"
            ) { _, _ ->

                scannedRecords.clear()

                refreshList()

                tvLastItem.text =
                    "最後點貨\n尚未掃描"

                tvStatus.text =
                    "請按「開始掃描」"

                etQty.setText("1")

                etManualCode.text.clear()
            }

            .setNegativeButton(
                "取消",
                null
            )

            .show()
    }

    // ============================================================
    // 盤點模式
    // ============================================================

    private fun addInventoryItem(
        item: ItemInfo
    ) {

        val key =
            item.customCode

        if (!inventoryRecords.containsKey(key)) {

            val now =
                SimpleDateFormat(
                    "yyyy/MM/dd HH:mm:ss",
                    Locale.TAIWAN
                ).format(Date())

            inventoryRecords[key] =
                ScanRecord(
                    key,
                    item.intlCode,
                    item.name,
                    0,
                    now
                )

            tvStatus.text =
                "✓ 已加入：${item.name}"

        } else {

            tvStatus.text =
                "✓ 已存在：${item.name}"
        }

        renderInventoryList()
    }

    private fun renderInventoryList() {

        inventoryContent.removeAllViews()

        inventoryRecords
            .values
            .forEachIndexed { index, record ->

                val row =
                    LinearLayout(this).apply {

                        orientation =
                            LinearLayout.HORIZONTAL

                        gravity =
                            Gravity.CENTER_VERTICAL

                        setPadding(
                            dp(5),
                            dp(6),
                            dp(5),
                            dp(6)
                        )

                        setBackgroundColor(
                            Color.WHITE
                        )
                    }

                // ------------------------------------------------
                // 刪除按鈕
                // ------------------------------------------------

                val delete =
                    MaterialButton(this).apply {

                        text = "刪除"

                        textSize = 11f

                        minWidth = 0
                        minimumWidth = 0

                        minHeight = 0
                        minimumHeight = 0

                        setPadding(
                            dp(5),
                            0,
                            dp(5),
                            0
                        )

                        setTextColor(
                            Color.WHITE
                        )

                        backgroundTintList =
                            android.content.res
                                .ColorStateList
                                .valueOf(
                                    Color.rgb(
                                        220,
                                        80,
                                        70
                                    )
                                )

                        cornerRadius =
                            dp(7)
                    }

                delete.setOnClickListener {

                    inventoryRecords.remove(
                        record.customCode
                    )

                    renderInventoryList()
                }

                // ------------------------------------------------
                // 自編碼
                // ------------------------------------------------

                val code =
                    TextView(this).apply {

                        text =
                            record.customCode

                        textSize = 16f

                        typeface =
                            Typeface.DEFAULT_BOLD

                        setTextColor(
                            Color.rgb(
                                7,
                                89,
                                133
                            )
                        )

                        gravity =
                            Gravity.CENTER_VERTICAL
                    }

                // ------------------------------------------------
                // 商品名稱
                // ------------------------------------------------

                val name =
                    TextView(this).apply {

                        text =
                            record.name

                        textSize = 15f

                        setTextColor(
                            Color.rgb(
                                30,
                                41,
                                59
                            )
                        )

                        gravity =
                            Gravity.CENTER_VERTICAL

                        maxLines = 3
                    }

                // ------------------------------------------------
                // 數量
                // ------------------------------------------------

                val qty =
                    EditText(this).apply {

                        // 數量為 0 時畫面保持空白，
                        // 避免使用者看到預設的 0。
                        setText(
                            if (record.qty == 0) {
                                ""
                            } else {
                                record.qty.toString()
                            }
                        )

                        textSize = 17f

                        setTextColor(
                            Color.rgb(
                                3,
                                105,
                                161
                            )
                        )

                        gravity =
                            Gravity.CENTER

                        inputType =
                            android.text.InputType
                                .TYPE_CLASS_NUMBER

                        background =
                            ContextCompat.getDrawable(
                                this@ScanActivity,
                                R.drawable.bg_qty
                            )

                        setSelectAllOnFocus(
                            true
                        )
                    }

                qty.setOnFocusChangeListener {
                        _,
                        hasFocus ->

                    if (!hasFocus) {

                        record.qty =
                            qty.text
                                .toString()
                                .toIntOrNull()
                                ?.coerceAtLeast(0)
                                ?: 0

                        updateInventorySummary()
                    }
                }

                qty.setOnEditorActionListener {
                        _,
                        _,
                        _ ->

                    record.qty =
                        qty.text
                            .toString()
                            .toIntOrNull()
                            ?.coerceAtLeast(0)
                            ?: 0

                    updateInventorySummary()

                    false
                }

                // ------------------------------------------------
                // 加入 Row
                // ------------------------------------------------

                row.addView(
                    delete,
                    LinearLayout.LayoutParams(
                        dp(58),
                        dp(42)
                    )
                )

                row.addView(
                    code,
                    LinearLayout.LayoutParams(
                        dp(74),
                        -2
                    )
                )

                row.addView(
                    name,
                    LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f
                    ).apply {

                        marginStart =
                            dp(4)

                        marginEnd =
                            dp(4)
                    }
                )

                row.addView(
                    qty,
                    LinearLayout.LayoutParams(
                        dp(68),
                        dp(42)
                    )
                )

                inventoryContent.addView(row)

                if (
                    index <
                    inventoryRecords.size - 1
                ) {

                    val divider =
                        View(this).apply {

                            setBackgroundColor(
                                Color.rgb(
                                    226,
                                    232,
                                    240
                                )
                            )
                        }

                    inventoryContent.addView(
                        divider,
                        LinearLayout.LayoutParams(
                            -1,
                            dp(1)
                        )
                    )
                }
            }

        updateInventorySummary()
    }

    private fun updateInventorySummary() {

        val total =
            inventoryRecords
                .values
                .sumOf {
                    it.qty
                }

        tvInventorySummary.text =
            "已掃描 ${inventoryRecords.size} 項　｜　" +
            "目前數量 $total"
    }

    private fun clearInventory() {

        if (inventoryRecords.isEmpty()) {
            return
        }

        AlertDialog.Builder(this)

            .setTitle("清空盤點")

            .setMessage(
                "確定清空目前盤點清單？"
            )

            .setPositiveButton(
                "確定"
            ) { _, _ ->

                inventoryRecords.clear()

                renderInventoryList()
            }

            .setNegativeButton(
                "取消",
                null
            )

            .show()
    }

    // ============================================================
    // 產生盤點 PDF
    // ============================================================

    private fun generateInventoryPdf() {

        if (inventoryRecords.isEmpty()) {

            Toast.makeText(
                this,
              "目前沒有盤點品項",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        // 產生前先讀取畫面上最新的數量。
        inventoryContent
            .childrenForEditTexts()
            .forEach { (key, value) ->

                inventoryRecords[key]?.qty =
                    value
            }

        // LinkedHashMap：
        // 保留使用者掃描／加入的原始順序。
        val records =
            inventoryRecords.values.toList()

        try {

            val pdf =
                PdfDocument()

            val pageWidth =
                595f

            val pageHeight =
                842f

            val margin =
                28f

            val gap =
                14f

            val columnWidth =
                (
                    pageWidth -
                        margin * 2 -
                        gap
                ) / 2f

            val rowHeight =
                40f

            val titleHeight =
                52f

            val usableHeight =
                pageHeight -
                    margin * 2 -
                    titleHeight

            val rowsPerColumn =
                maxOf(
                    1,
                    (
                        usableHeight /
                            rowHeight
                    ).toInt()
                )

            val rowsPerPage =
                rowsPerColumn * 2

            val pageCount =
                (
                    records.size +
                        rowsPerPage -
                        1
                ) / rowsPerPage

            val titlePaint =
                Paint(
                    Paint.ANTI_ALIAS_FLAG
                ).apply {

                    color =
                        Color.BLACK

                    textSize =
                        20f

                    typeface =
                        Typeface.DEFAULT_BOLD
                }

            val headerPaint =
                Paint(
                    Paint.ANTI_ALIAS_FLAG
                ).apply {

                    color =
                        Color.DKGRAY

                    textSize =
                        10f

                    typeface =
                        Typeface.DEFAULT_BOLD
                }

            val textPaint =
                Paint(
                    Paint.ANTI_ALIAS_FLAG
                ).apply {

                    color =
                        Color.BLACK

                    textSize =
                        10f
                }

            val codePaint =
                Paint(
                    Paint.ANTI_ALIAS_FLAG
                ).apply {

                    color =
                        Color.BLACK

                    textSize =
                        9f

                    typeface =
                        Typeface.DEFAULT_BOLD
                }

            val linePaint =
                Paint(
                    Paint.ANTI_ALIAS_FLAG
                ).apply {

                    color =
                        Color.LTGRAY

                    strokeWidth =
                        0.8f
                }

            for (
                pageIndex
                in 0 until pageCount
            ) {

                val page =
                    pdf.startPage(
                        PdfDocument.PageInfo
                            .Builder(
                                pageWidth.toInt(),
                                pageHeight.toInt(),
                                pageIndex + 1
                            )
                            .create()
                    )

                val canvas =
                    page.canvas

                canvas.drawText(
                    "盤點表",
                    margin,
                    margin + 20f,
                    titlePaint
                )

                val start =
                    pageIndex *
                        rowsPerPage

                val end =
                    minOf(
                        records.size,
                        start + rowsPerPage
                    )

                val pageRecords =
                    records.subList(
                        start,
                        end
                    )

                for (column in 0..1) {

                    val colStart =
                        column *
                            rowsPerColumn

                    val colEnd =
                        minOf(
                            pageRecords.size,
                            colStart +
                                rowsPerColumn
                        )

                    if (colStart >= colEnd) {
                        continue
                    }

                    val x =
                        margin +
                            column *
                            (
                                columnWidth +
                                    gap
                            )

                    canvas.drawText(
                        "自編碼",
                        x,
                        margin +
                            titleHeight -
                            10f,
                        headerPaint
                    )

                    canvas.drawText(
                        "品名",
                        x + 118f,
                        margin +
                            titleHeight -
                            10f,
                        headerPaint
                    )

                    canvas.drawText(
                        "數量",
                        x +
                            columnWidth -
                            30f,
                        margin +
                            titleHeight -
                            10f,
                        headerPaint
                    )

                    pageRecords
                        .subList(
                            colStart,
                            colEnd
                        )
                        .forEachIndexed {
                                rowIndex,
                                record ->

                            val y =
                                margin +
                                    titleHeight +
                                    rowIndex *
                                    rowHeight

                            drawCode128(
                                canvas,
                                record.customCode,
                                x,
                                y + 4f,
                                80f,
                                18f
                            )

                            canvas.drawText(
                                record.customCode,
                                x,
                                y + 29f,
                                codePaint
                            )

                            drawWrappedText(
                                canvas,
                                record.name,
                                x + 118f,
                                y + 17f,
                                columnWidth - 158f,
                                textPaint,
                                2
                            )

                            canvas.drawText(
                                record.qty.toString(),
                                x +
                                    columnWidth -
                                    26f,
                                y + 20f,
                                textPaint
                            )

                            canvas.drawLine(
                                x,
                                y +
                                    rowHeight -
                                    5f,
                                x +
                                    columnWidth,
                                y +
                                    rowHeight -
                                    5f,
                                linePaint
                            )
                        }
                }

                pdf.finishPage(page)
            }

            val file =
                File(
                    cacheDir,
                    "盤點表_${
                        SimpleDateFormat(
                            "yyyyMMdd_HHmm",
                            Locale.TAIWAN
                        ).format(Date())
                    }.pdf"
                )

            pdf.writeTo(
                file.outputStream()
            )

            pdf.close()

            sharePdf(file)

        } catch (e: Exception) {

            e.printStackTrace()

            Toast.makeText(
                this,
                "PDF 產生失敗：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "分享盤點 PDF"))
    }

    // =========================
    // 報表頁面
    // =========================

    private fun showReportPage() {
        layoutReportSection.visibility = View.VISIBLE
        layoutInventorySection.visibility = View.GONE
        layoutPointSection.visibility = View.GONE
        layoutManualCode.visibility = View.GONE

        tvStatus.text = "盤點報表"
        tvCount.text = "共 ${inventoryRecords.size} 筆"

        reportContainer.removeAllViews()

        inventoryRecords.forEachIndexed { index, record ->
            addReportRow(index + 1, record)
        }
    }

    private fun addReportRow(index: Int, record: InventoryRecord) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
        }

        val number = TextView(this).apply {
            text = index.toString()
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }

        val name = TextView(this).apply {
            text = record.name.ifBlank { "未命名品項" }
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val qty = TextView(this).apply {
            text = record.qty.toString()
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1976D2"))
        }

        row.addView(
            number,
            LinearLayout.LayoutParams(
                dp(40),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        row.addView(
            name,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            qty,
            LinearLayout.LayoutParams(
                dp(60),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        reportContainer.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(6))
            }
        )
    }

    // =========================
    // 商品快取
    // =========================

    private fun loadProductCache() {
        barcodeMap.clear()

        try {
            val prefs = getSharedPreferences(
                "product_cache",
                MODE_PRIVATE
            )

            val json = prefs.getString("items", null)

            if (!json.isNullOrBlank()) {
                val array = JSONArray(json)

                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue

                    val barcode = obj.optString("barcode").trim()
                    val name = obj.optString("name").trim()

                    if (barcode.isNotEmpty()) {
                        barcodeMap[barcode] = ItemInfo(
                            barcode = barcode,
                            name = name
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveProductCache() {
        try {
            val prefs = getSharedPreferences(
                "product_cache",
                MODE_PRIVATE
            )

            val array = JSONArray()

            barcodeMap.values.forEach { item ->
                val obj = JSONObject().apply {
                    put("barcode", item.barcode)
                    put("name", item.name)
                }

                array.put(obj)
            }

            prefs.edit()
                .putString("items", array.toString())
                .apply()

        } catch (_: Exception) {
        }
    }

    // =========================
    // Google Sheet / GAS
    // =========================

    private fun queryProductFromGas(
        barcode: String,
        callback: (ItemInfo?) -> Unit
    ) {
        val code = barcode.trim()

        if (code.isEmpty()) {
            callback(null)
            return
        }

        val url = GAS_URL

        if (url.isBlank()) {
            callback(null)
            return
        }

        val json = JSONObject().apply {
            put("action", "query")
            put("barcode", code)
        }

        val body = json.toString()
            .toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(
            object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {
                    runOnUiThread {
                        callback(null)
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {
                    response.use {
                        try {
                            val text = it.body?.string().orEmpty()

                            if (text.isBlank()) {
                                runOnUiThread {
                                    callback(null)
                                }
                                return
                            }

                            val obj = JSONObject(text)

                            val success = obj.optBoolean(
                                "success",
                                false
                            )

                            if (!success) {
                                runOnUiThread {
                                    callback(null)
                                }
                                return
                            }

                            val item = ItemInfo(
                                barcode = obj.optString(
                                    "barcode",
                                    code
                                ),
                                name = obj.optString(
                                    "name",
                                    ""
                                )
                            )

                            runOnUiThread {
                                callback(item)
                            }

                        } catch (_: Exception) {
                            runOnUiThread {
                                callback(null)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun uploadUnknownItem(
        barcode: String,
        name: String,
        callback: (Boolean) -> Unit
    ) {
        val url = GAS_URL

        if (url.isBlank()) {
            callback(false)
            return
        }

        val json = JSONObject().apply {
            put("action", "add")
            put("barcode", barcode)
            put("name", name)
        }

        val body = json.toString()
            .toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(
            object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {
                    runOnUiThread {
                        callback(false)
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {
                    response.use {
                        val success = try {
                            val text = it.body?.string().orEmpty()

                            if (text.isBlank()) {
                                false
                            } else {
                                JSONObject(text)
                                    .optBoolean(
                                        "success",
                                        false
                                    )
                            }
                        } catch (_: Exception) {
                            false
                        }

                        runOnUiThread {
                            callback(success)
                        }
                    }
                }
            }
        )
    }

    // =========================
    // 建立新商品
    // =========================

    private fun showCreateItemDialog(
        barcode: String
    ) {
        val input = EditText(this).apply {
            hint = "請輸入品名"
            setSingleLine(true)
            setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8)
            )
        }

        val container = FrameLayout(this).apply {
            setPadding(
                dp(24),
                dp(8),
                dp(24),
                0
            )

            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle("建立新商品")
            .setMessage("條碼：$barcode")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("建立", null)
            .create()
            .also { dialog ->

                dialog.setOnShowListener {
                    dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                    ).setOnClickListener {

                        val name = input.text
                            .toString()
                            .trim()

                        if (name.isEmpty()) {
                            input.error = "請輸入品名"
                            return@setOnClickListener
                        }

                        val item = ItemInfo(
                            barcode = barcode,
                            name = name
                        )

                        barcodeMap[barcode] = item
                        saveProductCache()

                        if (mode == Mode.POINT) {
                            uploadUnknownItem(
                                barcode,
                                name
                            ) {
                                runOnUiThread {
                                    addPointRecord(item)
                                    dialog.dismiss()
                                }
                            }
                        } else {
                            addInventoryRecord(item)
                            dialog.dismiss()
                        }
                    }
                }

                dialog.show()
            }
    }

    // =========================
    // CameraX
    // =========================

    private fun startCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
            {
                try {
                    cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(
                                previewView.surfaceProvider
                            )
                        }

                    imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                    val cameraSelector =
                        CameraSelector.DEFAULT_BACK_CAMERA

                    cameraProvider?.unbindAll()

                    cameraProvider?.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                } catch (e: Exception) {
                    tvStatus.text =
                        "相機啟動失敗：${e.message}"
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun startInventoryCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
            {
                try {
                    cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(
                                previewView.surfaceProvider
                            )
                        }

                    imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                    val cameraSelector =
                        CameraSelector.DEFAULT_BACK_CAMERA

                    cameraProvider?.unbindAll()

                    cameraProvider?.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                    tvStatus.text =
                        "盤點：請將一維條碼對準掃描框"

                } catch (e: Exception) {
                    tvStatus.text =
                        "相機啟動失敗：${e.message}"
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider = null
    }

    // =========================
    // 相機權限
    // =========================

    private fun checkCameraPermission() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            } else {
                tvStatus.text = "需要相機權限才能掃描條碼"
            }
        }
    }

    // =========================
    // UI 工具
    // =========================

    private fun dp(value: Int): Int {
        return (
            value *
                resources.displayMetrics.density
            ).roundToInt()
    }

    private fun childrenForEditTexts(
        view: ViewGroup
    ): List<EditText> {
        val result = mutableListOf<EditText>()

        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)

            when (child) {
                is EditText -> {
                    result.add(child)
                }

                is ViewGroup -> {
                    result.addAll(
                        childrenForEditTexts(child)
                    )
                }
            }
        }

        return result
    }

    // =========================
    // 返回鍵
    // =========================

    override fun onBackPressed() {
        when {
            layoutReportSection.visibility == View.VISIBLE -> {
                layoutReportSection.visibility = View.GONE

                when (mode) {
                    Mode.POINT -> {
                        layoutPointSection.visibility = View.VISIBLE
                        layoutInventorySection.visibility = View.GONE
                    }

                    Mode.INVENTORY -> {
                        layoutInventorySection.visibility = View.VISIBLE
                        layoutPointSection.visibility = View.GONE
                    }
                }

                startCamera()
            }

            layoutManualCode.visibility == View.VISIBLE -> {
                layoutManualCode.visibility = View.GONE
                startCamera()
            }

            else -> {
                super.onBackPressed()
            }
        }
    }

    // =========================
    // 生命週期
    // =========================

    override fun onPause() {
        super.onPause()

        if (isScanning) {
            stopScanning()
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            layoutReportSection.visibility != View.VISIBLE &&
            !isFinishing
        ) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            }
        }
    }

    override fun onDestroy() {
        stopScanning()
        stopCamera()

        try {
            barcodeScanner?.close()
        } catch (_: Exception) {
        }

        try {
            successPlayer?.release()
        } catch (_: Exception) {
        }

        try {
            failPlayer?.release()
        } catch (_: Exception) {
        }

        try {
            scanExecutor.shutdownNow()
        } catch (_: Exception) {
        }

        client.dispatcher.cancelAll()

        super.onDestroy()
    }
}    
