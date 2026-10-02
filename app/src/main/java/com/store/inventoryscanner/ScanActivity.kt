package com.store.inventoryscanner

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.view.KeyEvent
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

    // ===== 相機 =====
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isTorchOn = false
    private var isScanning = false
    private var lastScanTime = 0L

    // ===== UI =====
    private lateinit var previewView: PreviewView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnReport: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvLastItem: TextView
    private lateinit var tvDbStatus: TextView
    private lateinit var tvSummary: TextView
    private lateinit var recyclerView: RecyclerView

    // ===== 資料 =====
    private val barcodeMap = mutableMapOf<String, ItemInfo>()
    private val scannedRecords = linkedMapOf<String, ScanRecord>()
    private lateinit var adapter: ScanAdapter

    // ===== 音效 =====
    private var soundPool: SoundPool? = null
    private var successSoundId = 0
    private var failSoundId = 0

    // ===== 網路 =====
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 替換為你的 Google Apps Script Web App URL
    private val GAS_WEB_APP_URL = "https://script.google.com/macros/s/AKfycbxD84499eLT9602gFVbCsKHrFAUgGYvOayHH9uNRc79HYD4sAQZYCuOA-j2KypNnLx1/exec"

    companion object {
        private const val REQUEST_CAMERA = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        bindViews()
        initSounds()
        setupRecycler()
        setupButtons()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
        }

        fetchCloudData()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        btnScan = findViewById(R.id.btnScan)
        btnClear = findViewById(R.id.btnClear)
        btnReport = findViewById(R.id.btnReport)
        tvStatus = findViewById(R.id.tvStatus)
        tvLastItem = findViewById(R.id.tvLastItem)
        tvDbStatus = findViewById(R.id.tvDbStatus)
        tvSummary = findViewById(R.id.tvSummary)
        recyclerView = findViewById(R.id.recyclerView)
    }

    private fun setupRecycler() {
        adapter = ScanAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        btnScan.setOnClickListener { triggerScan() }
        btnClear.setOnClickListener { clearRecords() }
        btnReport.setOnClickListener { showReportDialog() }
    }

    // ==================== 音量鍵攔截 ====================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                toggleTorch()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                triggerScan()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ==================== 音效 ====================
    private fun initSounds() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()

        try {
            successSoundId = soundPool!!.load(this, R.raw.success, 1)
            failSoundId = soundPool!!.load(this, R.raw.fail, 1)
        } catch (e: Exception) {
            // raw 資源不存在時忽略
        }
    }

    private fun playSuccessSound() {
        if (successSoundId != 0) soundPool?.play(successSoundId, 1f, 1f, 1, 0, 1f)
    }

    private fun playFailSound() {
        if (failSoundId != 0) soundPool?.play(failSoundId, 1f, 1f, 1, 0, 1f)
    }

    // ==================== 手電筒 ====================
    private fun toggleTorch() {
        camera?.let {
            isTorchOn = !isTorchOn
            it.cameraControl.enableTorch(isTorchOn)
            tvStatus.text = if (isTorchOn) getString(R.string.torch_on) else getString(R.string.torch_off)
        }
    }

    // ==================== 觸發掃描 ====================
    private fun triggerScan() {
        if (isScanning) return
        val now = System.currentTimeMillis()
        if (now - lastScanTime < 800) return

        isScanning = true
        lastScanTime = now
        tvStatus.text = getString(R.string.scanning)

        imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            processImage(imageProxy)
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isScanning = false
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage, imageProxy.imageInfo.rotationDegrees
        )
        val scanner = BarcodeScanning.getClient()

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val code = barcodes[0].rawValue
                    if (!code.isNullOrBlank()) {
                        onBarcodeDetected(code)
                    } else {
                        onScanFailed()
                    }
                } else {
                    onScanFailed()
                }
            }
            .addOnFailureListener { onScanFailed() }
            .addOnCompleteListener {
                imageProxy.close()
                imageAnalysis?.clearAnalyzer()
                isScanning = false
            }
    }

    // ==================== 掃到條碼後 ====================
    private fun onBarcodeDetected(code: String) {
        val item = barcodeMap[code]
        if (item != null) {
            playSuccessSound()
            recordItem(item.customCode, item.intlCode, item.name, 1)
            tvStatus.text = getString(R.string.scan_success) + " (+1)"
        } else {
            playFailSound()
            tvStatus.text = "資料庫無此條碼"
            showCreateItemDialog(code)
        }
    }

    private fun onScanFailed() {
        playFailSound()
        tvStatus.text = getString(R.string.scan_fail)
    }

    // ==================== 點貨紀錄 ====================
    private fun recordItem(cCode: String, iCode: String, name: String, qty: Int) {
        val nowTime = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(Date())

        if (scannedRecords.containsKey(cCode)) {
            scannedRecords[cCode]!!.qty += qty
            scannedRecords[cCode]!!.lastTime = nowTime
        } else {
            scannedRecords[cCode] = ScanRecord(cCode, iCode, name, qty, nowTime)
        }

        val rec = scannedRecords[cCode]!!
        tvLastItem.text = "最後點貨：【${rec.customCode}】${rec.name}  (累計 ${rec.qty} 件)"
        refreshList()
    }

    private fun refreshList() {
        val list = scannedRecords.values.sortedBy { it.customCode }
        adapter.updateData(list)
        val totalQty = list.sumOf { it.qty }
        tvSummary.text = "累計品項：${list.size} 種 | 總點貨量：$totalQty 件"
    }

    private fun clearRecords() {
        AlertDialog.Builder(this)
            .setTitle("確認清空")
            .setMessage("確定要清空目前的點貨紀錄嗎？")
            .setPositiveButton("清空") { _, _ ->
                scannedRecords.clear()
                refreshList()
                tvLastItem.text = getString(R.string.last_item_none)
                tvStatus.text = getString(R.string.scan_hint)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 手動建檔 ====================
    private fun showCreateItemDialog(rawBarcode: String) {
        val inputCustom = android.widget.EditText(this).apply {
            hint = "自編碼（例：000123）"
            setText(if (rawBarcode.matches(Regex("\\d+"))) rawBarcode.padStart(6, '0') else rawBarcode)
        }
        val inputName = android.widget.EditText(this).apply {
            hint = "商品名稱"
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(android.widget.TextView(this@ScanActivity).apply {
                text = "原始條碼：$rawBarcode"
                setPadding(0, 0, 0, 16)
            })
            addView(inputCustom)
            addView(inputName)
        }

        AlertDialog.Builder(this)
            .setTitle("資料庫無此條碼，請建檔")
            .setView(layout)
            .setPositiveButton("確認建檔並點貨") { _, _ ->
                var customCode = inputCustom.text.toString().trim()
                val name = inputName.text.toString().trim()
                if (customCode.isEmpty() || name.isEmpty()) {
                    Toast.makeText(this, "請填寫自編碼與商品名稱", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (customCode.matches(Regex("\\d+"))) {
                    customCode = customCode.padStart(6, '0')
                }
                postNewItemToCloud(rawBarcode, customCode, name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 雲端同步 ====================
    private fun fetchCloudData() {
        tvDbStatus.text = "讀取中…"
        Thread {
            try {
                val request = Request.Builder().url(GAS_WEB_APP_URL).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val body = response.body?.string() ?: throw Exception("空回應")
                    val jsonArray = JSONArray(body)

                    barcodeMap.clear()
                    var count = 0
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.optString("商品名稱").trim()
                        if (name.isEmpty()) continue

                        var customCode = obj.optString("自編碼").trim()
                        if (customCode.endsWith(".0")) customCode = customCode.dropLast(2)
                        if (customCode.matches(Regex("\\d+"))) {
                            customCode = customCode.padStart(6, '0')
                        }
                        var intlCode = obj.optString("國際條碼").trim()
                        if (intlCode == "nan") intlCode = ""

                        if (customCode.isEmpty() || customCode == "nan") continue

                        val info = ItemInfo(customCode, intlCode, name)
                        barcodeMap[customCode] = info
                        if (intlCode.isNotEmpty()) barcodeMap[intlCode] = info
                        barcodeMap[customCode.trimStart('0').ifEmpty { "0" }] = info
                        count++
                    }

                    runOnUiThread {
                        tvDbStatus.text = "✓ $count 筆"
                        tvStatus.text = getString(R.string.cloud_success)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvDbStatus.text = "載入失敗"
                    tvStatus.text = getString(R.string.cloud_fail)
                }
            }
        }.start()
    }

    private fun postNewItemToCloud(intlCode: String, customCode: String, name: String) {
        tvStatus.text = "同步至雲端中…"
        Thread {
            try {
                val json = JSONObject().apply {
                    put("國際條碼", intlCode)
                    put("自編碼", customCode)
                    put("商品名稱", name)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(GAS_WEB_APP_URL)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { }

                val info = ItemInfo(customCode, intlCode, name)
                barcodeMap[intlCode] = info
                barcodeMap[customCode] = info

                runOnUiThread {
                    playSuccessSound()
                    recordItem(customCode, intlCode, name, 1)
                    tvStatus.text = "✓ 建檔並點貨成功"
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

    // ==================== 核對單 ====================
    private fun showReportDialog() {
        if (scannedRecords.isEmpty()) {
            Toast.makeText(this, "目前沒有點貨紀錄", Toast.LENGTH_SHORT).show()
            return
        }
        val list = scannedRecords.values.sortedBy { it.customCode }
        val sb = StringBuilder()
        sb.append("盤點點貨核對單\n")
        sb.append("列印時間：${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date())}\n")
        sb.append("排序：依自編碼\n\n")
        list.forEachIndexed { index, rec ->
            sb.append("${index + 1}. [${rec.customCode}] ${rec.name}  × ${rec.qty}\n")
        }
        sb.append("\n合計：${list.size} 種 / ${list.sumOf { it.qty }} 件")

        AlertDialog.Builder(this)
            .setTitle("點貨核對單")
            .setMessage(sb.toString())
            .setPositiveButton("關閉", null)
            .show()
    }

    // ==================== 相機權限 ====================
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相機權限才能掃描", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
        cameraProvider?.unbindAll()
    }
}
