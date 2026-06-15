package com.pokebinder.scanner.scanner

import android.graphics.Bitmap
import com.pokebinder.scanner.model.CardDetection
import com.pokebinder.scanner.model.ScanPoint
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class CardRegionDetector {
    init {
        check(OpenCVLoader.initLocal()) { "OpenCV 초기화에 실패했습니다." }
    }

    fun detect(bitmap: Bitmap): DetectedCardRegion? {
        val source = Mat()
        val working = Mat()
        val gray = Mat()
        val edges = Mat()
        val threshold = Mat()
        val thresholdEdges = Mat()
        val hierarchy = Mat()
        return try {
            Utils.bitmapToMat(bitmap, source)
            val scale = min(1.0, MAX_ANALYSIS_EDGE / max(source.cols(), source.rows()).toDouble())
            if (scale < 1.0) {
                Imgproc.resize(source, working, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                source.copyTo(working)
            }

            Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 32.0, 112.0)
            Imgproc.adaptiveThreshold(
                gray,
                threshold,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                31,
                7.0,
            )
            Imgproc.Canny(threshold, thresholdEdges, 24.0, 92.0)
            Core.bitwise_or(edges, thresholdEdges, edges)
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(9.0, 9.0),
            )
            Imgproc.morphologyEx(
                edges,
                edges,
                Imgproc.MORPH_CLOSE,
                kernel,
                Point(-1.0, -1.0),
                2,
            )
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val frameArea = working.cols().toDouble() * working.rows()
            val candidates = contours.asSequence()
                .mapNotNull { contour -> candidate(contour, frameArea, working.size()) }
                .toList()
            contours.forEach(MatOfPoint::release)
            try {
                val best = candidates.maxByOrNull { it.score }
                if (best == null || best.score < MIN_SCORE) {
                    return centeredFallback(bitmap, gray, edges, scale)
                }
                val mean = Core.mean(gray, best.mask)
                detectedRegion(
                    bitmap = bitmap,
                    scaledPoints = best.points,
                    scale = scale,
                    confidence = best.score,
                    brightness = mean.`val`[0],
                )
            } finally {
                candidates.forEach { it.mask.release() }
            }
        } finally {
            source.release()
            working.release()
            gray.release()
            edges.release()
            threshold.release()
            thresholdEdges.release()
            hierarchy.release()
        }
    }

    fun warp(
        bitmap: Bitmap,
        region: DetectedCardRegion,
    ): Bitmap {
        val source = Mat()
        val output = Mat(CARD_OUTPUT_HEIGHT, CARD_OUTPUT_WIDTH, CvType.CV_8UC4)
        val sourcePoints = MatOfPoint2f(*region.sourceCorners.toTypedArray())
        val targetPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(CARD_OUTPUT_WIDTH - 1.0, 0.0),
            Point(CARD_OUTPUT_WIDTH - 1.0, CARD_OUTPUT_HEIGHT - 1.0),
            Point(0.0, CARD_OUTPUT_HEIGHT - 1.0),
        )
        val transform = Mat()
        return try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.getPerspectiveTransform(sourcePoints, targetPoints).copyTo(transform)
            Imgproc.warpPerspective(
                source,
                output,
                transform,
                Size(CARD_OUTPUT_WIDTH.toDouble(), CARD_OUTPUT_HEIGHT.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE,
                Scalar.all(0.0),
            )
            Bitmap.createBitmap(
                CARD_OUTPUT_WIDTH,
                CARD_OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888,
            ).also { Utils.matToBitmap(output, it) }
        } finally {
            source.release()
            output.release()
            sourcePoints.release()
            targetPoints.release()
            transform.release()
        }
    }

    private fun candidate(
        contour: MatOfPoint,
        frameArea: Double,
        frameSize: Size,
    ): Candidate? {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val approximation = MatOfPoint2f()
        return try {
            val perimeter = Imgproc.arcLength(contour2f, true)
            if (perimeter <= 0.0) return null
            val points = APPROXIMATION_FACTORS.firstNotNullOfOrNull { factor ->
                Imgproc.approxPolyDP(contour2f, approximation, perimeter * factor, true)
                approximation.toArray().takeIf { it.size == 4 }
            } ?: return null

            val polygon = MatOfPoint(*points)
            try {
                if (!Imgproc.isContourConvex(polygon)) return null
                val area = abs(Imgproc.contourArea(polygon))
                val areaRatio = area / frameArea
                if (areaRatio !in MIN_AREA_RATIO..MAX_AREA_RATIO) return null

                val ordered = orderCorners(points.toList())
                val width = (
                    distance(ordered[0], ordered[1]) +
                        distance(ordered[3], ordered[2])
                    ) / 2.0
                val height = (
                    distance(ordered[0], ordered[3]) +
                        distance(ordered[1], ordered[2])
                    ) / 2.0
                val aspect = max(width, height) / min(width, height).coerceAtLeast(1.0)
                if (aspect !in MIN_ASPECT..MAX_ASPECT) return null

                val rotatedRect = Imgproc.minAreaRect(contour2f)
                val rectangleArea = rotatedRect.size.area().coerceAtLeast(1.0)
                val rectangularity = (area / rectangleArea).coerceIn(0.0, 1.0)
                if (rectangularity < MIN_RECTANGULARITY) return null

                val maxAngleCosine = maxCornerCosine(ordered)
                if (maxAngleCosine > MAX_CORNER_COSINE) return null

                val centerX = ordered.sumOf { it.x } / 4.0
                val centerY = ordered.sumOf { it.y } / 4.0
                val centerDistance = hypot(
                    (centerX - frameSize.width / 2.0) / frameSize.width,
                    (centerY - frameSize.height / 2.0) / frameSize.height,
                )
                val aspectScore = (
                    1.0 - abs(aspect - CARD_ASPECT) / ASPECT_TOLERANCE
                    ).coerceIn(0.0, 1.0)
                val areaScore = ((areaRatio - MIN_AREA_RATIO) / 0.28).coerceIn(0.0, 1.0)
                val angleScore = (1.0 - maxAngleCosine / MAX_CORNER_COSINE)
                    .coerceIn(0.0, 1.0)
                val centerScore = (1.0 - centerDistance / 0.72).coerceIn(0.0, 1.0)
                val score = aspectScore * 0.38 +
                    rectangularity * 0.27 +
                    angleScore * 0.20 +
                    areaScore * 0.10 +
                    centerScore * 0.05

                val mask = Mat.zeros(
                    frameSize.height.toInt(),
                    frameSize.width.toInt(),
                    CvType.CV_8UC1,
                )
                Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0))
                Candidate(ordered, score, mask)
            } finally {
                polygon.release()
            }
        } finally {
            contour2f.release()
            approximation.release()
        }
    }

    private fun orderCorners(points: List<Point>): List<Point> {
        require(points.size == 4)
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.maxBy { it.x - it.y }
        val bottomLeft = points.minBy { it.x - it.y }
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun maxCornerCosine(points: List<Point>): Double {
        var maximum = 0.0
        for (index in points.indices) {
            val previous = points[(index + 3) % 4]
            val current = points[index]
            val next = points[(index + 1) % 4]
            val ax = previous.x - current.x
            val ay = previous.y - current.y
            val bx = next.x - current.x
            val by = next.y - current.y
            val denominator = hypot(ax, ay) * hypot(bx, by)
            if (denominator > 0.0) {
                maximum = max(maximum, abs((ax * bx + ay * by) / denominator))
            }
        }
        return maximum
    }

    private fun distance(first: Point, second: Point): Double =
        hypot(first.x - second.x, first.y - second.y)

    private fun centeredFallback(
        bitmap: Bitmap,
        gray: Mat,
        edges: Mat,
        scale: Double,
    ): DetectedCardRegion? {
        var width = gray.cols() * FALLBACK_WIDTH_RATIO
        var height = width * CARD_ASPECT
        val maximumHeight = gray.rows() * FALLBACK_HEIGHT_RATIO
        if (height > maximumHeight) {
            height = maximumHeight
            width = height / CARD_ASPECT
        }
        val left = ((gray.cols() - width) / 2.0).toInt().coerceAtLeast(0)
        val top = ((gray.rows() - height) / 2.0).toInt().coerceAtLeast(0)
        val rect = Rect(
            left,
            top,
            width.toInt().coerceAtLeast(1),
            height.toInt().coerceAtLeast(1),
        )
        val grayRegion = gray.submat(rect)
        val edgeRegion = edges.submat(rect)
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        return try {
            val edgeDensity = Core.countNonZero(edgeRegion).toDouble() / rect.area()
            Core.meanStdDev(grayRegion, mean, deviation)
            val brightness = mean.toArray().firstOrNull() ?: 0.0
            val contrast = deviation.toArray().firstOrNull() ?: 0.0
            if (edgeDensity < MIN_FALLBACK_EDGE_DENSITY || contrast < MIN_FALLBACK_CONTRAST) {
                return null
            }
            val points = listOf(
                Point(left.toDouble(), top.toDouble()),
                Point(left + width, top.toDouble()),
                Point(left + width, top + height),
                Point(left.toDouble(), top + height),
            )
            detectedRegion(
                bitmap = bitmap,
                scaledPoints = points,
                scale = scale,
                confidence = FALLBACK_CONFIDENCE,
                brightness = brightness,
            )
        } finally {
            grayRegion.release()
            edgeRegion.release()
            mean.release()
            deviation.release()
        }
    }

    private fun detectedRegion(
        bitmap: Bitmap,
        scaledPoints: List<Point>,
        scale: Double,
        confidence: Double,
        brightness: Double,
    ): DetectedCardRegion {
        val ordered = orderCorners(
            scaledPoints.map { point -> Point(point.x / scale, point.y / scale) },
        )
        return DetectedCardRegion(
            detection = CardDetection(
                corners = ordered.map { point ->
                    ScanPoint(
                        x = (point.x / bitmap.width).toFloat().coerceIn(0f, 1f),
                        y = (point.y / bitmap.height).toFloat().coerceIn(0f, 1f),
                    )
                },
                confidence = confidence.coerceIn(0.0, 1.0),
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
            ),
            sourceCorners = ordered,
            brightness = brightness,
        )
    }

    data class DetectedCardRegion(
        val detection: CardDetection,
        internal val sourceCorners: List<Point>,
        val brightness: Double,
    )

    private data class Candidate(
        val points: List<Point>,
        val score: Double,
        val mask: Mat,
    )

    private companion object {
        const val CARD_ASPECT = 88.0 / 63.0
        const val ASPECT_TOLERANCE = 0.42
        const val MIN_ASPECT = 1.05
        const val MAX_ASPECT = 1.88
        const val MIN_AREA_RATIO = 0.035
        const val MAX_AREA_RATIO = 0.88
        const val MIN_RECTANGULARITY = 0.52
        const val MAX_CORNER_COSINE = 0.62
        const val MIN_SCORE = 0.43
        const val MAX_ANALYSIS_EDGE = 720.0
        const val CARD_OUTPUT_WIDTH = 630
        const val CARD_OUTPUT_HEIGHT = 880
        const val FALLBACK_WIDTH_RATIO = 0.68
        const val FALLBACK_HEIGHT_RATIO = 0.76
        const val MIN_FALLBACK_EDGE_DENSITY = 0.018
        const val MIN_FALLBACK_CONTRAST = 18.0
        const val FALLBACK_CONFIDENCE = 0.32
        val APPROXIMATION_FACTORS = listOf(0.018, 0.028, 0.042, 0.06)
    }
}
