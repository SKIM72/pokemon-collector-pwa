package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CardMetadataFallbackRepository(
    private val client: OkHttpClient,
) {
    private val officialJapaneseCards = ConcurrentHashMap<String, List<OfficialJapaneseCard>>()
    private val officialJapaneseDetails = ConcurrentHashMap<String, String>()
    private val englishCards = ConcurrentHashMap<String, List<JSONObject>>()
    private val cardRushPriceRepository = CardRushPriceRepository(client)
    private val yuyuTeiPriceRepository = YuyuTeiPriceRepository(client)

    fun enrich(card: RecognizedCard): RecognizedCard {
        val sourceCard = if (card.priceSource == "estimated-rarity") {
            card.copy(
                marketPrice = null,
                priceSource = "price-unavailable",
            )
        } else {
            card
        }
        return when (sourceCard.language) {
            CardLanguage.JAPANESE -> {
                val withImage = addOfficialJapaneseImage(sourceCard)
                val withCardRush = cardRushPriceRepository.enrich(withImage)
                if (withCardRush.marketPrice == null) {
                    yuyuTeiPriceRepository.enrich(withCardRush)
                } else {
                    withCardRush
                }
            }
            CardLanguage.ENGLISH -> addPokemonTcgMetadata(sourceCard)
            CardLanguage.KOREAN -> sourceCard
        }
    }

    private fun addOfficialJapaneseImage(card: RecognizedCard): RecognizedCard {
        if (!needsProviderImage(card.imageUrl)) return card
        val results = officialJapaneseCards.computeIfAbsent(card.name) {
            searchOfficialJapaneseCards(card.name)
        }
        val setMatches = results.filter { candidate ->
            card.setId.isNotBlank() &&
                candidate.setId.equals(card.setId, ignoreCase = true)
        }
        val candidates = setMatches.ifEmpty {
            results.filter { candidate ->
                normalized(candidate.name) == normalized(card.name)
            }
        }
        val exact = when {
            candidates.size == 1 -> candidates.first()
            candidates.isEmpty() -> null
            else -> candidates.firstOrNull { candidate ->
                officialJapaneseDetails.computeIfAbsent(candidate.cardId) {
                    fetchOfficialJapaneseNumber(candidate.cardId)
                }.normalizedNumber() == card.number.normalizedNumber()
            }
        } ?: return card

        return card.copy(
            imageUrl = exact.imageUrl,
            imageHighUrl = exact.imageUrl,
        )
    }

    private fun searchOfficialJapaneseCards(name: String): List<OfficialJapaneseCard> {
        val url = "$JAPANESE_CARD_SITE/card-search/resultAPI.php"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", name)
            .addQueryParameter("regulation_header_search_item0", "all")
            .addQueryParameter("sm_and_keyword", "true")
            .addQueryParameter("illust", "")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use emptyList()
            val cards = JSONObject(body).optJSONArray("cardList") ?: JSONArray()
            buildList {
                for (index in 0 until cards.length()) {
                    val json = cards.optJSONObject(index) ?: continue
                    val cardId = json.optString("cardID")
                    val imagePath = json.optString("cardThumbFile")
                    if (cardId.isBlank() || imagePath.isBlank()) continue
                    add(
                        OfficialJapaneseCard(
                            cardId = cardId,
                            name = json.optString("cardNameAltText"),
                            setId = imagePath
                                .substringAfter("/large/", "")
                                .substringBefore('/'),
                            imageUrl = "$JAPANESE_CARD_SITE$imagePath",
                        ),
                    )
                }
            }
        }
    }

    private fun fetchOfficialJapaneseNumber(cardId: String): String {
        val request = Request.Builder()
            .url("$JAPANESE_CARD_SITE/card-search/details.php/card/$cardId/regu/all")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            val html = response.body?.string().orEmpty()
            OFFICIAL_NUMBER_REGEX.find(html)?.let { match ->
                "${match.groupValues[1]}/${match.groupValues[2]}"
            }.orEmpty()
        }
    }

    private fun addPokemonTcgMetadata(card: RecognizedCard): RecognizedCard {
        if (!needsProviderImage(card.imageUrl) && card.marketPrice != null) return card
        val cacheKey = "${card.name}:${card.number}"
        val results = englishCards.computeIfAbsent(cacheKey) {
            searchPokemonTcgCards(card)
        }
        val best = results.maxByOrNull { candidate ->
            pokemonTcgScore(candidate, card)
        } ?: return card
        if (pokemonTcgScore(best, card) < 4) return card

        val images = best.optJSONObject("images")
        val market = pokemonTcgPrice(best)
        return card.copy(
            imageUrl = card.imageUrl
                .takeUnless(::needsProviderImage)
                ?: images?.optString("small")?.takeIf(String::isNotBlank),
            imageHighUrl = card.imageHighUrl
                .takeUnless(::needsProviderImage)
                ?: images?.optString("large")?.takeIf(String::isNotBlank),
            marketPrice = card.marketPrice ?: market?.price,
            currency = if (card.marketPrice == null && market != null) {
                market.currency
            } else {
                card.currency
            },
            priceSource = if (card.marketPrice == null && market != null) {
                market.source
            } else {
                card.priceSource
            },
        )
    }

    private fun searchPokemonTcgCards(card: RecognizedCard): List<JSONObject> {
        val query = buildString {
            append("name:\"")
            append(card.name.replace("\"", ""))
            append('"')
            card.number.normalizedNumber().takeIf(String::isNotBlank)?.let {
                append(" number:")
                append(it)
            }
        }
        val url = "$POKEMON_TCG_API/v2/cards"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", "1")
            .addQueryParameter("pageSize", "20")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.let(::add)
                }
            }
        }
    }

    private fun pokemonTcgScore(
        candidate: JSONObject,
        card: RecognizedCard,
    ): Int {
        var score = 0
        if (normalized(candidate.optString("name")) == normalized(card.name)) score += 4
        if (candidate.optString("number").normalizedNumber() ==
            card.number.normalizedNumber()
        ) {
            score += 4
        }
        val setName = candidate.optJSONObject("set")?.optString("name").orEmpty()
        if (normalized(setName) == normalized(card.setName)) score += 3
        if (normalized(setName).contains(normalized(card.setName)) ||
            normalized(card.setName).contains(normalized(setName))
        ) {
            score += 1
        }
        return score
    }

    private fun pokemonTcgPrice(json: JSONObject): FallbackPrice? {
        val tcgplayer = json.optJSONObject("tcgplayer")?.optJSONObject("prices")
        if (tcgplayer != null) {
            val finishes = tcgplayer.keys().asSequence().toList()
            for (finish in finishes) {
                val values = tcgplayer.optJSONObject(finish) ?: continue
                val price = values.optPositiveDouble("market")
                    ?: values.optPositiveDouble("mid")
                if (price != null) {
                    return FallbackPrice(price, "USD", "pokemon-tcg-api")
                }
            }
        }
        val cardmarket = json.optJSONObject("cardmarket")?.optJSONObject("prices")
        val price = cardmarket?.optPositiveDouble("trendPrice")
            ?: cardmarket?.optPositiveDouble("averageSellPrice")
        return price?.let {
            FallbackPrice(it, "EUR", "pokemon-tcg-api-cardmarket")
        }
    }

    private data class OfficialJapaneseCard(
        val cardId: String,
        val name: String,
        val setId: String,
        val imageUrl: String,
    )

    private data class FallbackPrice(
        val price: Double,
        val currency: String,
        val source: String,
    )

    private companion object {
        const val JAPANESE_CARD_SITE = "https://www.pokemon-card.com"
        const val POKEMON_TCG_API = "https://api.pokemontcg.io"
        const val USER_AGENT = "PokeBinder/0.9 (personal collection app)"
        val OFFICIAL_NUMBER_REGEX = Regex(
            """&nbsp;\s*(\d{1,4})\s*&nbsp;\s*/\s*&nbsp;\s*(\d{1,4})""",
        )

        fun normalized(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s'’._-]+"""), "")

        fun String.normalizedNumber(): String =
            substringBefore('/').trim().trimStart('0').ifBlank { "0" }

        fun needsProviderImage(value: String?): Boolean =
            value.isNullOrBlank() ||
                value.startsWith("file:") ||
                value.contains("/card-scans/")

        fun JSONObject.optPositiveDouble(name: String): Double? =
            optDouble(name).takeUnless { it.isNaN() || it <= 0.0 }
    }
}
