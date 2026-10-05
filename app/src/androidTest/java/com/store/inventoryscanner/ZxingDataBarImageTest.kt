package com.store.inventoryscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZxingDataBarImageTest {

    // 實際 DataBar 掃描結果
    private val expected = "18886456203092"

    @Test
    fun testRss14AndRssExpandedAgainstRealPhoto() {

        val context = InstrumentationRegistry
            .getInstrumentation()
            .context

        val bitmap = context.assets.open("databar_test.jpg").use { input ->
            BitmapFactory.decodeStream(input)
                ?: error("無法讀取 databar_test.jpg")
        }

        val results = linkedMapOf<String, String?>()

        results["RSS_14 + Hybrid"] =
            decode(bitmap, BarcodeFormat.RSS_14, useHybrid = true)

        results["RSS_14 + Global"] =
            decode(bitmap, BarcodeFormat.RSS_14, useHybrid = false)

        results["RSS_EXPANDED + Hybrid"] =
            decode(bitmap, BarcodeFormat.RSS_EXPANDED, useHybrid = true)

        results["RSS_EXPANDED + Global"] =
            decode(bitmap, BarcodeFormat.RSS_EXPANDED, useHybrid = false)

        println("===== ZXing Core DataBar Android Test =====")

        results.forEach { (name, value) ->
            println("$name -> ${value ?: "FAIL"}")
        }

        println("Expected -> $expected")

        val success = results.values
            .filterNotNull()
            .firstOrNull()

        assertEquals(
            "ZXing Core 沒有成功解碼這張 DataBar 實拍照片",
            expected,
            success
        )
    }

    private fun decode(
        bitmap: Bitmap,
        format: BarcodeFormat,
        useHybrid: Boolean
    ): String? {

        val pixels = IntArray(bitmap.width * bitmap.height)

        bitmap.getPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height
        )

        val source = RGBLuminanceSource(
            bitmap.width,
            bitmap.height,
            pixels
        )

        val binaryBitmap = if (useHybrid) {
            BinaryBitmap(
                HybridBinarizer(source)
            )
        } else {
            BinaryBitmap(
                GlobalHistogramBinarizer(source)
            )
        }

        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(format),
            DecodeHintType.TRY_HARDER to true
        )

        val reader = MultiFormatReader()

        return try {
            val result: Result = reader.decode(
                binaryBitmap,
                hints
            )

            result.text

        } catch (_: Exception) {
            null

        } finally {
            reader.reset()
        }
    }
}
