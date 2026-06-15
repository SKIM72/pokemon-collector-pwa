package com.pokebinder.scanner.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.pokebinder.scanner.model.CardDetection
import com.pokebinder.scanner.model.FrameProbe
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.hypot

class FrameStabilityAnalyzer(
    private val onProbe: (FrameProbe) -> Unit,
    private val onStableFrame: (ByteArray) -> Unit,
    private val detector: CardRegionDetector = CardRegionDetector(),
) : ImageAnalysis.Analyzer {

    private var previousDetection: CardDetection? = null
    private var stableFrames = 0
    private var lastCaptureAt = 0L
    private var analysisLocked = false

    override fun analyze(image: ImageProxy) {
        var rotatedBitmap: Bitmap? = null
        try {
            rotatedBitmap = image.toRotatedBitmap() ?: return
            val region = detector.detect(rotatedBitmap)
            if (region == null) {
                previousDetection = null
                stableFrames = 0
                onProbe(FrameProbe())
                return
            }

            val detection = region.detection
            val motion = cornerMotion(previousDetection, detection)
            previousDetection = detection
            val wellLit = region.brightness in MIN_BRIGHTNESS..MAX_BRIGHTNESS
            stableFrames = if (wellLit && motion < MAX_CORNER_MOTION) {
                stableFrames + 1
            } else {
                0
            }
            onProbe(
                FrameProbe(
                    brightness = region.brightness,
                    motion = motion,
                    stableFrames = stableFrames,
                    detection = detection,
                ),
            )

            val now = System.currentTimeMillis()
            if (
                !analysisLocked &&
                stableFrames >= REQUIRED_STABLE_FRAMES &&
                now - lastCaptureAt >= CAPTURE_COOLDOWN_MS
            ) {
                analysisLocked = true
                val cardBitmap = detector.warp(rotatedBitmap, region)
                val output = ByteArrayOutputStream()
                cardBitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                cardBitmap.recycle()
                lastCaptureAt = now
                stableFrames = 0
                onStableFrame(output.toByteArray())
                analysisLocked = false
            }
        } finally {
            rotatedBitmap?.recycle()
            image.close()
        }
    }

    private fun cornerMotion(
        previous: CardDetection?,
        current: CardDetection,
    ): Double {
        if (previous == null || previous.corners.size != current.corners.size) return 1.0
        return current.corners.indices.sumOf { index ->
            val before = previous.corners[index]
            val after = current.corners[index]
            hypot(
                (after.x - before.x).toDouble(),
                (after.y - before.y).toDouble(),
            )
        } / current.corners.size
    }

    private fun ImageProxy.toRotatedBitmap(): Bitmap? {
        val rgbaBitmap = toRgbaBitmap() ?: return null
        if (imageInfo.rotationDegrees == 0) return rgbaBitmap
        val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(
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

    private fun ImageProxy.toRgbaBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val pixels = IntArray(width * height)
        if (plane.pixelStride == 4) {
            val rowPixels = IntArray(width)
            for (y in 0 until height) {
                buffer.position(y * plane.rowStride)
                buffer.slice()
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asIntBuffer()
                    .get(rowPixels, 0, width)
                for (x in 0 until width) {
                    pixels[y * width + x] = packedRgbaToArgb(rowPixels[x])
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index + 3 >= buffer.limit()) continue
                pixels[y * width + x] = rgbaToArgb(
                    red = buffer.get(index).toInt() and 0xff,
                    green = buffer.get(index + 1).toInt() and 0xff,
                    blue = buffer.get(index + 2).toInt() and 0xff,
                    alpha = buffer.get(index + 3).toInt() and 0xff,
                )
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val MIN_BRIGHTNESS = 32.0
        const val MAX_BRIGHTNESS = 238.0
        const val MAX_CORNER_MOTION = 0.035
        const val REQUIRED_STABLE_FRAMES = 2
        const val CAPTURE_COOLDOWN_MS = 1_700L
    }
}

internal fun rgbaToArgb(
    red: Int,
    green: Int,
    blue: Int,
    alpha: Int,
): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

internal fun packedRgbaToArgb(rgba: Int): Int =
    (rgba and 0xFF00FF00.toInt()) or
        ((rgba and 0x00FF0000) ushr 16) or
        ((rgba and 0x000000FF) shl 16)
