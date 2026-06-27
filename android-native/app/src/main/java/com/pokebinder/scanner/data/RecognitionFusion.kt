package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.RecognitionOutcome
import com.pokebinder.scanner.model.RecognizedCard
import java.util.Locale

data class RecognitionDecision(
    val outcome: RecognitionOutcome,
    val path: String,
)

object RecognitionFusion {
    private const val MIN_OFFICIAL_CONFIDENCE = 0.60
    private const val STRONG_TEXT_CONFIDENCE = 0.82
    private const val MIN_TEXT_CONFIDENCE = 0.52
    private const val STRONG_IMAGE_CONFIDENCE = 0.78
    private const val MIN_IMAGE_CONFIDENCE = 0.68
    private const val MIN_OFFICIAL_INDEX_CONFIDENCE = 0.53
    private const val MIN_OFFICIAL_INDEX_MARGIN = 0.025

    fun resolve(
        image: RecognitionOutcome,
        text: RecognitionOutcome,
        official: RecognitionOutcome = RecognitionOutcome.NoMatch,
    ): RecognitionDecision {
        val imageMatch = image as? RecognitionOutcome.Match
        val textMatch = text as? RecognitionOutcome.Match
        val officialMatch = official as? RecognitionOutcome.Match

        if (officialMatch != null &&
            officialMatch.card.confidence >= MIN_OFFICIAL_CONFIDENCE
        ) {
            return decision(
                officialMatch.card,
                officialMatch,
                textMatch,
                imageMatch,
                path = "official-ja",
            )
        }

        if (imageMatch != null && isConfidentOfficialIndexMatch(imageMatch)) {
            return decision(
                imageMatch.card,
                imageMatch,
                textMatch,
                path = "official-index",
            )
        }

        if (textMatch != null && textMatch.card.confidence >= STRONG_TEXT_CONFIDENCE) {
            return decision(textMatch.card, textMatch, imageMatch, path = "ocr-name-number")
        }

        if (imageMatch != null && imageMatch.card.confidence >= STRONG_IMAGE_CONFIDENCE) {
            val conflictsWithText = textMatch != null &&
                !sameNormalizedName(imageMatch.card, textMatch.card)
            val textClearlyWins = conflictsWithText &&
                (textMatch?.card?.confidence ?: 0.0) + 0.18 >
                imageMatch.card.confidence
            if (!textClearlyWins) {
                return decision(imageMatch.card, imageMatch, textMatch, path = "image-strong")
            }
        }

        if (textMatch != null && textMatch.card.confidence >= MIN_TEXT_CONFIDENCE) {
            return decision(textMatch.card, textMatch, imageMatch, path = "ocr-name")
        }

        if (imageMatch != null && imageMatch.card.confidence >= MIN_IMAGE_CONFIDENCE) {
            return decision(imageMatch.card, imageMatch, path = "image-threshold")
        }

        val unavailable = listOf(image, text, official)
            .filterIsInstance<RecognitionOutcome.Unavailable>()
            .firstOrNull()
        return RecognitionDecision(
            outcome = unavailable ?: RecognitionOutcome.NoMatch,
            path = if (unavailable == null) "no-confident-match" else "recognition-unavailable",
        )
    }

    private fun decision(
        selected: RecognizedCard,
        vararg matches: RecognitionOutcome.Match?,
        path: String,
    ): RecognitionDecision {
        val candidates = buildList {
            add(selected)
            matches.filterNotNull().forEach { match ->
                add(match.card)
                addAll(match.candidates)
            }
        }
            .distinctBy(::cardKey)
            .sortedWith(
                compareByDescending<RecognizedCard> { cardKey(it) == cardKey(selected) }
                    .thenByDescending(RecognizedCard::confidence),
            )
            .take(8)
        return RecognitionDecision(
            outcome = RecognitionOutcome.Match(selected, candidates),
            path = path,
        )
    }

    private fun sameNormalizedName(
        first: RecognizedCard,
        second: RecognizedCard,
    ): Boolean = normalizeName(first.name) == normalizeName(second.name)

    private fun cardKey(card: RecognizedCard): String =
        "${card.source}:${card.language.code}:${card.id}"

    private fun isConfidentOfficialIndexMatch(
        match: RecognitionOutcome.Match,
    ): Boolean {
        if (
            match.card.source != "pokemon-card-official" ||
            match.card.confidence < MIN_OFFICIAL_INDEX_CONFIDENCE
        ) {
            return false
        }
        val runnerUp = match.candidates
            .asSequence()
            .filter { cardKey(it) != cardKey(match.card) }
            .maxOfOrNull(RecognizedCard::confidence)
            ?: 0.0
        return match.card.confidence - runnerUp >= MIN_OFFICIAL_INDEX_MARGIN
    }

    private fun normalizeName(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""[\s　'’._\-]+"""), "")
}
