package com.store.inventoryscanner

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer

/**
 * ZXing Core 專用的 Code 128 相機解碼器。
 *
 * 只負責「影像 -> Code 128 原始字串」。
 * GS1-128 的 AI 欄位解析仍交給 Gs1Parser。
 *
 * 不使用 ZXing-C++、NDK、JNI 或 CMake。
 */
object ZxingDecoder {

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.CODE_128),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ASSUME_GS1 to true
    )

    fun decodeCode128(proxy: ImageProxy, rotationDegrees: Int): String? {
        val image = proxy.image ?: return null
        val yPlane = image.planes.firstOrNull() ?: return null

        val width = image.width
        val height = image.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val buffer = yPlane.buffer.duplicate()

        val gray = ByteArray(width * height)

        // Y 平面就是灰階亮度資料。處理 rowStride / pixelStride，
        // 避免直接把帶 padding 的 ImageProxy buffer 當成連續影像。
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val index = rowStart + x * pixelStride
                if (index < buffer.limit()) {
                    gray[y * width + x] = buffer.get(index)
                }
            }
        }

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360

        // 相機實際方向不一定與條碼方向一致。
        // Code 128 主要是水平條碼，所以四個方向都試一次。
        val candidates = when (normalizedRotation) {
            90 -> listOf(
                Rotated(gray, width, height, 90),
                Rotated(gray, width, height, 0),
                Rotated(gray, width, height, 270),
                Rotated(gray, width, height, 180)
            )
            180 -> listOf(
                Rotated(gray, width, height, 180),
                Rotated(gray, width, height, 0),
                Rotated(gray, width, height, 90),
                Rotated(gray, width, height, 270)
            )
            270 -> listOf(
                Rotated(gray, width, height, 270),
                Rotated(gray, width, height, 0),
                Rotated(gray, width, height, 90),
                Rotated(gray, width, height, 180)
            )
            else -> listOf(
                Rotated(gray, width, height, 0),
                Rotated(gray, width, height, 90),
                Rotated(gray, width, height, 270),
                Rotated(gray, width, height, 180)
            )
        }

        for (candidate in candidates) {
            decode(candidate.data, candidate.width, candidate.height)?.let { return it }
        }

        return null
    }

    private fun decode(data: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(
            data,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )

        // HybridBinarizer 通常比較適合手機相機畫面。
        tryDecode(BinaryBitmap(HybridBinarizer(source)))?.let { return it }

        // 高反差條碼再用 GlobalHistogramBinarizer 補一次。
        tryDecode(BinaryBitmap(GlobalHistogramBinarizer(source)))?.let { return it }

        return null
    }

    private fun tryDecode(bitmap: BinaryBitmap): String? {
        val reader = MultiFormatReader()
        return try {
            val result: Result = reader.decode(bitmap, hints)
            result.text.takeIf { it.isNotBlank() }
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private data class Rotated(
        val data: ByteArray,
        val width: Int,
        val height: Int
    )

    private fun Rotated(
        src: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        degrees: Int
    ): Rotated {
        return when (degrees) {
            90 -> {
                val out = ByteArray(srcWidth * srcHeight)
                val outWidth = srcHeight
                for (y in 0 until srcHeight) {
                    for (x in 0 until srcWidth) {
                        val nx = srcHeight - 1 - y
                        val ny = x
                        out[ny * outWidth + nx] = src[y * srcWidth + x]
                    }
                }
                Rotated(out, srcHeight, srcWidth)
            }

            180 -> {
                val out = ByteArray(srcWidth * srcHeight)
                for (y in 0 until srcHeight) {
                    for (x in 0 until srcWidth) {
                        val nx = srcWidth - 1 - x
                        val ny = srcHeight - 1 - y
                        out[ny * srcWidth + nx] = src[y * srcWidth + x]
                    }
                }
                Rotated(out, srcWidth, srcHeight)
            }

            270 -> {
                val out = ByteArray(srcWidth * srcHeight)
                val outWidth = srcHeight
                for (y in 0 until srcHeight) {
                    for (x in 0 until srcWidth) {
                        val nx = y
                        val ny = srcWidth - 1 - x
                        out[ny * outWidth + nx] = src[y * srcWidth + x]
                    }
                }
                Rotated(out, srcHeight, srcWidth)
            }

            else -> Rotated(src.copyOf(), srcWidth, srcHeight)
        }
    }
}
