package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardMetadataFallbackRepositoryTest {
    @Test
    fun removesLegacyEstimatedPriceWhenNoRealProviderPriceExists() {
        val card = RecognizedCard(
            id = "legacy",
            name = "피카츄",
            setName = "test",
            number = "001",
            imageUrl = null,
            marketPrice = 300.0,
            currency = "KRW",
            priceSource = "estimated-rarity",
            confidence = 1.0,
            language = CardLanguage.KOREAN,
        )

        val enriched = CardMetadataFallbackRepository(OkHttpClient()).enrich(card)

        assertNull(enriched.marketPrice)
        assertEquals("price-unavailable", enriched.priceSource)
    }
}
