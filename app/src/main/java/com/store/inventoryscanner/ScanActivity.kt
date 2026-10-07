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
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors
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
import java.util.concurrent.Executors
import zxingcpp.BarcodeReader

class ScanActivity : AppCompatActivity() {
    private enum class Mode { POINT, INVENTORY }

    private var mode = Mode.POINT
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var barcodeScanner: BarcodeScanner? = null
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var lastZxingCppAttemptTime = 0L
    private var successPlayer: MediaPlayer? = null
    private var failPlayer: MediaPlayer? = null
    private var isScanning = false
    private var isProcessingFrame = false
    private var lastScanTime = 0L
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var lastZxingCppAttemptTime = 0L
    
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
    private val scannedRecords = linkedMapOf<String, ScanRecord>()
    private val inventoryRecords = linkedMapOf<String, ScanRecord>()
    private lateinit var adapter: ScanAdapter
    private val cacheFileName = "product_cache.json"
    @Volatile private var cloudLoading = false

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
        private const val ZXING_CPP_INTERVAL_MS = 250L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        bindViews()
        setupRecycler()
        setupButtons()
        setupSounds()

        val oneDimensionalOptions = BarcodeScannerOptions.Builder()
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

        barcodeScanner = BarcodeScanning.getClient(oneDimensionalOptions)

// ZXing-C++ DataBar 補掃引擎
// 實際第一次 read() 時才會使用 native engine

        loadProductCache()

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA
        )

        fetchCloudData()
        updateModeUi()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        etQty = findViewById(R.id.etQty)
        etManualCode = findViewById(R.id.etManualCode)
        btnScan = findViewById(R.id.btnScan)
        btnClear = findViewById(R.id.btnClear)
        btnReport = findViewById(R.id.btnReport)
        btnRefreshDb = findViewById(R.id.btnRefreshDb)
        tvStatus = findViewById(R.id.tvStatus)
        tvLastItem = findViewById(R.id.tvLastItem)
        tvDbStatus = findViewById(R.id.tvDbStatus)
        tvSummary = findViewById(R.id.tvSummary)
        recyclerView = findViewById(R.id.recyclerView)
        layoutScannerSection = findViewById(R.id.layoutScannerSection)
        layoutReportSection = findViewById(R.id.layoutReportSection)
        layoutInventorySection = findViewById(R.id.layoutInventorySection)
        tvReportSummary = findViewById(R.id.tvReportSummary)
        tvReportContent = findViewById(R.id.tvReportContent)
        btnBackToScan = findViewById(R.id.btnBackToScan)
        inventoryContent = findViewById(R.id.inventoryContent)
        tvInventorySummary = findViewById(R.id.tvInventorySummary)
        etInventoryManualCode = findViewById(R.id.etInventoryManualCode)
        inventoryPreviewView = findViewById(R.id.inventoryPreviewView)
    }

    private fun setupRecycler() {
        adapter = ScanAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btnModePoint).setOnClickListener {
            if (mode != Mode.POINT) {
                mode = Mode.POINT
                showPointMode()
            }
        }
        findViewById<MaterialButton>(R.id.btnModeInventory).setOnClickListener {
            if (mode != Mode.INVENTORY) {
                mode = Mode.INVENTORY
                showInventoryMode()
            }
        }

        btnScan.setOnClickListener { triggerScan() }
        btnClear.setOnClickListener { clearRecords() }
        btnReport.setOnClickListener { showReportPage() }
        btnRefreshDb.setOnClickListener { fetchCloudData(true) }
        btnBackToScan.setOnClickListener { showPointMode() }

        etManualCode.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                handleManualCode()
                true
            } else false
        }

        etInventoryManualCode.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                handleInventoryManualCode()
                true
            } else false
        }

        findViewById<MaterialButton>(R.id.btnInventoryScan).setOnClickListener { triggerScan() }
        findViewById<MaterialButton>(R.id.btnInventoryGenerate).setOnClickListener { generateInventoryPdf() }
        findViewById<MaterialButton>(R.id.btnInventoryClear).setOnClickListener { clearInventory() }
    }

    private fun setupSounds() {
        try {
            successPlayer = MediaPlayer.create(this, R.raw.success)
            failPlayer = MediaPlayer.create(this, R.raw.fail)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun play(player: MediaPlayer?, recreate: Int): MediaPlayer? {
        return try {
            if (player != null) {
                if (player.isPlaying) player.pause()
                player.seekTo(0)
                player.start()
                player
            } else {
                MediaPlayer.create(this, recreate)?.also { it.start() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                player?.release()
                MediaPlayer.create(this, recreate)?.also { it.start() }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun playSuccessSound() { successPlayer = play(successPlayer, R.raw.success) }
    private fun playFailSound() { failPlayer = play(failPlayer, R.raw.fail) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    val isTorchOn = cam.cameraInfo.torchState.value == TorchState.ON
                    cam.cameraControl.enableTorch(!isTorchOn)
                }
            }
            true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            triggerScan()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun showPointMode() {
        mode = Mode.POINT
        stopScanning()
        layoutInventorySection.visibility = View.GONE
        layoutReportSection.visibility = View.GONE
        layoutScannerSection.visibility = View.VISIBLE
        tvStatus.text = "請按「開始掃描」"
        startCamera()
        updateModeUi()
    }

    private fun showInventoryMode() {
        mode = Mode.INVENTORY
        stopScanning()
        layoutReportSection.visibility = View.GONE
        layoutScannerSection.visibility = View.GONE
        layoutInventorySection.visibility = View.VISIBLE
        tvInventorySummary.text = "已掃描 ${inventoryRecords.size} 項"
        renderInventoryList()
        startInventoryCamera()
        updateModeUi()
    }

    private fun updateModeUi() {
        val point = findViewById<MaterialButton>(R.id.btnModePoint)
        val inv = findViewById<MaterialButton>(R.id.btnModeInventory)
        if (mode == Mode.POINT) {
            point.alpha = 1f
            inv.alpha = 0.55f
        } else {
            point.alpha = 0.55f
            inv.alpha = 1f
        }
    }

    private fun startInventoryCamera() {
        // 同一個 CameraX 預覽，只把 Preview surface 換到盤點頁的 PreviewView。
        if (!hasCameraPermission()) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(inventoryPreviewView.surfaceProvider)
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                tvStatus.text = "盤點模式：請按「掃描」"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleManualCode() {
        val code = etManualCode.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(this, "請輸入自編碼或條碼", Toast.LENGTH_SHORT).show()
            return
        }
        val item = findItem(code)
        val qty = etQty.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (item != null) {
            playSuccessSound()
            recordItem(item.customCode, item.intlCode, item.name, qty)
            tvStatus.text = "✓ 手動輸入成功　+$qty"
            etQty.setText("1")
            etManualCode.text.clear()
            etManualCode.requestFocus()
        } else {
            playFailSound()
            tvStatus.text = "資料庫沒有此編碼"
            Toast.makeText(this, "查無此編碼：$code", Toast.LENGTH_SHORT).show()
            etManualCode.selectAll()
        }
    }

    private fun handleInventoryManualCode() {
        val code = etInventoryManualCode.text.toString().trim()
        if (code.isEmpty()) return
        val item = findItem(code)
        if (item != null) {
            addInventoryItem(item)
            playSuccessSound()
            etInventoryManualCode.text.clear()
            etInventoryManualCode.requestFocus()
        } else {
            playFailSound()
            Toast.makeText(this, "查無此編碼：$code", Toast.LENGTH_SHORT).show()
            etInventoryManualCode.selectAll()
        }
    }

    private fun triggerScan() {
        if (layoutReportSection.visibility == View.VISIBLE || isScanning || imageAnalysis == null) return
        val now = System.currentTimeMillis()
        if (now - lastScanTime < 800) return
        lastScanTime = now
        isScanning = true
        isProcessingFrame = false
        if (mode == Mode.INVENTORY) tvStatus.text = "盤點：正在尋找一維條碼..."
        else tvStatus.text = "正在尋找條碼..."
        imageAnalysis?.setAnalyzer(scanExecutor) { processImage(it) }
    }     

    private fun processImage(proxy: ImageProxy) {

    if (!isScanning || isProcessingFrame) {
        proxy.close()
        return
    }

    val image = proxy.image

    if (image == null) {
        proxy.close()
        return
    }

    isProcessingFrame = true

    val inputImage = InputImage.fromMediaImage(
        image,
        proxy.imageInfo.rotationDegrees
    )

    barcodeScanner
        ?.process(inputImage)
        ?.addOnSuccessListener { bars ->

            if (!isScanning) {
                return@addOnSuccessListener
            }

            // =====================================================
            // Engine 1：Google ML Kit
            // =====================================================

            val mlKitCode = bars
                .asSequence()
                .mapNotNull { it.rawValue }
                .firstOrNull { it.isNotBlank() }

            if (!mlKitCode.isNullOrBlank()) {

                stopScanning()

                runOnUiThread {
                    onBarcodeDetected(mlKitCode)
                }

                return@addOnSuccessListener
            }

            // =====================================================
            // Engine 2：ZXing-C++
            //
            // ML Kit 沒找到 → DataBar 補掃
            // =====================================================

            tryZxingCpp(proxy)

        }
        ?.addOnFailureListener {

            // ML Kit 本身發生錯誤，也嘗試 DataBar 補掃
            if (isScanning) {
                tryZxingCpp(proxy)
            }
        }
        ?.addOnCompleteListener {

            isProcessingFrame = false

            // ML Kit 與 ZXing-C++ 都已經使用完影像
            proxy.close()
        }
        ?: run {

            // ML Kit 尚未初始化
            isProcessingFrame = false
            proxy.close()
        }
}

    private fun stopScanning() {
        isScanning = false
        isProcessingFrame = false
        imageAnalysis?.clearAnalyzer()
    }
    private fun tryZxingCpp(proxy: ImageProxy) {

           if (!isScanning) return

           val now = System.currentTimeMillis()

    // ZXing-C++ 不需要每一幀執行
           if (now - lastZxingCppAttemptTime < ZXING_CPP_INTERVAL_MS) {
           return
    }

           lastZxingCppAttemptTime = now

           val code = ZxingCppDecoder.decode(proxy)

           if (!code.isNullOrBlank() && isScanning) {

           stopScanning()

           runOnUiThread {
           onBarcodeDetected(code)
        }
    }
}
    private fun onBarcodeDetected(code: String) {
        val item = findItem(code)
        if (mode == Mode.INVENTORY) {
            if (item != null) {
                addInventoryItem(item)
                playSuccessSound()
            } else {
                playFailSound()
                Toast.makeText(this, "查無此條碼：$code", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val qty = etQty.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (item != null) {
            playSuccessSound()
            recordItem(item.customCode, item.intlCode, item.name, qty)
            tvStatus.text = "✓ 掃描成功　+$qty"
            etQty.setText("1")
        } else {
            playFailSound()
            tvStatus.text = "資料庫沒有此條碼"
            showCreateItemDialog(code)
        }
    }

    private fun findItem(raw: String): ItemInfo? {
        val code = raw.trim()
        barcodeMap[code]?.let { return it }
        if (code.matches(Regex("\\d+"))) {
            if (code.length == 12) barcodeMap["0$code"]?.let { return it }
            barcodeMap[code.trimStart('0').ifEmpty { "0" }]?.let { return it }
            barcodeMap[code.padStart(6, '0')]?.let { return it }
        }
        return null
    }

    private fun recordItem(c: String, i: String, n: String, q: Int) {
        val t = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(Date())
        scannedRecords[c]?.apply {
            qty += q
            lastTime = t
        } ?: run {
            scannedRecords[c] = ScanRecord(c, i, n, q, t)
        }
        val r = scannedRecords[c]!!
        tvLastItem.text = "最後點貨\n${r.name}\n自編碼：${r.customCode}　累計：${r.qty} 件"
        refreshList()
    }

    private fun refreshList() {
        val list = scannedRecords.values.sortedBy { it.customCode }
        adapter.updateData(list)
        tvSummary.text = "品項 ${list.size} 種　｜　總數 ${list.sumOf { it.qty }} 件"
    }

    private fun clearRecords() {
        AlertDialog.Builder(this)
            .setTitle("清空點貨紀錄")
            .setMessage("確定要清空目前所有點貨紀錄嗎？")
            .setPositiveButton("確定清空") { _, _ ->
                scannedRecords.clear()
                refreshList()
                tvLastItem.text = "最後點貨\n尚未掃描"
                tvStatus.text = "請按「開始掃描」"
                etQty.setText("1")
                etManualCode.text.clear()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addInventoryItem(item: ItemInfo) {
        val key = item.customCode
        if (!inventoryRecords.containsKey(key)) {
            val now = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(Date())
            inventoryRecords[key] = ScanRecord(key, item.intlCode, item.name, 0, now)
            tvStatus.text = "✓ 已加入：${item.name}"
        } else {
            tvStatus.text = "✓ 已存在：${item.name}"
        }
        renderInventoryList()
    }

    private fun renderInventoryList() {
        inventoryContent.removeAllViews()
        inventoryRecords.values.forEachIndexed { index, record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(5), dp(6), dp(5), dp(6))
                setBackgroundColor(Color.WHITE)
            }

            val delete = MaterialButton(this).apply {
                text = "刪除"
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(5), 0, dp(5), 0)
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(220, 80, 70))
                cornerRadius = dp(7)
            }
            delete.setOnClickListener {
                inventoryRecords.remove(record.customCode)
                renderInventoryList()
            }

            val code = TextView(this).apply {
                text = record.customCode
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(7, 89, 133))
                gravity = Gravity.CENTER_VERTICAL
            }

            val name = TextView(this).apply {
                text = record.name
                textSize = 15f
                setTextColor(Color.rgb(30, 41, 59))
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 3
            }

            val qty = EditText(this).apply {
                setText(record.qty.toString())
                textSize = 17f
                setTextColor(Color.rgb(3, 105, 161))
                gravity = Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                background = ContextCompat.getDrawable(this@ScanActivity, R.drawable.bg_qty)
                setSelectAllOnFocus(true)
            }
            qty.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    record.qty = qty.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    updateInventorySummary()
                }
            }
            qty.setOnEditorActionListener { _, _, _ ->
                record.qty = qty.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                updateInventorySummary()
                false
            }

            row.addView(delete, LinearLayout.LayoutParams(dp(58), dp(42)))
            row.addView(code, LinearLayout.LayoutParams(dp(74), -2))
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
            row.addView(qty, LinearLayout.LayoutParams(dp(68), dp(42)))

            inventoryContent.addView(row)
            if (index < inventoryRecords.size - 1) {
                val divider = View(this).apply { setBackgroundColor(Color.rgb(226, 232, 240)) }
                inventoryContent.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        updateInventorySummary()
    }

    private fun updateInventorySummary() {
        val total = inventoryRecords.values.sumOf { it.qty }
        tvInventorySummary.text = "已掃描 ${inventoryRecords.size} 項　｜　目前數量 $total"
    }

    private fun clearInventory() {
        if (inventoryRecords.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空盤點")
            .setMessage("確定清空目前盤點清單？")
            .setPositiveButton("確定") { _, _ ->
                inventoryRecords.clear()
                renderInventoryList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun generateInventoryPdf() {
        if (inventoryRecords.isEmpty()) {
            Toast.makeText(this, "目前沒有盤點品項", Toast.LENGTH_SHORT).show()
            return
        }

        // 生成前先讀取畫面上的最新數量，確保使用者最後修改的值。
        inventoryContent.childrenForEditTexts().forEach { (key, value) ->
            inventoryRecords[key]?.qty = value
        }

        val records = inventoryRecords.values.toList() // LinkedHashMap：保留生成當下順序
        try {
            val pdf = PdfDocument()
            val pageWidth = 595f
            val pageHeight = 842f
            val margin = 28f
            val gap = 14f
            val columnWidth = (pageWidth - margin * 2 - gap) / 2f
            val rowHeight = 40f
            val titleHeight = 52f
            val usableHeight = pageHeight - margin * 2 - titleHeight
            val rowsPerColumn = maxOf(1, (usableHeight / rowHeight).toInt())
            val rowsPerPage = rowsPerColumn * 2
            val pageCount = (records.size + rowsPerPage - 1) / rowsPerPage

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
            }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 10f
            }
            val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                strokeWidth = 0.8f
            }

            for (pageIndex in 0 until pageCount) {
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageIndex + 1).create()
                )
                val canvas = page.canvas
                canvas.drawText("盤點表", margin, margin + 20f, titlePaint)

                val start = pageIndex * rowsPerPage
                val end = minOf(records.size, start + rowsPerPage)
                val pageRecords = records.subList(start, end)

                for (column in 0..1) {
                    val colStart = column * rowsPerColumn
                    val colEnd = minOf(pageRecords.size, colStart + rowsPerColumn)
                    if (colStart >= colEnd) continue
                    val x = margin + column * (columnWidth + gap)

                    canvas.drawText("自編碼", x, margin + titleHeight - 10f, headerPaint)
                    canvas.drawText("品名", x + 118f, margin + titleHeight - 10f, headerPaint)
                    canvas.drawText("數量", x + columnWidth - 30f, margin + titleHeight - 10f, headerPaint)

                    pageRecords.subList(colStart, colEnd).forEachIndexed { rowIndex, record ->
                        val y = margin + titleHeight + rowIndex * rowHeight
                        drawCode128(canvas, record.customCode, x, y + 4f, 80f, 18f)
                        canvas.drawText(record.customCode, x, y + 29f, codePaint)
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
                            x + columnWidth - 26f,
                            y + 20f,
                            textPaint
                        )
                        canvas.drawLine(x, y + rowHeight - 5f, x + columnWidth, y + rowHeight - 5f, linePaint)
                    }
                }
                pdf.finishPage(page)
            }

            val file = File(cacheDir, "盤點表_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.TAIWAN).format(Date())}.pdf")
            pdf.writeTo(file.outputStream())
            pdf.close()

            sharePdf(file)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "PDF 產生失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "盤點表")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享盤點表"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "無法分享 PDF：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
        maxLines: Int
    ) {
        var line = ""
        var lineIndex = 0
        for (ch in text) {
            val candidate = line + ch
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line, x, y + lineIndex * 13f, paint)
                line = ch.toString()
                lineIndex++
                if (lineIndex >= maxLines) break
            } else {
                line = candidate
            }
        }
        if (lineIndex < maxLines) canvas.drawText(line, x, y + lineIndex * 13f, paint)
    }

    // Code 128-B：盤點表只產生一維 Barcode，不產生 QR / Data Matrix。
    private fun drawCode128(canvas: Canvas, value: String, x: Float, y: Float, width: Float, height: Float) {
        val digits = value.filter { it.isDigit() }.padStart(6, '0').takeLast(6)
        val patterns = arrayOf(
            "212222","222122","222221","121223","121322","131222","122213","122312","132212","221213",
            "221312","231212","112232","122132","122231","113222","123122","123221","223211","221132",
            "221231","213212","223112","312131","311222","321122","321221","312212","322112","322211",
            "212123","212321","232121","111323","131123","131321","112313","132113","132311","211313",
            "231113","231311","112133","112331","132131","113123","113321","133121","313121","211331",
            "231131","213113","213311","213131","311123","311321","331121","312113","312311","332111",
            "314111","221411","431111","111224","111422","121124","121421","141122","141221","112214",
            "112412","122114","122411","142112","142211","241211","221114","413111","241112","134111",
            "111242","121142","121241","114212","124112","124211","411212","421112","421211","212141",
            "214121","412121","111143","111341","131141","114113","114311","411113","411311","113141",
            "114131","311141","411131","211412","211214","211232","2331112"
        )
        val codes = mutableListOf<Int>()
        codes.add(104) // Start Code B
        digits.forEach { ch -> codes.add(ch.code - 32) }
        var checksum = 104
        for (i in 1 until codes.size) checksum += codes[i] * i
        codes.add(checksum % 103)
        codes.add(106) // Stop

        val modules = codes.sumOf { pattern ->
            patterns[pattern].sumOf { it.digitToInt() }
        }
        val module = width / modules.toFloat()
        var cursor = x
        codes.forEach { code ->
            val p = patterns[code]
            var black = true
            for (digit in p) {
                val w = digit.digitToInt() * module
                if (black) canvas.drawRect(cursor, y, cursor + w, y + height, Paint().apply { color = Color.BLACK }) 
                cursor += w
                black = !black
            }
        }
    }

    private fun LinearLayout.childrenForEditTexts(): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? LinearLayout ?: continue
            val codeView = row.getChildAt(1) as? TextView ?: continue
            val qtyView = row.getChildAt(3) as? EditText ?: continue
            val key = codeView.text.toString()
            val value = qtyView.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (key.isNotEmpty()) result.add(key to value)
        }
        return result
    }

    private fun showReportPage() {
        if (scannedRecords.isEmpty()) {
            Toast.makeText(this, "目前沒有點貨紀錄", Toast.LENGTH_SHORT).show()
            return
        }
        stopScanning()
        stopCamera()
        val list = scannedRecords.values.sortedBy { it.customCode }
        tvReportSummary.text = "本次盤點　${list.size} 種商品　｜　${list.sumOf { it.qty }} 件"
        tvReportContent.removeAllViews()
        addReportRow("自編碼", "品名", "數量", true)
        list.forEach { addReportRow(it.customCode, it.name, it.qty.toString(), false) }
        layoutScannerSection.visibility = View.GONE
        layoutInventorySection.visibility = View.GONE
        layoutReportSection.visibility = View.VISIBLE
    }

    private fun addReportRow(c: String, n: String, q: String, header: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            if (header) setBackgroundColor(Color.rgb(232, 240, 254))
        }
        val cv = TextView(this).apply {
            text = c
            textSize = if (header) 16f else 17f
            setTextColor(Color.DKGRAY)
            if (header) setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        val nv = TextView(this).apply {
            text = n
            textSize = if (header) 16f else 17f
            setTextColor(Color.DKGRAY)
            if (header) setTypeface(null, Typeface.BOLD)
            maxLines = 5
        }
        val qv = TextView(this).apply {
            text = q
            textSize = if (header) 16f else 18f
            setTextColor(Color.rgb(20, 80, 160))
            gravity = Gravity.CENTER
            if (header) setTypeface(null, Typeface.BOLD)
        }
        row.addView(cv, LinearLayout.LayoutParams(dp(82), -2))
        row.addView(nv, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(qv, LinearLayout.LayoutParams(dp(52), -2))
        tvReportContent.addView(row)
        if (!header) {
            val d = View(this).apply { setBackgroundColor(Color.rgb(225, 225, 225)) }
            tvReportContent.addView(d, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun cacheFile() = File(filesDir, cacheFileName)

    private fun loadProductCache() {
        Thread {
            try {
                val f = cacheFile()
                if (!f.exists()) return@Thread
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                val map = mutableMapOf<String, ItemInfo>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val c = o.optString("customCode").trim()
                    val intl = o.optString("intlCode").trim()
                    val n = o.optString("name").trim()
                    if (c.isEmpty() || n.isEmpty()) continue
                    val info = ItemInfo(c, intl, n)
                    map[c] = info
                    map[c.trimStart('0').ifEmpty { "0" }] = info
                    if (intl.isNotEmpty()) map[intl] = info
                }
                runOnUiThread {
                    barcodeMap.clear()
                    barcodeMap.putAll(map)
                    tvDbStatus.text = "本機資料　✓ ${map.values.associateBy { it.customCode }.size} 筆"
                    tvStatus.text = "已使用上次資料，可直接掃描"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun saveProductCache(items: Collection<ItemInfo>) {
        Thread {
            try {
                val arr = JSONArray()
                items.associateBy { it.customCode }.values.forEach {
                    arr.put(JSONObject().apply {
                        put("customCode", it.customCode)
                        put("intlCode", it.intlCode)
                        put("name", it.name)
                    })
                }
                val tmp = File(filesDir, "$cacheFileName.tmp")
                tmp.writeText(arr.toString(), Charsets.UTF_8)
                val f = cacheFile()
                if (f.exists()) f.delete()
                tmp.renameTo(f)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun fetchCloudData(force: Boolean = false) {
        if (cloudLoading) {
            if (force) Toast.makeText(this, "資料庫正在更新中，請稍候", Toast.LENGTH_SHORT).show()
            return
        }
        cloudLoading = true
        runOnUiThread {
            btnRefreshDb.isEnabled = false
            tvDbStatus.text = "雲端　更新中..."
            tvStatus.text = "正在讀取雲端商品資料..."
        }
        Thread {
            var success = false
            for (attempt in 1..MAX_RETRY) {
                try {
                    val req = Request.Builder().url(GAS_WEB_APP_URL).get()
                        .header("Cache-Control", "no-cache").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        val body = resp.body?.string()?.takeIf { it.isNotBlank() }
                            ?: throw Exception("GAS 回傳空白資料")
                        val arr = JSONArray(body)
                        val map = mutableMapOf<String, ItemInfo>()
                        val unique = linkedMapOf<String, ItemInfo>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val n = o.optString("商品名稱").trim()
                            if (n.isEmpty()) continue
                            var c = o.optString("自編碼").trim()
                            if (c.endsWith(".0")) c = c.dropLast(2)
                            if (c.matches(Regex("\\d+"))) c = c.padStart(6, '0')
                            var intl = o.optString("國際條碼").trim()
                            if (intl.equals("nan", true)) intl = ""
                            if (c.isEmpty() || c.equals("nan", true)) continue
                            val info = ItemInfo(c, intl, n)
                            unique[c] = info
                            map[c] = info
                            map[c.trimStart('0').ifEmpty { "0" }] = info
                            if (intl.isNotEmpty()) map[intl] = info
                        }
                        if (unique.isEmpty()) throw Exception("GAS 回傳 0 筆有效商品")
                        barcodeMap.clear()
                        barcodeMap.putAll(map)
                        saveProductCache(unique.values)
                        runOnUiThread {
                            tvDbStatus.text = "雲端　✓ ${unique.size} 筆"
                            tvStatus.text = "資料已更新，可開始掃描"
                            btnRefreshDb.isEnabled = true
                            Toast.makeText(
                                this@ScanActivity,
                                "資料庫更新完成：${unique.size} 筆",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        success = true
                    }
                    if (success) break
                } catch (e: Exception) {
                    if (attempt < MAX_RETRY) try {
                        Thread.sleep(if (attempt == 1) 2000L else 4000L)
                    } catch (_: InterruptedException) {}
                }
            }
            if (!success) runOnUiThread {
                btnRefreshDb.isEnabled = true
                if (barcodeMap.isNotEmpty()) {
                    val count = barcodeMap.values.associateBy { it.customCode }.size
                    tvDbStatus.text = "雲端更新失敗　｜　本機 $count 筆"
                    tvStatus.text = "⚠ 雲端更新失敗，繼續使用上次資料"
                    Toast.makeText(this, "雲端更新失敗，已使用上次資料", Toast.LENGTH_LONG).show()
                } else {
                    tvDbStatus.text = "雲端　載入失敗"
                    tvStatus.text = "❌ 尚無商品資料，請按「更新資料庫」重試"
                }
            }
            cloudLoading = false
            runOnUiThread { btnRefreshDb.isEnabled = true }
        }.start()
    }

    private fun postNewItemToCloud(intl: String, c: String, n: String, q: Int) {
        tvStatus.text = "正在同步雲端..."
        Thread {
            try {
                val json = JSONObject().apply {
                    put("國際條碼", intl)
                    put("自編碼", c)
                    put("商品名稱", n)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder().url(GAS_WEB_APP_URL).post(body).build()
                client.newCall(req).execute().use {
                    if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
                }
                val info = ItemInfo(c, intl, n)
                barcodeMap[c] = info
                barcodeMap[c.trimStart('0').ifEmpty { "0" }] = info
                if (intl.isNotEmpty()) barcodeMap[intl] = info
                saveProductCache(barcodeMap.values)
                runOnUiThread {
                    playSuccessSound()
                    recordItem(c, intl, n, q)
                    tvStatus.text = "✓ 建檔並完成點貨"
                    etQty.setText("1")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "同步雲端失敗，請檢查網路", Toast.LENGTH_LONG).show()
                    tvStatus.text = "同步失敗"
                }
            }
        }.start()
    }

    private fun showCreateItemDialog(raw: String) {
        val custom = EditText(this).apply {
            hint = "自編碼，例如：000123"
            setText(if (raw.matches(Regex("\\d+"))) raw.padStart(6, '0') else raw)
        }
        val name = EditText(this).apply { hint = "商品名稱" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(custom)
            addView(name)
        }
        AlertDialog.Builder(this)
            .setTitle("找不到商品")
            .setMessage("條碼：$raw\n請輸入自編碼與商品名稱")
            .setView(box)
            .setPositiveButton("建立並點貨") { _, _ ->
                var c = custom.text.toString().trim()
                val n = name.text.toString().trim()
                if (c.matches(Regex("\\d+"))) c = c.padStart(6, '0')
                val q = etQty.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                if (c.isEmpty() || n.isEmpty()) {
                    Toast.makeText(this, "自編碼與商品名稱不能空白", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                postNewItemToCloud(raw, c, n, q)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCamera() {
        if (!hasCameraPermission()) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val targetPreview = if (mode == Mode.INVENTORY) inventoryPreviewView else previewView
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(targetPreview.surfaceProvider)
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                tvStatus.text = if (mode == Mode.INVENTORY) "盤點模式：請按「掃描」" else "相機已準備完成，請按「開始掃描」"
            } catch (e: Exception) {
                e.printStackTrace()
                tvStatus.text = "相機啟動失敗"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try { imageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        camera = null
        imageAnalysis = null
        isScanning = false
        isProcessingFrame = false
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == REQUEST_CAMERA && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            layoutReportSection.visibility == View.VISIBLE -> showPointMode()
            layoutInventorySection.visibility == View.VISIBLE -> showPointMode()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
    stopScanning()
    stopCamera()

    try { barcodeScanner?.close() } catch (_: Exception) {}
    try { successPlayer?.release() } catch (_: Exception) {}
    try { failPlayer?.release() } catch (_: Exception) {}

    scanExecutor.shutdownNow()

    client.dispatcher.cancelAll()

    super.onDestroy()
}
