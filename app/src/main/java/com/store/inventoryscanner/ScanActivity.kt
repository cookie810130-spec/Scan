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
import androidx.camera.core.*
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
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ScanActivity : AppCompatActivity() {
    private var cameraProvider: ProcessCameraProvider?=null
    private var camera: Camera?=null
    private var imageAnalysis: ImageAnalysis?=null
    private var barcodeScanner: BarcodeScanner?=null
    private var successPlayer: MediaPlayer?=null
    private var failPlayer: MediaPlayer?=null
    private var isScanning=false
    private var isProcessingFrame=false
    private var lastScanTime=0L

    private lateinit var previewView:PreviewView
    private lateinit var etQty:EditText
    private lateinit var btnScan:MaterialButton
    private lateinit var btnClear:MaterialButton
    private lateinit var btnReport:MaterialButton
    private lateinit var btnRefreshDb:MaterialButton
    private lateinit var tvStatus:TextView
    private lateinit var tvLastItem:TextView
    private lateinit var tvDbStatus:TextView
    private lateinit var tvSummary:TextView
    private lateinit var recyclerView:RecyclerView
    private lateinit var layoutScannerSection:View
    private lateinit var layoutReportSection:View
    private lateinit var tvReportSummary:TextView
    private lateinit var tvReportContent:LinearLayout
    private lateinit var btnBackToScan:MaterialButton

    private val barcodeMap=mutableMapOf<String,ItemInfo>()
    private val scannedRecords=linkedMapOf<String,ScanRecord>()
    private lateinit var adapter:ScanAdapter
    private val cacheFileName="product_cache.json"
    @Volatile private var cloudLoading=false

    private val client=OkHttpClient.Builder()
        .connectTimeout(30,TimeUnit.SECONDS)
        .readTimeout(60,TimeUnit.SECONDS)
        .writeTimeout(30,TimeUnit.SECONDS)
        .callTimeout(90,TimeUnit.SECONDS)
        .build()

    private val GAS_WEB_APP_URL="https://script.google.com/macros/s/AKfycbxD84499eLT9602gFVbCsKHrFAUgGYvOayHH9uNRc79HYD4sAQZYCuOA-j2KypNnLx1/exec"

    companion object { private const val REQUEST_CAMERA=1001; private const val MAX_RETRY=3 }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        bindViews(); setupRecycler(); setupButtons(); setupSounds()
        barcodeScanner=BarcodeScanning.getClient()
        loadProductCache()
        if(hasCameraPermission()) startCamera() else ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),REQUEST_CAMERA)
        fetchCloudData()
    }

    private fun bindViews(){
        previewView=findViewById(R.id.previewView); etQty=findViewById(R.id.etQty)
        btnScan=findViewById(R.id.btnScan); btnClear=findViewById(R.id.btnClear)
        btnReport=findViewById(R.id.btnReport); btnRefreshDb=findViewById(R.id.btnRefreshDb)
        tvStatus=findViewById(R.id.tvStatus); tvLastItem=findViewById(R.id.tvLastItem)
        tvDbStatus=findViewById(R.id.tvDbStatus); tvSummary=findViewById(R.id.tvSummary)
        recyclerView=findViewById(R.id.recyclerView)
        layoutScannerSection=findViewById(R.id.layoutScannerSection)
        layoutReportSection=findViewById(R.id.layoutReportSection)
        tvReportSummary=findViewById(R.id.tvReportSummary)
        tvReportContent=findViewById(R.id.tvReportContent)
        btnBackToScan=findViewById(R.id.btnBackToScan)
    }

    private fun setupRecycler(){
        adapter=ScanAdapter(emptyList())
        recyclerView.layoutManager=LinearLayoutManager(this)
        recyclerView.adapter=adapter
    }

    private fun setupButtons(){
        btnScan.setOnClickListener{triggerScan()}
        btnClear.setOnClickListener{clearRecords()}
        btnReport.setOnClickListener{showReportPage()}
        btnRefreshDb.setOnClickListener{fetchCloudData(true)}
        btnBackToScan.setOnClickListener{showScannerPage()}
    }

    private fun setupSounds(){
        try{
            successPlayer=MediaPlayer.create(this,R.raw.success)
            failPlayer=MediaPlayer.create(this,R.raw.fail)
        }catch(e:Exception){e.printStackTrace()}
    }
    private fun play(player:MediaPlayer?, recreate:Int):MediaPlayer?{
        return try{
            if(player!=null){if(player.isPlaying)player.pause();player.seekTo(0);player.start();player}
            else MediaPlayer.create(this,recreate)?.also{it.start()}
        }catch(e:Exception){
            e.printStackTrace()
            try{player?.release();MediaPlayer.create(this,recreate)?.also{it.start()}}catch(_:Exception){null}
        }
    }
    private fun playSuccessSound(){successPlayer=play(successPlayer,R.raw.success)}
    private fun playFailSound(){failPlayer=play(failPlayer,R.raw.fail)}

    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean=when(keyCode){
        KeyEvent.KEYCODE_VOLUME_UP->{camera?.cameraControl?.enableTorch(true);true}
        KeyEvent.KEYCODE_VOLUME_DOWN->{triggerScan();true}
        else->super.onKeyDown(keyCode,event)
    }

    private fun triggerScan(){
        if(layoutReportSection.visibility==View.VISIBLE||isScanning||imageAnalysis==null)return
        val now=System.currentTimeMillis()
        if(now-lastScanTime<800)return
        lastScanTime=now;isScanning=true;isProcessingFrame=false
        tvStatus.text="正在尋找條碼..."
        imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(this)){processImage(it)}
    }

    private fun processImage(proxy:ImageProxy){
        if(!isScanning||isProcessingFrame){proxy.close();return}
        val image=proxy.image?:run{proxy.close();return}
        isProcessingFrame=true
        barcodeScanner?.process(InputImage.fromMediaImage(image,proxy.imageInfo.rotationDegrees))
            ?.addOnSuccessListener{bars->
                if(isScanning)bars.firstOrNull()?.rawValue?.takeIf{it.isNotBlank()}?.let{code->stopScanning();onBarcodeDetected(code)}
            }?.addOnFailureListener{}
            ?.addOnCompleteListener{isProcessingFrame=false;proxy.close()}
            ?:run{isProcessingFrame=false;proxy.close()}
    }
    private fun stopScanning(){isScanning=false;isProcessingFrame=false;imageAnalysis?.clearAnalyzer()}

    private fun onBarcodeDetected(code:String){
        val item=findItem(code)
        val qty=etQty.text.toString().toIntOrNull()?.coerceAtLeast(1)?:1
        if(item!=null){
            playSuccessSound();recordItem(item.customCode,item.intlCode,item.name,qty)
            tvStatus.text="✓ 掃描成功　+$qty";etQty.setText("1")
        }else{
            playFailSound();tvStatus.text="資料庫沒有此條碼";showCreateItemDialog(code)
        }
    }

    private fun findItem(raw:String):ItemInfo?{
        val code=raw.trim();barcodeMap[code]?.let{return it}
        if(code.matches(Regex("\\d+"))){
            barcodeMap[code.trimStart('0').ifEmpty{"0"}]?.let{return it}
            barcodeMap[code.padStart(6,'0')]?.let{return it}
        }
        return null
    }

    private fun recordItem(c:String,i:String,n:String,q:Int){
        val t=SimpleDateFormat("yyyy/MM/dd HH:mm:ss",Locale.TAIWAN).format(Date())
        scannedRecords[c]?.apply{qty+=q;lastTime=t}?:run{scannedRecords[c]=ScanRecord(c,i,n,q,t)}
        val r=scannedRecords[c]!!
        tvLastItem.text="最後點貨\n${r.name}\n自編碼：${r.customCode}　累計：${r.qty} 件"
        refreshList()
    }
    private fun refreshList(){
        val list=scannedRecords.values.sortedBy{it.customCode}
        adapter.updateData(list)
        tvSummary.text="品項 ${list.size} 種　｜　總數 ${list.sumOf{it.qty}} 件"
    }
    private fun clearRecords(){
        AlertDialog.Builder(this).setTitle("清空點貨紀錄").setMessage("確定要清空目前所有點貨紀錄嗎？")
            .setPositiveButton("確定清空"){_,_->scannedRecords.clear();refreshList();tvLastItem.text="最後點貨\n尚未掃描";tvStatus.text="請按「開始掃描」";etQty.setText("1")}
            .setNegativeButton("取消",null).show()
    }

    private fun showCreateItemDialog(raw:String){
        val custom=EditText(this).apply{hint="自編碼，例如：000123";setText(if(raw.matches(Regex("\\d+")))raw.padStart(6,'0') else raw)}
        val name=EditText(this).apply{hint="商品名稱"}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0);addView(custom);addView(name)}
        AlertDialog.Builder(this).setTitle("找不到商品").setMessage("條碼：$raw\n請輸入自編碼與商品名稱").setView(box)
            .setPositiveButton("建立並點貨"){_,_->
                var c=custom.text.toString().trim();val n=name.text.toString().trim()
                if(c.matches(Regex("\\d+")))c=c.padStart(6,'0')
                val q=etQty.text.toString().toIntOrNull()?.coerceAtLeast(1)?:1
                if(c.isEmpty()||n.isEmpty()){Toast.makeText(this,"自編碼與商品名稱不能空白",Toast.LENGTH_LONG).show();return@setPositiveButton}
                postNewItemToCloud(raw,c,n,q)
            }.setNegativeButton("取消",null).show()
    }

    private fun cacheFile()=File(filesDir,cacheFileName)

    private fun loadProductCache(){
        Thread{
            try{
                val f=cacheFile();if(!f.exists())return@Thread
                val arr=JSONArray(f.readText(Charsets.UTF_8));val map=mutableMapOf<String,ItemInfo>()
                for(i in 0 until arr.length()){
                    val o=arr.getJSONObject(i);val c=o.optString("customCode").trim();val intl=o.optString("intlCode").trim();val n=o.optString("name").trim()
                    if(c.isEmpty()||n.isEmpty())continue
                    val info=ItemInfo(c,intl,n);map[c]=info;map[c.trimStart('0').ifEmpty{"0"}]=info;if(intl.isNotEmpty())map[intl]=info
                }
                runOnUiThread{
                    barcodeMap.clear();barcodeMap.putAll(map)
                    tvDbStatus.text="本機資料　✓ ${map.values.associateBy{it.customCode}.size} 筆"
                    tvStatus.text="已使用上次資料，可直接掃描"
                }
            }catch(e:Exception){e.printStackTrace()}
        }.start()
    }

    private fun saveProductCache(items:Collection<ItemInfo>){
        Thread{
            try{
                val arr=JSONArray()
                items.associateBy{it.customCode}.values.forEach{item->
                    arr.put(JSONObject().apply{put("customCode",item.customCode);put("intlCode",item.intlCode);put("name",item.name)})
                }
                val tmp=File(filesDir,"$cacheFileName.tmp");tmp.writeText(arr.toString(),Charsets.UTF_8)
                val f=cacheFile();if(f.exists())f.delete();tmp.renameTo(f)
            }catch(e:Exception){e.printStackTrace()}
        }.start()
    }

    private fun fetchCloudData(force:Boolean=false){
        if(cloudLoading){if(force)Toast.makeText(this,"資料庫正在更新中，請稍候",Toast.LENGTH_SHORT).show();return}
        cloudLoading=true
        runOnUiThread{btnRefreshDb.isEnabled=false;tvDbStatus.text="雲端　更新中...";tvStatus.text="正在讀取雲端商品資料..."}
        Thread{
            var error:Exception?=null;var success=false
            for(attempt in 1..MAX_RETRY){
                try{
                    val req=Request.Builder().url(GAS_WEB_APP_URL).get().header("Cache-Control","no-cache").build()
                    client.newCall(req).execute().use{resp->
                        if(!resp.isSuccessful)throw Exception("HTTP ${resp.code}")
                        val body=resp.body?.string()?.takeIf{it.isNotBlank()}?:throw Exception("GAS 回傳空白資料")
                        val arr=JSONArray(body);val map=mutableMapOf<String,ItemInfo>();val unique=linkedMapOf<String,ItemInfo>()
                        for(i in 0 until arr.length()){
                            val o=arr.getJSONObject(i);val n=o.optString("商品名稱").trim();if(n.isEmpty())continue
                            var c=o.optString("自編碼").trim();if(c.endsWith(".0"))c=c.dropLast(2);if(c.matches(Regex("\\d+")))c=c.padStart(6,'0')
                            var intl=o.optString("國際條碼").trim();if(intl.equals("nan",true))intl=""
                            if(c.isEmpty()||c.equals("nan",true))continue
                            val info=ItemInfo(c,intl,n);unique[c]=info;map[c]=info;map[c.trimStart('0').ifEmpty{"0"}]=info;if(intl.isNotEmpty())map[intl]=info
                        }
                        if(unique.isEmpty())throw Exception("GAS 回傳 0 筆有效商品")
                        barcodeMap.clear();barcodeMap.putAll(map);saveProductCache(unique.values)
                        runOnUiThread{tvDbStatus.text="雲端　✓ ${unique.size} 筆";tvStatus.text="資料已更新，可開始掃描";btnRefreshDb.isEnabled=true;Toast.makeText(this@ScanActivity,"資料庫更新完成：${unique.size} 筆",Toast.LENGTH_SHORT).show()}
                        success=true
                    }
                    if(success)break
                }catch(e:Exception){
                    error=e
                    if(attempt<MAX_RETRY)try{Thread.sleep(if(attempt==1)2000L else 4000L)}catch(_:InterruptedException){}
                }
            }
            if(!success)runOnUiThread{
                btnRefreshDb.isEnabled=true
                if(barcodeMap.isNotEmpty()){
                    val count=barcodeMap.values.associateBy{it.customCode}.size
                    tvDbStatus.text="雲端更新失敗　｜　本機 $count 筆"
                    tvStatus.text="⚠ 雲端更新失敗，繼續使用上次資料"
                    Toast.makeText(this,"雲端更新失敗，已使用上次資料",Toast.LENGTH_LONG).show()
                }else{
                    tvDbStatus.text="雲端　載入失敗";tvStatus.text="❌ 尚無商品資料，請按「更新資料庫」重試"
                    Toast.makeText(this,"商品資料載入失敗，請確認網路後重試",Toast.LENGTH_LONG).show()
                }
            }
            cloudLoading=false
            runOnUiThread{btnRefreshDb.isEnabled=true}
        }.start()
    }

    private fun postNewItemToCloud(intl:String,c:String,n:String,q:Int){
        tvStatus.text="正在同步雲端..."
        Thread{
            try{
                val json=JSONObject().apply{put("國際條碼",intl);put("自編碼",c);put("商品名稱",n)}
                val body=json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req=Request.Builder().url(GAS_WEB_APP_URL).post(body).build()
                client.newCall(req).execute().use{if(!it.isSuccessful)throw Exception("HTTP ${it.code}")}
                val info=ItemInfo(c,intl,n);barcodeMap[c]=info;barcodeMap[c.trimStart('0').ifEmpty{"0"}]=info;if(intl.isNotEmpty())barcodeMap[intl]=info
                saveProductCache(barcodeMap.values)
                runOnUiThread{playSuccessSound();recordItem(c,intl,n,q);tvStatus.text="✓ 建檔並完成點貨";etQty.setText("1")}
            }catch(e:Exception){e.printStackTrace();runOnUiThread{Toast.makeText(this,"同步雲端失敗，請檢查網路",Toast.LENGTH_LONG).show();tvStatus.text="同步失敗"}}
        }.start()
    }

    private fun showReportPage(){
        if(scannedRecords.isEmpty()){Toast.makeText(this,"目前沒有點貨紀錄",Toast.LENGTH_SHORT).show();return}
        stopScanning();stopCamera()
        val list=scannedRecords.values.sortedBy{it.customCode}
        tvReportSummary.text="本次盤點　${list.size} 種商品　｜　${list.sumOf{it.qty}} 件"
        tvReportContent.removeAllViews()
        addReportRow("自編碼","品名","數量",true)
        list.forEach{addReportRow(it.customCode,it.name,it.qty.toString(),false)}
        layoutScannerSection.visibility=View.GONE;layoutReportSection.visibility=View.VISIBLE
    }

    private fun addReportRow(c:String,n:String,q:String,header:Boolean){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));if(header)setBackgroundColor(Color.rgb(232,240,254))}
        val cv=TextView(this).apply{text=c;textSize=if(header)16f else 17f;setTextColor(Color.DKGRAY);if(header)setTypeface(null,Typeface.BOLD);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        val nv=TextView(this).apply{text=n;textSize=if(header)16f else 17f;setTextColor(Color.DKGRAY);if(header)setTypeface(null,Typeface.BOLD);maxLines=5;breakStrategy=android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY}
        val qv=TextView(this).apply{text=q;textSize=if(header)16f else 18f;setTextColor(Color.rgb(20,80,160));gravity=Gravity.CENTER;if(header)setTypeface(null,Typeface.BOLD)}
        row.addView(cv,LinearLayout.LayoutParams(dp(82),-2));row.addView(nv,LinearLayout.LayoutParams(0,-2,1f));row.addView(qv,LinearLayout.LayoutParams(dp(52),-2));tvReportContent.addView(row)
        if(!header){val d=View(this).apply{setBackgroundColor(Color.rgb(225,225,225))};tvReportContent.addView(d,LinearLayout.LayoutParams(-1,dp(1)))}
    }

    private fun showScannerPage(){layoutReportSection.visibility=View.GONE;layoutScannerSection.visibility=View.VISIBLE;tvStatus.text="請按「開始掃描」";startCamera()}
    private fun stopCamera(){try{imageAnalysis?.clearAnalyzer()}catch(_:Exception){};try{cameraProvider?.unbindAll()}catch(_:Exception){};camera=null;imageAnalysis=null;isScanning=false;isProcessingFrame=false}

    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(rc:Int,p:Array<out String>,r:IntArray){super.onRequestPermissionsResult(rc,p,r);if(rc==REQUEST_CAMERA&&r.isNotEmpty()&&r[0]==PackageManager.PERMISSION_GRANTED)startCamera()}

    private fun startCamera(){
        if(!hasCameraPermission())return
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            try{
                val provider=future.get();cameraProvider=provider
                val preview=Preview.Builder().build();preview.setSurfaceProvider(previewView.surfaceProvider)
                imageAnalysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                provider.unbindAll()
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,imageAnalysis)
                tvStatus.text="相機已準備完成，請按「開始掃描」"
            }catch(e:Exception){e.printStackTrace();tvStatus.text="相機啟動失敗"}
        },ContextCompat.getMainExecutor(this))
    }

    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed(){if(layoutReportSection.visibility==View.VISIBLE)showScannerPage()else super.onBackPressed()}

    override fun onDestroy(){
        stopScanning();stopCamera()
        try{barcodeScanner?.close()}catch(_:Exception){}
        try{successPlayer?.release()}catch(_:Exception){}
        try{failPlayer?.release()}catch(_:Exception){}
        client.dispatcher.cancelAll()
        super.onDestroy()
    }
}
