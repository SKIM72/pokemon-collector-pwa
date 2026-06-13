package com.pokebinder.scanner.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.pokebinder.scanner.model.FrameProbe
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FrameStabilityAnalyzer(
    private val onProbe: (FrameProbe) -> Unit,
    private val onStableFrame: (ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {

    private var previousSamples: IntArray? = null
    private var stableFrames = 0
    private var lastCaptureAt = 0L
    private var analysisLocked = false

    override fun analyze(image: ImageProxy) {
        try {
            val samples = sampleCenterLuminance(image)
            if (samples.isEmpty()) return

            val brightness = samples.average()
            val previous = previousSamples
            val motion = if (previous == null || previous.size != samples.size) {
                1.0
            } else {
                samples.indices.sumOf { abs(samples[it] - previous[it]).toDouble() } /
                    (samples.size * 255.0)
            }
            previousSamples = samples

            val wellLit = brightness in MIN_BRIGHTNESS..MAX_BRIGHTNESS
            stableFrames = if (wellLit && motion < MAX_MOTION) stableFrames + 1 else 0
            onProbe(FrameProbe(brightness, motion, stableFrames))

            val now = System.currentTimeMillis()
            if (
                !analysisLocked &&
                stableFrames >= REQUIRED_STABLE_FRAMES &&
                now - lastCaptureAt >= CAPTURE_COOLDOWN_MS
            ) {
                analysisLocked = true
                val jpegBytes = image.toCenteredCardJpeg()
                lastCaptureAt = now
                stableFrames = 0
                if (jpegBytes != null) onStableFrame(jpegBytes)
                analysisLocked = false
            }
        } finally {
            image.close()
        }
    }

    private fun sampleCenterLuminance(image: ImageProxy): IntArray {
        val plane = image.planes.firstOrNull() ?: return IntArray(0)
        val buffer = plane.buffer
        val cropWidth = (image.width * 0.58f).toInt().coerceAtLeast(1)
        val cropHeight = (cropWidth * CARD_HEIGHT_RATIO).toInt()
            .coerceAtMost((image.height * 0.76f).toInt())
            .coerceAtLeast(1)
        val startX = (image.width - cropWidth) / 2
        val startY = (image.height - cropHeight) / 2
        val sampleColumns = 16
        val sampleRows = 22
        val values = IntArray(sampleColumns * sampleRows)

        var target = 0
        for (row in 0 until sampleRows) {
            val y = startY + row * max(1, cropHeight - 1) / max(1, sampleRows - 1)
            for (column in 0 until sampleColumns) {
                val x = startX + column * max(1, cropWidth - 1) / max(1, sampleColumns - 1)
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index + 3 >= buffer.limit()) {
                    values[target++] = 0
                    continue
                }

                val red = buffer.get(index + 1).toInt() and 0xff
                val green = buffer.get(index + 2).toInt() and 0xff
                val blue = buffer.get(index + 3).toInt() and 0xff
                values[target++] = (red * 30 + green * 59 + blue * 11) / 100
            }
        }
        return values
    }

    private fun ImageProxy.toCenteredCardJpeg(): ByteArray? {
        val rgbaBitmap = toRgbaBitmap() ?: return null
        val rotatedBitmap = if (imageInfo.rotationDegrees == 0) {
            rgbaBitmap
        } else {
            val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(
                rgbaBitmap,
                0,
                0,
                rgbaBitmap.width,
                rgbaBitmap.height,
                matrix,
                true,
            ).also {
                if (it !== rgbaBitmap) rgbaBitmap.recycle()
            }
        }

        val cropWidth = min(rotatedBitmap.width * 0.72f, rotatedBitmap.height / CARD_HEIGHT_RATIO)
            .toInt()
            .coerceAtLeast(1)
        val cropHeight = (cropWidth * CARD_HEIGHT_RATIO)
            .toInt()
            .coerceAtMost(rotatedBitmap.height)
        val cropLeft = ((rotatedBitmap.width - cropWidth) / 2).coerceAtLeast(0)
        val cropTop = ((rotatedBitmap.height - cropHeight) / 2).coerceAtLeast(0)
        val cardBitmap = Bitmap.createBitmap(
            rotatedBitmap,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight,
        )

        val output = ByteArrayOutputStream()
        cardBitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)
        cardBitmap.recycle()
        if (cardBitmap !== rotatedBitmap) rotatedBitmap.recycle()
        return output.toByteArray()
    }

    private fun ImageProxy.toRgbaBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index + 3 >= buffer.limit()) continue
                val alpha = buffer.get(index).toInt() and 0xff
                val red = buffer.get(index + 1).toInt() and 0xff
                val green = buffer.get(index + 2).toInt() and 0xff
                val blue = buffer.get(index + 3).toInt() and 0xff
                pixels[y * width + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val CARD_HEIGHT_RATIO = 88f / 63f
        const val MIN_BRIGHTNESS = 35.0
        const val MAX_BRIGHTNESS = 235.0
        const val MAX_MOTION = 0.035
        const val REQUIRED_STABLE_FRAMES = 5
        const val CAPTURE_COOLDOWN_MS = 1_800L
    }
}
