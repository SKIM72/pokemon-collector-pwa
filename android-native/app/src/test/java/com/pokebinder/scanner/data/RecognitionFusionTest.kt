package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognitionOutcome
import com.pokebinder.scanner.model.RecognizedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionFusionTest {
    @Test
    fun lowImageScoreDoesNotOverrideExactTextName() {
        val image = match(card("scyther", "ストライク", 0.58))
        val text = match(card("pikachu", "ピカチュウex", 0.58))

        val decision = RecognitionFusion.resolve(image, text)

        assertEquals("ocr-name", decision.path)
        assertEquals("pikachu", (decision.outcome as RecognitionOutcome.Match).card.id)
    }

    @Test
    fun officialJapaneseImageMatchHasPriority() {
        val image = match(card("scyther", "ストライク", 0.73))
        val text = match(card("pikachu-summary", "ピカチュウex", 0.58))
        val official = match(
            card(
                id = "48943",
                name = "ピカチュウex",
                confidence = 0.87,
                source = "pokemon-card-official",
            ),
        )

        val decision = RecognitionFusion.resolve(image, text, official)

        assertEquals("official-ja", decision.path)
        val match = decision.outcome as RecognitionOutcome.Match
        assertEquals("48943", match.card.id)
        assertTrue(match.candidates.any { it.id == "pikachu-summary" })
    }

    @Test
    fun weakImageOnlyResultIsRejected() {
        val decision = RecognitionFusion.resolve(
            image = match(card("wrong", "ストライク", 0.58)),
            text = RecognitionOutcome.NoMatch,
        )

        assertEquals("no-confident-match", decision.path)
        assertEquals(RecognitionOutcome.NoMatch, decision.outcome)
    }

    private fun match(card: RecognizedCard): RecognitionOutcome.Match =
        RecognitionOutcome.Match(card, listOf(card))

    private fun card(
        id: String,
        name: String,
        confidence: Double,
        source: String = "tcgdex",
    ) = RecognizedCard(
        id = id,
        name = name,
        setName = "test",
        number = "001",
        imageUrl = null,
        marketPrice = null,
        currency = "JPY",
        confidence = confidence,
        language = CardLanguage.JAPANESE,
        source = source,
    )
}
