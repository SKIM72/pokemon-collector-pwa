package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class CardRushPriceRepositoryTest {
    private val repository = CardRushPriceRepository(OkHttpClient())

    @Test
    fun selectsUngradedExactCardAndRejectsConditionVariants() {
        val html = """
            <div class="item_data" data-product-id="75875">
              <img src="https://example.com/a.jpg"
                alt="ピカチュウex(SAR仕様)【-】{764/742}" />
              <span class="model_number_value">MC</span>
              <span class="figure">298,000円</span>
            </div>
            <div class="item_data" data-product-id="76313">
              <img src="https://example.com/b.jpg"
                alt="〔状態A-〕ピカチュウex(SAR仕様)【-】{764/742}" />
              <span class="model_number_value">[状態A-]MC</span>
              <span class="figure">278,000円</span>
            </div>
            <div class="item_data" data-product-id="other">
              <img src="https://example.com/c.jpg"
                alt="ピカチュウex(ノーマル仕様)【-】{227/742}" />
              <span class="model_number_value">MC</span>
              <span class="figure">780円</span>
            </div>
        """.trimIndent()

        val listings = repository.parseSearchResults(html, card())

        assertEquals(1, listings.size)
        assertEquals(298_000.0, listings.single().price, 0.0)
        assertEquals("https://example.com/a.jpg", listings.single().imageUrl)
    }

    private fun card() = RecognizedCard(
        id = "50032",
        name = "ピカチュウex",
        setName = "スタートデッキ100 バトルコレクション",
        number = "764/742",
        imageUrl = null,
        marketPrice = null,
        currency = "JPY",
        confidence = 0.9,
        language = CardLanguage.JAPANESE,
        source = "pokemon-card-official",
        setId = "MC",
    )
}
