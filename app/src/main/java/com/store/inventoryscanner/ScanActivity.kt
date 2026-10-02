package com.store.inventoryscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ScanActivity : AppCompatActivity() {

    // =========================================================
    // 相機
    // =========================================================

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var barcodeScanner: BarcodeScanner? = null

    private var isTorchOn = false
    private var isScanning = false
    private var isProcessingFrame = false
    private var lastScanTime = 0L

    // =========================================================
    // 音效
    // =========================================================

    private var successPlayer: MediaPlayer? = null
    private var failPlayer: MediaPlayer? = null

    // =========================================================
    // 掃描頁 UI
    // =========================================================

    private lateinit var previewView: PreviewView
    private lateinit var etQty: EditText

    private lateinit var btnScan: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnReport: MaterialButton

    private lateinit var tvStatus: TextView
    private lateinit var tvLastItem: TextView
    private lateinit var tvDbStatus: TextView
    private lateinit var tvSummary: TextView

    private lateinit var recyclerView: RecyclerView

    // =========================================================
    // 核對頁 UI
    // =========================================================

    private lateinit var layoutScannerSection: View
    private lateinit var layoutReportSection: View

    private lateinit var tvReportSummary: TextView
    private lateinit var tvReportContent: LinearLayout
    private lateinit var btnBackToScan: MaterialButton

    // =========================================================
    // 商品資料
    // =========================================================

    private val barcodeMap =
        mutableMapOf<String, ItemInfo>()

    private val scannedRecords =
        linkedMapOf<String, ScanRecord>()

    private lateinit var adapter: ScanAdapter

    // =========================================================
    // Google Apps Script
    // =========================================================

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private val GAS_WEB_APP_URL =
        "https://script.google.com/macros/s/AKfycbxD84499eLT9602gFVbCsKHrFAUgGYvOayHH9uNRc79HYD4sAQZYCuOA-j2KypNnLx1/exec"

    companion object {
        private const val REQUEST_CAMERA = 1001
    }

    // =========================================================
    // Activity
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_scan)

        bindViews()
        setupRecycler()
        setupButtons()
        setupSounds()

        barcodeScanner =
            BarcodeScanning.getClient()

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
    }

    // =========================================================
    // UI 綁定
    // =========================================================

    private fun bindViews() {

        previewView =
            findViewById(R.id.previewView)

        etQty =
            findViewById(R.id.etQty)

        btnScan =
            findViewById(R.id.btnScan)

        btnClear =
            findViewById(R.id.btnClear)

        btnReport =
            findViewById(R.id.btnReport)

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

        tvReportSummary =
            findViewById(R.id.tvReportSummary)

        tvReportContent =
            findViewById(R.id.tvReportContent)

        btnBackToScan =
            findViewById(R.id.btnBackToScan)
    }

    // =========================================================
    // RecyclerView
    // =========================================================

    private fun setupRecycler() {

        adapter =
            ScanAdapter(emptyList())

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        recyclerView.adapter =
            adapter
    }

    // =========================================================
    // 按鈕
    // =========================================================

    private fun setupButtons() {

        btnScan.setOnClickListener {
            triggerScan()
        }

        btnClear.setOnClickListener {
            clearRecords()
        }

        btnReport.setOnClickListener {
            showReportPage()
        }

        btnBackToScan.setOnClickListener {
            showScannerPage()
        }
    }

    // =========================================================
    // 音效初始化
    // =========================================================

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

            successPlayer = null
            failPlayer = null
        }
    }

    // =========================================================
    // 成功音
    // =========================================================

    private fun playSuccessSound() {

        try {

            successPlayer?.let { player ->

                if (player.isPlaying) {
                    player.seekTo(0)
                } else {
                    player.seekTo(0)
                    player.start()
                }
            }

        } catch (e: Exception) {

            e.printStackTrace()

            // 如果播放器狀態異常，重新建立
            try {

                successPlayer?.release()

                successPlayer =
                    MediaPlayer.create(
                        this,
                        R.raw.success
                    )

                successPlayer?.start()

            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    // =========================================================
    // 失敗音
    // =========================================================

    private fun playFailSound() {

        try {

            failPlayer?.let { player ->

                if (player.isPlaying) {
                    player.seekTo(0)
                } else {
                    player.seekTo(0)
                    player.start()
                }
            }

        } catch (e: Exception) {

            e.printStackTrace()

            try {

                failPlayer?.release()

                failPlayer =
                    MediaPlayer.create(
                        this,
                        R.raw.fail
                    )

                failPlayer?.start()

            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    // =========================================================
    // 音量鍵
    // =========================================================

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {

        return when (keyCode) {

            KeyEvent.KEYCODE_VOLUME_UP -> {
                toggleTorch()
                true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                triggerScan()
                true
            }

            else ->
                super.onKeyDown(
                    keyCode,
                    event
                )
        }
    }

    // =========================================================
    // 手電筒
    // =========================================================

    private fun toggleTorch() {

        camera?.let {

            isTorchOn =
                !isTorchOn

            it.cameraControl
                .enableTorch(isTorchOn)

            tvStatus.text =
                if (isTorchOn) {
                    "手電筒已開"
                } else {
                    "手電筒已關"
                }
        }
    }

    // =========================================================
    // 開始掃描
    // =========================================================

    private fun triggerScan() {

        if (
            layoutReportSection.visibility ==
            View.VISIBLE
        ) {
            return
        }

        if (isScanning) {
            return
        }

        val now =
            System.currentTimeMillis()

        if (
            now - lastScanTime < 800
        ) {
            return
        }

        if (imageAnalysis == null) {

            tvStatus.text =
                "相機尚未準備完成"

            return
        }

        isScanning = true
        isProcessingFrame = false
        lastScanTime = now

        tvStatus.text =
            "正在尋找條碼..."

        imageAnalysis?.setAnalyzer(
            ContextCompat.getMainExecutor(this)
        ) { imageProxy ->

            processImage(imageProxy)
        }
    }

    // =========================================================
    // ML Kit 條碼辨識
    // =========================================================

    private fun processImage(
        imageProxy: ImageProxy
    ) {

        if (!isScanning) {
            imageProxy.close()
            return
        }

        if (isProcessingFrame) {
            imageProxy.close()
            return
        }

        val mediaImage =
            imageProxy.image

        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true

        val inputImage =
            InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

        barcodeScanner
            ?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->

                if (!isScanning) {
                    return@addOnSuccessListener
                }

                val barcode =
                    barcodes.firstOrNull()

                val code =
                    barcode?.rawValue

                if (!code.isNullOrBlank()) {

                    stopScanning()

                    onBarcodeDetected(code)
                }
            }
            ?.addOnFailureListener {
                // 失敗時繼續掃描下一張
            }
            ?.addOnCompleteListener {

                isProcessingFrame =
                    false

                imageProxy.close()
            }
            ?: run {

                isProcessingFrame =
                    false

                imageProxy.close()
            }
    }

    // =========================================================
    // 停止掃描
    // =========================================================

    private fun stopScanning() {

        isScanning = false
        isProcessingFrame = false

        imageAnalysis?.clearAnalyzer()
    }

    // =========================================================
    // 條碼處理
    // =========================================================

    private fun onBarcodeDetected(
        code: String
    ) {

        val item =
            findItem(code)

        val addQty =
            etQty.text
                .toString()
                .toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1

        if (item != null) {

            // ★ 成功音效
            playSuccessSound()

            recordItem(
                item.customCode,
                item.intlCode,
                item.name,
                addQty
            )

            tvStatus.text =
                "✓ 掃描成功　+${addQty}"

            etQty.setText("1")

        } else {

            // ★ 失敗音效
            playFailSound()

            tvStatus.text =
                "資料庫沒有此條碼"

            showCreateItemDialog(code)
        }
    }

    // =========================================================
    // 查找商品
    // =========================================================

    private fun findItem(
        rawCode: String
    ): ItemInfo? {

        val code =
            rawCode.trim()

        barcodeMap[code]?.let {
            return it
        }

        if (
            code.matches(
                Regex("\\d+")
            )
        ) {

            val normalized =
                code
                    .trimStart('0')
                    .ifEmpty {
                        "0"
                    }

            barcodeMap[normalized]?.let {
                return it
            }

            val padded =
                code.padStart(
                    6,
                    '0'
                )

            barcodeMap[padded]?.let {
                return it
            }
        }

        return null
    }

    // =========================================================
    // 記錄商品
    // =========================================================

    private fun recordItem(
        cCode: String,
        iCode: String,
        name: String,
        qty: Int
    ) {

        val nowTime =
            SimpleDateFormat(
                "yyyy/MM/dd HH:mm:ss",
                Locale.TAIWAN
            ).format(Date())

        if (
            scannedRecords.containsKey(cCode)
        ) {

            scannedRecords[cCode]!!.qty += qty

            scannedRecords[cCode]!!
                .lastTime = nowTime

        } else {

            scannedRecords[cCode] =
                ScanRecord(
                    cCode,
                    iCode,
                    name,
                    qty,
                    nowTime
                )
        }

        val rec =
            scannedRecords[cCode]!!

        tvLastItem.text =
            "最後點貨\n" +
            "${rec.name}\n" +
            "自編碼：${rec.customCode}　" +
            "累計：${rec.qty} 件"

        refreshList()
    }

    // =========================================================
    // 更新掃描清單
    // =========================================================

    private fun refreshList() {

        val list =
            scannedRecords.values
                .sortedBy {
                    it.customCode
                }

        adapter.updateData(list)

        val totalQty =
            list.sumOf {
                it.qty
            }

        tvSummary.text =
            "品項 ${list.size} 種　｜　總數 $totalQty 件"
    }

    // =========================================================
    // 清空紀錄
    // =========================================================

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
            }

            .setNegativeButton(
                "取消",
                null
            )

            .show()
    }

    // =========================================================
    // 新商品建檔
    // =========================================================

    private fun showCreateItemDialog(
        rawBarcode: String
    ) {

        val inputCustom =
            EditText(this).apply {

                hint =
                    "自編碼，例如：000123"

                setText(
                    if (
                        rawBarcode.matches(
                            Regex("\\d+")
                        )
                    ) {
                        rawBarcode.padStart(
                            6,
                            '0'
                        )
                    } else {
                        rawBarcode
                    }
                )
            }

        val inputName =
            EditText(this).apply {

                hint =
                    "商品名稱"
            }

        val layout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    48,
                    24,
                    48,
                    8
                )

                addView(
                    TextView(
                        this@ScanActivity
                    ).apply {

                        text =
                            "掃描條碼：$rawBarcode"

                        setPadding(
                            0,
                            0,
                            0,
                            20
                        )
                    }
                )

                addView(inputCustom)
                addView(inputName)
            }

        AlertDialog.Builder(this)

            .setTitle("新增商品")

            .setMessage(
                "雲端資料庫找不到此商品，請建立資料。"
            )

            .setView(layout)

            .setPositiveButton(
                "建立並點貨"
            ) { _, _ ->

                var customCode =
                    inputCustom.text
                        .toString()
                        .trim()

                val name =
                    inputName.text
                        .toString()
                        .trim()

                if (
                    customCode.isEmpty() ||
                    name.isEmpty()
                ) {

                    Toast.makeText(
                        this,
                        "請填寫自編碼與商品名稱",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setPositiveButton
                }

                if (
                    customCode.matches(
                        Regex("\\d+")
                    )
                ) {

                    customCode =
                        customCode.padStart(
                            6,
                            '0'
                        )
                }

                val addQty =
                    etQty.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceAtLeast(1)
                        ?: 1

                postNewItemToCloud(
                    rawBarcode,
                    customCode,
                    name,
                    addQty
                )
            }

            .setNegativeButton(
                "取消",
                null
            )

            .show()
    }

    // =========================================================
    // 下載雲端資料
    // =========================================================

    private fun fetchCloudData() {

        tvDbStatus.text =
            "雲端資料庫　讀取中..."

        Thread {

            try {

                val request =
                    Request.Builder()
                        .url(GAS_WEB_APP_URL)
                        .get()
                        .build()

                client
                    .newCall(request)
                    .execute()
                    .use { response ->

                        if (!response.isSuccessful) {
                            throw Exception(
                                "HTTP ${response.code}"
                            )
                        }

                        val body =
                            response.body
                                ?.string()
                                ?: throw Exception(
                                    "空回應"
                                )

                        val jsonArray =
                            JSONArray(body)

                        barcodeMap.clear()

                        var count = 0

                        for (
                            i in 0 until jsonArray.length()
                        ) {

                            val obj =
                                jsonArray
                                    .getJSONObject(i)

                            val name =
                                obj.optString(
                                    "商品名稱"
                                ).trim()

                            if (name.isEmpty()) {
                                continue
                            }

                            var customCode =
                                obj.optString(
                                    "自編碼"
                                ).trim()

                            if (
                                customCode.endsWith(
                                    ".0"
                                )
                            ) {

                                customCode =
                                    customCode
                                        .dropLast(2)
                            }

                            if (
                                customCode.matches(
                                    Regex("\\d+")
                                )
                            ) {

                                customCode =
                                    customCode.padStart(
                                        6,
                                        '0'
                                    )
                            }

                            var intlCode =
                                obj.optString(
                                    "國際條碼"
                                ).trim()

                            if (
                                intlCode.equals(
                                    "nan",
                                    ignoreCase = true
                                )
                            ) {
                                intlCode = ""
                            }

                            if (
                                customCode.isEmpty() ||
                                customCode.equals(
                                    "nan",
                                    ignoreCase = true
                                )
                            ) {
                                continue
                            }

                            val info =
                                ItemInfo(
                                    customCode,
                                    intlCode,
                                    name
                                )

                            // 完整自編碼
                            barcodeMap[
                                customCode
                            ] = info

                            // 國際條碼
                            if (
                                intlCode.isNotEmpty()
                            ) {

                                barcodeMap[
                                    intlCode
                                ] = info
                            }

                            // 去掉前導 0
                            val normalizedCustomCode =
                                customCode
                                    .trimStart('0')
                                    .ifEmpty {
                                        "0"
                                    }

                            barcodeMap[
                                normalizedCustomCode
                            ] = info

                            count++
                        }

                        runOnUiThread {

                            tvDbStatus.text =
                                "雲端資料庫　✓ $count 筆"

                            tvStatus.text =
                                "資料已載入，請開始掃描"
                        }
                    }

            } catch (e: Exception) {

                e.printStackTrace()

                runOnUiThread {

                    tvDbStatus.text =
                        "雲端資料庫　載入失敗"

                    tvStatus.text =
                        "雲端連線失敗"
                }
            }

        }.start()
    }

    // =========================================================
    // 新商品同步雲端
    // =========================================================

    private fun postNewItemToCloud(
        intlCode: String,
        customCode: String,
        name: String,
        addQty: Int
    ) {

        tvStatus.text =
            "正在同步雲端..."

        Thread {

            try {

                val json =
                    JSONObject().apply {

                        put(
                            "國際條碼",
                            intlCode
                        )

                        put(
                            "自編碼",
                            customCode
                        )

                        put(
                            "商品名稱",
                            name
                        )
                    }

                val body =
                    json.toString()
                        .toRequestBody(
                            "application/json; charset=utf-8"
                                .toMediaType()
                        )

                val request =
                    Request.Builder()
                        .url(GAS_WEB_APP_URL)
                        .post(body)
                        .build()

                client
                    .newCall(request)
                    .execute()
                    .use { response ->

                        if (!response.isSuccessful) {
                            throw Exception(
                                "HTTP ${response.code}"
                            )
                        }
                    }

                val info =
                    ItemInfo(
                        customCode,
                        intlCode,
                        name
                    )

                barcodeMap[
                    customCode
                ] = info

                if (
                    intlCode.isNotEmpty()
                ) {

                    barcodeMap[
                        intlCode
                    ] = info
                }

                val normalizedCustomCode =
                    customCode
                        .trimStart('0')
                        .ifEmpty {
                            "0"
                        }

                barcodeMap[
                    normalizedCustomCode
                ] = info

                runOnUiThread {

                    playSuccessSound()

                    recordItem(
                        customCode,
                        intlCode,
                        name,
                        addQty
                    )

                    tvStatus.text =
                        "✓ 建檔並完成點貨"

                    etQty.setText("1")
                }

            } catch (e: Exception) {

                e.printStackTrace()

                runOnUiThread {

                    Toast.makeText(
                        this,
                        "同步雲端失敗，請檢查網路",
                        Toast.LENGTH_LONG
                    ).show()

                    tvStatus.text =
                        "同步失敗"
                }
            }

        }.start()
    }

    // =========================================================
    // 核對頁
    // =========================================================

    private fun showReportPage() {

        if (scannedRecords.isEmpty()) {

            Toast.makeText(
                this,
                "目前沒有點貨紀錄",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        stopScanning()
        stopCamera()

        val list =
            scannedRecords.values
                .sortedBy {
                    it.customCode
                }

        val totalQty =
            list.sumOf {
                it.qty
            }

        tvReportSummary.text =
            "本次盤點　${list.size} 種商品　｜　$totalQty 件"

        tvReportContent.removeAllViews()

        addReportRow(
            customCode = "自編碼",
            name = "品名",
            qty = "數量",
            isHeader = true
        )

        list.forEach { rec ->

            addReportRow(
                customCode = rec.customCode,
                name = rec.name,
                qty = rec.qty.toString(),
                isHeader = false
            )
        }

        layoutScannerSection.visibility =
            View.GONE

        layoutReportSection.visibility =
            View.VISIBLE
    }

    // =========================================================
    // 核對清單列
    // =========================================================

    private fun addReportRow(
        customCode: String,
        name: String,
        qty: String,
        isHeader: Boolean
    ) {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8)
                )

                if (isHeader) {

                    setBackgroundColor(
                        Color.rgb(
                            232,
                            240,
                            254
                        )
                    )
                }
            }

        val codeView =
            TextView(this).apply {

                text =
                    customCode

                textSize =
                    if (isHeader) 16f else 17f

                setTextColor(
                    Color.rgb(
                        40,
                        40,
                        40
                    )
                )

                if (isHeader) {

                    setTypeface(
                        null,
                        Typeface.BOLD
                    )
                }

                gravity =
                    Gravity.CENTER_VERTICAL

                maxLines = 1

                ellipsize =
                    android.text.TextUtils.TruncateAt.END
            }

        row.addView(
            codeView,
            LinearLayout.LayoutParams(
                dp(82),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val nameView =
            TextView(this).apply {

                text =
                    name

                textSize =
                    if (isHeader) 16f else 17f

                setTextColor(
                    Color.rgb(
                        40,
                        40,
                        40
                    )
                )

                if (isHeader) {

                    setTypeface(
                        null,
                        Typeface.BOLD
                    )
                }

                gravity =
                    Gravity.CENTER_VERTICAL

                maxLines = 5

                breakStrategy =
                    android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            }

        row.addView(
            nameView,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val qtyView =
            TextView(this).apply {

                text =
                    qty

                textSize =
                    if (isHeader) 16f else 18f

                setTextColor(
                    Color.rgb(
                        20,
                        80,
                        160
                    )
                )

                if (isHeader) {

                    setTypeface(
                        null,
                        Typeface.BOLD
                    )
                }

                gravity =
                    Gravity.CENTER
            }

        row.addView(
            qtyView,
            LinearLayout.LayoutParams(
                dp(52),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        tvReportContent.addView(row)

        if (!isHeader) {

            val divider =
                View(this).apply {

                    setBackgroundColor(
                        Color.rgb(
                            225,
                            225,
                            225
                        )
                    )
                }

            tvReportContent.addView(
                divider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
            )
        }
    }

    // =========================================================
    // dp
    // =========================================================

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    // =========================================================
    // 回掃描頁
    // =========================================================

    private fun showScannerPage() {

        layoutReportSection.visibility =
            View.GONE

        layoutScannerSection.visibility =
            View.VISIBLE

        tvStatus.text =
            "請按「開始掃描」"

        startCamera()
    }

    // =========================================================
    // 停止相機
    // =========================================================

    private fun stopCamera() {

        try {
            imageAnalysis?.clearAnalyzer()
        } catch (_: Exception) {
        }

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        camera = null
        imageAnalysis = null

        isScanning = false
        isProcessingFrame = false
        isTorchOn = false
    }

    // =========================================================
    // 相機權限
    // =========================================================

    private fun hasCameraPermission(): Boolean {

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
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

        if (
            requestCode == REQUEST_CAMERA
        ) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED
            ) {

                startCamera()

            } else {

                tvStatus.text =
                    "需要相機權限才能掃描條碼"

                Toast.makeText(
                    this,
                    "請允許相機權限",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // =========================================================
    // CameraX
    // =========================================================

    private fun startCamera() {

        if (!hasCameraPermission()) {
            return
        }

        val cameraProviderFuture =
            ProcessCameraProvider
                .getInstance(this)

        cameraProviderFuture.addListener({

            try {

                val provider =
                    cameraProviderFuture.get()

                cameraProvider =
                    provider

                val preview =
                    Preview.Builder()
                        .build()

                // ★ 修正 CameraX surfaceProvider 編譯問題
                preview.setSurfaceProvider(
                    previewView.surfaceProvider
                )

                imageAnalysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                val cameraSelector =
                    CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()

                camera =
                    provider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                tvStatus.text =
                    "相機已準備完成，請按「開始掃描」"

            } catch (e: Exception) {

                e.printStackTrace()

                tvStatus.text =
                    "相機啟動失敗"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // =========================================================
    // 返回鍵
    // =========================================================

    @Suppress("DEPRECATION")
    override fun onBackPressed() {

        if (
            layoutReportSection.visibility ==
            View.VISIBLE
        ) {

            showScannerPage()

        } else {

            super.onBackPressed()
        }
    }

    // =========================================================
    // 銷毀
    // =========================================================

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

        successPlayer = null
        failPlayer = null

        client.dispatcher.cancelAll()

        super.onDestroy()
    }
}
