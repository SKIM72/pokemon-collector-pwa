package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuyuTeiPriceRepositoryTest {
    @Test
    fun buildsYuyuTeiUrlFromJapaneseSetAndCardNumber() {
        val card = mawileCard()

        assertEquals(
            "https://yuyu-tei.jp/sell/poc/card/m03/10031",
            YuyuTeiPriceRepository.yuyuUrl(card),
        )
    }

    @Test
    fun parsesProductJsonLdPriceAndImage() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@context":"http://schema.org","@type":"Product","name":"C クチート",
            "image":"https://card.yuyu-tei.jp/poc/front/m03/10031.jpg",
            "description":"031/080",
            "offers":{"@type":"Offer","price":"30","priceCurrency":"JPY"}}
            </script>
            </head></html>
        """.trimIndent()

        val price = YuyuTeiPriceRepository(OkHttpClient())
            .parseProductPrice(html, mawileCard())

        assertEquals(30.0, price?.price ?: 0.0, 0.0)
        assertEquals("https://card.yuyu-tei.jp/poc/front/m03/10031.jpg", price?.imageUrl)
    }

    @Test
    fun rejectsDifferentCardNumbers() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"C クチート","description":"032/080",
            "offers":{"price":"30","priceCurrency":"JPY"}}
            </script>
        """.trimIndent()

        val price = YuyuTeiPriceRepository(OkHttpClient())
            .parseProductPrice(html, mawileCard())

        assertTrue(price == null)
    }

    private fun mawileCard(): RecognizedCard = RecognizedCard(
        id = "M3-031",
        name = "クチート",
        setName = "ムニキスゼロ",
        number = "031",
        imageUrl = null,
        marketPrice = null,
        currency = "JPY",
        confidence = 1.0,
        language = CardLanguage.JAPANESE,
        setId = "M3",
    )
}
