package com.store.inventoryscanner

import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

/**
 * ZXing-C++ DataBar 補掃器。
 *
 * 只開啟一維 DataBar / GS1 DataBar 類型：
 * - DATA_BAR
 * - DATA_BAR_EXPANDED
 * - DATA_BAR_LIMITED
 *
 * 不加入 QR Code 或任何其他二維碼格式。
 */
object ZxingCppDecoder {

    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(
                BarcodeReader.Format.DATA_BAR,
                BarcodeReader.Format.DATA_BAR_EXPANDED,
                BarcodeReader.Format.DATA_BAR_LIMITED
            ),
            tryHarder = false,
            tryRotate = false,
            tryInvert = false,
            tryDownscale = false,
            maxNumberOfSymbols = 1,
            binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE
        )
    )

    /**
     * 使用 ZXing-C++ 直接讀取 CameraX 的 ImageProxy。
     *
     * 回傳成功解碼的條碼文字；失敗則回傳 null。
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
