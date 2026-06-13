package com.pokebinder.scanner.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlin.math.roundToInt

data class CardImageFingerprint(
    val embedding: FloatArray,
    val perceptualHash: String,
)

class CardImageEmbedder(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private var imageEmbedder: ImageEmbedder? = null

    @Synchronized
    fun embed(jpegBytes: ByteArray): CardImageFingerprint {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: error("촬영 이미지를 읽을 수 없습니다.")
        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        }

        try {
            val result = getOrCreateEmbedder()
                .embed(BitmapImageBuilder(argbBitmap).build())
                .embeddingResult()
                .embeddings()
                .firstOrNull()
                ?: error("이미지 특징값을 만들 수 없습니다.")
            val embedding = result.floatEmbedding()
            check(embedding.size == EMBEDDING_SIZE) {
                "이미지 모델 출력 크기가 올바르지 않습니다. (${embedding.size})"
            }
            return CardImageFingerprint(
                embedding = embedding,
                perceptualHash = differenceHash(argbBitmap),
            )
        } finally {
            argbBitmap.recycle()
        }
    }

    @Synchronized
    override fun close() {
        imageEmbedder?.close()
        imageEmbedder = null
    }

    private fun getOrCreateEmbedder(): ImageEmbedder {
        imageEmbedder?.let { return it }

        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MODEL_PATH)
            .build()
        val options = ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .setQuantize(false)
            .build()
        return ImageEmbedder.createFromOptions(applicationContext, options)
            .also { imageEmbedder = it }
    }

    private fun differenceHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true)
        try {
            var hash = 0UL
            var bitIndex = 0
            for (y in 0 until HASH_HEIGHT) {
                for (x in 0 until HASH_WIDTH - 1) {
                    val left = luminance(scaled.getPixel(x, y))
                    val right = luminance(scaled.getPixel(x + 1, y))
                    if (left > right) hash = hash or (1UL shl bitIndex)
                    bitIndex += 1
                }
            }
            return hash.toString(16).padStart(16, '0')
        } finally {
            scaled.recycle()
        }
    }

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return (red * 0.299 + green * 0.587 + blue * 0.114).roundToInt()
    }

    companion object {
        const val EMBEDDING_SIZE = 1024
        private const val MODEL_PATH = "mobilenet_v3_small.tflite"
        private const val HASH_WIDTH = 9
        private const val HASH_HEIGHT = 8
    }
}
