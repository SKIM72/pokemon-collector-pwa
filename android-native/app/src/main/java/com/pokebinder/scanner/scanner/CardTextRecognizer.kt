package com.pokebinder.scanner.scanner

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.CardTextHints
import kotlinx.coroutines.tasks.await

class CardTextRecognizer : AutoCloseable {
    private val recognizers = mutableMapOf<CardLanguage, TextRecognizer>()

    suspend fun recognize(
        jpegBytes: ByteArray,
        language: CardLanguage,
    ): CardTextHints {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return CardTextHints(emptyList(), null)
        return try {
            val result = recognizer(language)
                .process(InputImage.fromBitmap(bitmap, 0))
                .await()
            val lines = result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    TextLine(
                        text = line.text.trim(),
                        top = bounds.top,
                        height = bounds.height(),
                    )
                }

            val names = lines.asSequence()
                .filter { it.top < bitmap.height * NAME_REGION_RATIO }
                .filter { isLikelyName(it.text, language) }
                .sortedWith(compareBy<TextLine> { it.top }.thenByDescending { it.height })
                .map { cleanName(it.text) }
                .filter { it.length >= 2 }
                .distinct()
                .take(MAX_NAME_CANDIDATES)
                .toList()

            CardTextHints(
                names = names,
                localId = CARD_NUMBER.find(result.text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.padStart(3, '0'),
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized
    override fun close() {
        recognizers.values.forEach(TextRecognizer::close)
        recognizers.clear()
    }

    @Synchronized
    private fun recognizer(language: CardLanguage): TextRecognizer =
        recognizers.getOrPut(language) {
            when (language) {
                CardLanguage.JAPANESE -> TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build(),
                )
                CardLanguage.KOREAN -> TextRecognition.getClient(
                    KoreanTextRecognizerOptions.Builder().build(),
                )
                CardLanguage.ENGLISH -> TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS,
                )
            }
        }

    private fun isLikelyName(
        value: String,
        language: CardLanguage,
    ): Boolean {
        val cleaned = cleanName(value)
        if (cleaned.length !in 2..28) return false
        if (cleaned.contains("HP", ignoreCase = true)) return false
        if (IGNORED_NAME_PARTS.any { cleaned.contains(it, ignoreCase = true) }) return false
        if (cleaned.count(Char::isDigit) > cleaned.length / 3) return false
        return when (language) {
            CardLanguage.JAPANESE -> cleaned.any {
                it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf'
            }
            CardLanguage.KOREAN -> cleaned.any { it in '\uac00'..'\ud7a3' }
            CardLanguage.ENGLISH -> cleaned.count(Char::isLetter) >= 3
        }
    }

    private fun cleanName(value: String): String = value
        .replace(HP_VALUE, "")
        .replace(LEADING_LABEL, "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '·', '.', '-', '_', ':')

    private data class TextLine(
        val text: String,
        val top: Int,
        val height: Int,
    )

    private companion object {
        const val NAME_REGION_RATIO = 0.42f
        const val MAX_NAME_CANDIDATES = 4
        val CARD_NUMBER = Regex("""(?<!\d)(\d{1,3})\s*[/／]\s*\d{2,3}""")
        val HP_VALUE = Regex("""(?i)HP\s*\d{1,3}""")
        val LEADING_LABEL = Regex("""(?i)^(BASIC|STAGE\s*[12]|たね|1進化|2進化)\s*""")
        val IGNORED_NAME_PARTS = listOf(
            "TRAINER",
            "ENERGY",
            "ポケモン",
            "トレーナーズ",
            "エネルギー",
            "약점",
            "저항력",
            "후퇴",
        )
    }
}
