package com.store.inventoryscanner

import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

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

            // DataBar 補掃以速度為優先
            tryHarder = false,

            // CameraX 已經提供 rotation
            tryRotate = false,

            tryInvert = false,
            tryDownscale = false,

            maxNumberOfSymbols = 1,

            binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE
        )
    )

    fun decode(proxy: ImageProxy): String? {
        return try {
            val results = reader.read(proxy)

            results
                .asSequence()
                .filter { it.error == BarcodeReader.ErrorType.NONE }
                .mapNotNull { it.text?.trim() }
                .firstOrNull { it.isNotEmpty() }

        } catch (_: Exception) {
            null
        }
    }
}
