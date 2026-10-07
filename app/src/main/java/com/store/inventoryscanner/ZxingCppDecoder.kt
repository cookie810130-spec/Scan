package com.store.inventoryscanner

import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

/**
 * ZXing-C++ DataBar 補掃引擎。
 *
 * 設計：
 * 1. 不負責一般主掃描
 * 2. ML Kit 掃不到時才呼叫
 * 3. 只允許 1D / DataBar
 * 4. 不掃 QR / Data Matrix / PDF417 / Aztec
 *
 * 這樣可以避免 ZXing-C++ 每一幀都執行造成卡頓。
 */
object ZxingCppDecoder {

    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(
                BarcodeReader.Format.DATA_BAR,
                BarcodeReader.Format.DATA_BAR_OMNI,
                BarcodeReader.Format.DATA_BAR_STK,
                BarcodeReader.Format.DATA_BAR_STK_OMNI,
                BarcodeReader.Format.DATA_BAR_LTD,
                BarcodeReader.Format.DATA_BAR_EXP,
                BarcodeReader.Format.DATA_BAR_EXP_STK
            ),

            // 不開啟 Try Harder，避免補掃過慢
            tryHarder = false,

            // CameraX 已經把 rotation 傳給 ZXing-C++
            // 不再額外旋轉影像
            tryRotate = false,

            // DataBar 一般不需要反相
            tryInvert = false,

            // 1280x720 不需要再縮圖
            tryDownscale = false,

            // 一次只找一個
            maxNumberOfSymbols = 1,

            // DataBar 使用 Local Average
            binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE,

            // 不回傳失敗結果
            returnErrors = false,

            // GS1/人類可讀文字模式
            textMode = BarcodeReader.TextMode.HRI
        )
    )

    /**
     * 使用 CameraX ImageProxy 進行 DataBar 補掃。
     *
     * 注意：
     * 不在這裡 close ImageProxy。
     * ImageProxy 的生命週期由 ScanActivity 控制。
     */
    fun decode(proxy: ImageProxy): String? {

        return try {

            val results = reader.read(proxy)

            results
                .asSequence()
                .filter { it.error == null }
                .mapNotNull { it.text?.trim() }
                .firstOrNull { it.isNotEmpty() }

        } catch (_: Exception) {

            null
        }
    }
}
