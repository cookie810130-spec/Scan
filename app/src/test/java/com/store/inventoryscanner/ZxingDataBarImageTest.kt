package com.store.inventoryscanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.File

class ZxingDataBarImageTest {

    private val expected = "0118886456203092"

    @Test
    fun testRss14AndRssExpandedAgainstRealPhoto() {
        val image = loadImage()

        val results = linkedMapOf<String, String?>()

        results["RSS_14 + Hybrid"] =
            decode(image, BarcodeFormat.RSS_14, useHybrid = true)
        results["RSS_14 + Global"] =
            decode(image, BarcodeFormat.RSS_14, useHybrid = false)
        results["RSS_EXPANDED + Hybrid"] =
            decode(image, BarcodeFormat.RSS_EXPANDED, useHybrid = true)
        results["RSS_EXPANDED + Global"] =
            decode(image, BarcodeFormat.RSS_EXPANDED, useHybrid = false)

        println("===== ZXing Core DataBar test =====")
        results.forEach { (name, value) ->
            println("$name -> ${value ?: "FAIL"}")
        }
        println("Expected -> $expected")

        val success = results.values.filterNotNull().firstOrNull()
        assertEquals(
            "ZXing Core did not decode the supplied DataBar photo with RSS_14/RSS_EXPANDED",
            expected,
            success
        )
    }

    private fun loadImage(): BufferedImage {
        val stream = javaClass.classLoader!!.getResourceAsStream("databar_test.jpg")
            ?: error("Missing test resource: databar_test.jpg")
        return stream.use { ImageIO.read(it) }
    }

    private fun decode(
        image: BufferedImage,
        format: BarcodeFormat,
        useHybrid: Boolean
    ): String? {
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)

        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val bitmap = if (useHybrid) {
            BinaryBitmap(HybridBinarizer(source))
        } else {
            BinaryBitmap(GlobalHistogramBinarizer(source))
        }

        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(format),
            DecodeHintType.TRY_HARDER to true
        )

        val reader = MultiFormatReader()
        return try {
            val result: Result = reader.decode(bitmap, hints)
            result.text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
