package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class YuyuTeiPriceRepository(
    private val client: OkHttpClient,
) {
    private val priceCache = mutableMapOf<String, YuyuPrice>()

    fun enrich(card: RecognizedCard): RecognizedCard {
        if (card.language != CardLanguage.JAPANESE) return card
        val url = yuyuUrl(card) ?: return card
        val price = synchronized(priceCache) { priceCache[url] }
            ?: fetchPrice(url, card)?.also { fetched ->
                synchronized(priceCache) { priceCache[url] = fetched }
            }
            ?: return card

        return card.copy(
            imageUrl = card.imageUrl ?: price.imageUrl,
            imageHighUrl = card.imageHighUrl ?: price.imageUrl,
            marketPrice = price.price,
            currency = "JPY",
            priceSource = "yuyu-tei",
        )
    }

    private fun fetchPrice(
        url: String,
        card: RecognizedCard,
    ): YuyuPrice? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "ja-JP,ja;q=0.9,ko;q=0.8,en;q=0.7")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val html = response.body?.string().orEmpty()
            parseProductPrice(html, card)
        }
    }

    internal fun parseProductPrice(
        html: String,
        card: RecognizedCard,
    ): YuyuPrice? {
        val expectedNumber = normalizedNumber(card.number)
        JSON_LD_REGEX.findAll(html).forEach { match ->
            val rawJson = match.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
            if (jsonStringField(rawJson, "@type") != "Product") return@forEach
            val name = jsonStringField(rawJson, "name")
            val description = jsonStringField(rawJson, "description")
            if (!isPlausibleCardMatch(name, description, card.name, expectedNumber)) {
                return@forEach
            }
            val price = jsonStringField(rawJson, "price")
                .replace(",", "")
                .toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?: return@forEach
            val currency = jsonStringField(rawJson, "priceCurrency")
            if (currency.isNotBlank() && currency != "JPY") return@forEach
            return YuyuPrice(
                price = price,
                imageUrl = jsonStringField(rawJson, "image").takeIf { it.isNotBlank() },
            )
        }
        return parseVisiblePrice(html, card)
    }

    private fun parseVisiblePrice(
        html: String,
        card: RecognizedCard,
    ): YuyuPrice? {
        val expectedNumber = normalizedNumber(card.number)
        val title = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val number = NUMBER_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
        if (!isPlausibleCardMatch(title, number, card.name, expectedNumber)) return null
        val price = PRICE_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: return null
        val imageUrl = IMAGE_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
        return YuyuPrice(price = price, imageUrl = imageUrl)
    }

    internal data class YuyuPrice(
        val price: Double,
        val imageUrl: String?,
    )

    internal companion object {
        const val BASE_URL = "https://yuyu-tei.jp"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36 PokeBinder/0.11"
        val JSON_LD_REGEX = Regex(
            """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val TITLE_REGEX = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
        val NUMBER_REGEX = Regex(""">(\d{1,4})\s*/\s*\d{1,4}<""")
        val IMAGE_REGEX = Regex("""<img[^>]+class=["'][^"']*\bvimg\b[^"']*["'][^>]+src=["']([^"']+)["']""")
        val PRICE_REGEX = Regex("""<h4[^>]*>\s*([0-9,]+)\s*円\s*</h4>""")
        val JSON_STRING_TEMPLATE = """"%s"\s*:\s*"([^"]*)""""

        fun yuyuUrl(card: RecognizedCard): String? {
            val setCode = yuyuSetCode(card.setId.ifBlank {
                card.id.substringBefore('-', "")
            })
                .ifBlank { return null }
            val localNumber = normalizedNumber(card.number).toIntOrNull() ?: return null
            return "$BASE_URL/sell/poc/card/$setCode/${10_000 + localNumber}"
        }

        fun yuyuSetCode(rawValue: String): String {
            val lowercase = rawValue.lowercase(Locale.ROOT)
            val match = Regex("""^([a-z]+)(\d{1,2})$""").matchEntire(lowercase)
                ?: return lowercase
            return match.groupValues[1] + match.groupValues[2].padStart(2, '0')
        }

        fun isPlausibleCardMatch(
            titleOrName: String,
            descriptionOrNumber: String,
            cardName: String,
            expectedNumber: String,
        ): Boolean {
            val title = normalized(titleOrName)
            val name = normalized(cardName)
            val titleMatches = title.contains(name) || name.contains(title)
            val number = normalizedNumber(descriptionOrNumber)
            val numberMatches = expectedNumber.isBlank() ||
                number.isBlank() ||
                number == expectedNumber
            return titleMatches && numberMatches
        }

        fun normalized(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""[\s　'’._\-|［\]\[\]（）()]+"""), "")
            .replace(Regex("""^[a-z]{1,4}"""), "")

        fun normalizedNumber(value: String): String =
            value.substringBefore('/')
                .filter(Char::isDigit)
                .trimStart('0')
                .ifBlank { "" }

        fun jsonStringField(
            json: String,
            name: String,
        ): String {
            val escapedName = Regex.escape(name)
            return Regex(JSON_STRING_TEMPLATE.format(escapedName))
                .find(json)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                .orEmpty()
        }
    }
}
