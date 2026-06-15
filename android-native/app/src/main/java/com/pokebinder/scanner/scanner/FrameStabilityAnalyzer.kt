package com.pokebinder.scanner.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.pokebinder.scanner.model.CardDetection
import com.pokebinder.scanner.model.FrameProbe
import com.pokebinder.scanner.model.ScanPoint
import org.opencv.core.Point
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.hypot

class FrameStabilityAnalyzer(
    private val onProbe: (FrameProbe) -> Unit,
    private val onStableFrame: (ByteArray) -> Unit,
    private val detector: CardRegionDetector = CardRegionDetector(),
) : ImageAnalysis.Analyzer {

    private var previousRegion: CardRegionDetector.DetectedCardRegion? = null
    private var stableFrames = 0
    private var lastCaptureAt = 0L
    private var analysisLocked = false

    override fun analyze(image: ImageProxy) {
        var rotatedBitmap: Bitmap? = null
        try {
            rotatedBitmap = image.toRotatedBitmap() ?: return
            val rawRegion = detector.detect(rotatedBitmap)
            if (rawRegion == null) {
                previousRegion = null
                stableFrames = 0
                onProbe(FrameProbe())
                return
            }

            val motion = cornerMotion(previousRegion?.detection, rawRegion.detection)
            val region = smoothRegion(previousRegion, rawRegion, motion)
            val detection = region.detection
            previousRegion = region
            val acceptableFrame = region.brightness in MIN_BRIGHTNESS..MAX_BRIGHTNESS &&
                detection.confidence >= MIN_DETECTION_CONFIDENCE
            stableFrames = if (acceptableFrame && motion < MAX_CORNER_MOTION) {
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
                try {
                    val cardBitmap = detector.warp(rotatedBitmap, region)
                    val output = ByteArrayOutputStream()
                    cardBitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                    cardBitmap.recycle()
                    lastCaptureAt = now
                    stableFrames = 0
                    onStableFrame(output.toByteArray())
                } finally {
                    analysisLocked = false
                }
            }
        } finally {
            rotatedBitmap?.recycle()
            image.close()
        }
    }

    private fun smoothRegion(
        previous: CardRegionDetector.DetectedCardRegion?,
        current: CardRegionDetector.DetectedCardRegion,
        motion: Double,
    ): CardRegionDetector.DetectedCardRegion {
        if (previous == null || motion > SMOOTHING_RESET_MOTION) return current
        val points = previous.sourceCorners.zip(current.sourceCorners).map { (before, after) ->
            Point(
                before.x * (1.0 - CURRENT_FRAME_WEIGHT) + after.x * CURRENT_FRAME_WEIGHT,
                before.y * (1.0 - CURRENT_FRAME_WEIGHT) + after.y * CURRENT_FRAME_WEIGHT,
            )
        }
        val detection = current.detection.copy(
            corners = points.map { point ->
                ScanPoint(
                    x = (point.x / current.detection.frameWidth).toFloat().coerceIn(0f, 1f),
                    y = (point.y / current.detection.frameHeight).toFloat().coerceIn(0f, 1f),
                )
            },
            confidence = previous.detection.confidence * (1.0 - CURRENT_FRAME_WEIGHT) +
                current.detection.confidence * CURRENT_FRAME_WEIGHT,
        )
        return current.copy(
            detection = detection,
            sourceCorners = points,
            brightness = previous.brightness * (1.0 - CURRENT_FRAME_WEIGHT) +
                current.brightness * CURRENT_FRAME_WEIGHT,
        )
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
        const val MIN_DETECTION_CONFIDENCE = 0.50
        const val MAX_CORNER_MOTION = 0.028
        const val SMOOTHING_RESET_MOTION = 0.09
        const val CURRENT_FRAME_WEIGHT = 0.42
        const val REQUIRED_STABLE_FRAMES = 3
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
