package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CardRushPriceRepository(
    private val client: OkHttpClient,
) {
    private val cache = ConcurrentHashMap<String, CardRushListing?>()

    fun enrich(card: RecognizedCard): RecognizedCard {
        if (card.language != CardLanguage.JAPANESE) return card
        val number = normalizedNumber(card.number)
        if (card.name.isBlank() || number.isBlank()) return card
        val cacheKey = "${normalized(card.name)}:$number:${normalized(card.setId)}"
        val listing = cache.computeIfAbsent(cacheKey) {
            search(card)
        } ?: return card
        return card.copy(
            imageUrl = card.imageUrl ?: listing.imageUrl,
            imageHighUrl = card.imageHighUrl ?: listing.imageUrl,
            marketPrice = listing.price,
            currency = "JPY",
            priceSource = "cardrush",
        )
    }

    private fun search(card: RecognizedCard): CardRushListing? {
        val query = "${card.name} ${card.number}".trim()
        val url = "$BASE_URL/product-list"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ja-JP,ja;q=0.9")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            parseSearchResults(response.body?.string().orEmpty(), card).firstOrNull()
        }
    }

    internal fun parseSearchResults(
        html: String,
        card: RecognizedCard,
    ): List<CardRushListing> {
        if (html.isBlank()) return emptyList()
        val expectedName = normalized(card.name)
        val expectedNumber = normalizedNumber(card.number)
        val expectedSet = normalized(card.setId)
        return html.split(ITEM_MARKER)
            .drop(1)
            .mapNotNull { block ->
                val title = ALT_TEXT.find(block)?.groupValues?.getOrNull(1)
                    ?.decodeHtml()
                    .orEmpty()
                if (title.isBlank() || isExcludedCondition(title)) return@mapNotNull null
                if (!normalized(title).contains(expectedName)) return@mapNotNull null
                val listedNumber = CARD_NUMBER.find(title)?.groupValues?.getOrNull(1)
                    ?: return@mapNotNull null
                if (normalizedNumber(listedNumber) != expectedNumber) return@mapNotNull null
                val listedSet = SET_CODE.find(block)?.groupValues?.getOrNull(1).orEmpty()
                if (
                    expectedSet.isNotBlank() &&
                    listedSet.isNotBlank() &&
                    normalized(listedSet) != expectedSet
                ) {
                    return@mapNotNull null
                }
                val price = PRICE.find(block)?.groupValues?.getOrNull(1)
                    ?.replace(",", "")
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                    ?: return@mapNotNull null
                CardRushListing(
                    title = title,
                    price = price,
                    imageUrl = IMAGE_URL.find(block)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.decodeHtml(),
                )
            }
            .sortedBy(CardRushListing::price)
    }

    internal data class CardRushListing(
        val title: String,
        val price: Double,
        val imageUrl: String?,
    )

    private companion object {
        const val BASE_URL = "https://www.cardrush-pokemon.jp"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36 PokeBinder/0.16"
        const val ITEM_MARKER = "<div class=\"item_data\""
        val ALT_TEXT = Regex("""<img[^>]+alt=["']([^"']+)["']""")
        val IMAGE_URL = Regex("""<img[^>]+src=["']([^"']+)["']""")
        val CARD_NUMBER = Regex("""[<{〈｛]\s*(\d{1,4}(?:\s*/\s*\d{1,4})?)\s*[>}〉｝]""")
        val SET_CODE = Regex("""model_number_value["'][^>]*>(?:\[[^]]+])?([^<\]]+)""")
        val PRICE = Regex("""figure["'][^>]*>\s*([0-9,]+)\s*円""")
        val EXCLUDED_CONDITIONS = listOf(
            "状態",
            "PSA",
            "BGS",
            "CGC",
            "鑑定",
            "傷",
            "難",
        )

        fun isExcludedCondition(value: String): Boolean =
            EXCLUDED_CONDITIONS.any { value.contains(it, ignoreCase = true) }

        fun normalized(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""[\s　'’._\-|［\]\[\]（）()【】{}｛｝〈〉<>]+"""), "")

        fun normalizedNumber(value: String): String =
            value.substringBefore('/')
                .filter(Char::isDigit)
                .trimStart('0')

        fun String.decodeHtml(): String = replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
