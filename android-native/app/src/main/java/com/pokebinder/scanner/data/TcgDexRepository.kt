package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class TcgDexRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun search(
        query: String,
        language: CardLanguage,
    ): List<RecognizedCard> = withContext(Dispatchers.IO) {
        val variants = QueryAliases.expand(query, language).take(5)
        coroutineScope {
            variants.map { variant ->
                async { searchVariant(variant, language) }
            }.awaitAll()
                .flatten()
                .distinctBy { "${it.source}:${it.language.code}:${it.id}" }
                .sortedWith(
                    compareByDescending<RecognizedCard> {
                        normalized(it.name).contains(normalized(query))
                    }.thenBy { it.number.padStart(5, '0') },
                )
                .take(48)
        }
    }

    suspend fun fetchCard(card: RecognizedCard): RecognizedCard =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$API/${card.language.code}/cards/${card.id}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext card
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext card
                parseDetailed(JSONObject(body), card.language) ?: card
            }
        }

    private fun searchVariant(
        query: String,
        language: CardLanguage,
    ): List<RecognizedCard> {
        val url = "$API/${language.code}/cards"
            .toHttpUrl()
            .newBuilder()
            .apply {
                val number = query.trim().takeIf { value ->
                    value.matches(Regex("""\d{1,4}(/[A-Za-z0-9]+)?"""))
                }
                if (number == null) {
                    addQueryParameter("name", query.trim())
                } else {
                    addQueryParameter("localId", number.substringBefore('/'))
                }
                addQueryParameter("pagination:page", "1")
                addQueryParameter("pagination:itemsPerPage", "24")
            }
            .build()
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use emptyList()
            val array = JSONArray(body)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)
                        ?.let { parseSummary(it, language) }
                        ?.let(::add)
                }
            }
        }
    }

    private fun parseSummary(
        json: JSONObject,
        language: CardLanguage,
    ): RecognizedCard? {
        val id = json.optString("id")
        val name = json.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        val image = json.optString("image").takeIf { it.isNotBlank() }
        return RecognizedCard(
            id = id,
            name = name,
            setName = "상세 정보 확인",
            number = json.optString("localId", "-"),
            imageUrl = image?.let { "$it/low.webp" },
            marketPrice = null,
            currency = defaultCurrency(language),
            priceSource = "tcgdex",
            confidence = 1.0,
            language = language,
            source = "tcgdex",
            imageHighUrl = image?.let { "$it/high.webp" },
        )
    }

    private fun parseDetailed(
        json: JSONObject,
        language: CardLanguage,
    ): RecognizedCard? {
        val summary = parseSummary(json, language) ?: return null
        val set = json.optJSONObject("set")
        val price = pickPrice(json.optJSONObject("pricing"), language)
        return summary.copy(
            setId = set?.optString("id").orEmpty(),
            setName = set?.optString("name").orEmpty().ifBlank { "세트 정보 없음" },
            rarity = json.optString("rarity"),
            marketPrice = price?.price,
            currency = price?.currency ?: defaultCurrency(language),
            priceSource = price?.source ?: "tcgdex",
        )
    }

    private fun pickPrice(
        pricing: JSONObject?,
        language: CardLanguage,
    ): MarketPrice? {
        if (pricing == null) return null
        val tcgplayer = pricing.optJSONObject("tcgplayer")
        if (language == CardLanguage.ENGLISH && tcgplayer != null) {
            val finishOrder = listOf("holofoil", "normal", "reverse-holofoil", "1st-edition-holofoil")
            for (finish in finishOrder) {
                val values = tcgplayer.optJSONObject(finish) ?: continue
                val value = values.optDouble("marketPrice")
                    .takeUnless { it.isNaN() || it <= 0.0 }
                    ?: values.optDouble("midPrice").takeUnless { it.isNaN() || it <= 0.0 }
                if (value != null) return MarketPrice(value, "USD", "tcgplayer")
            }
        }

        val cardmarket = pricing.optJSONObject("cardmarket") ?: return null
        val value = cardmarket.optDouble("trend")
            .takeUnless { it.isNaN() || it <= 0.0 }
            ?: cardmarket.optDouble("avg30").takeUnless { it.isNaN() || it <= 0.0 }
            ?: cardmarket.optDouble("avg").takeUnless { it.isNaN() || it <= 0.0 }
        return value?.let {
            MarketPrice(it, cardmarket.optString("unit", "EUR"), "cardmarket")
        }
    }

    private companion object {
        const val API = "https://api.tcgdex.net/v2"

        fun defaultCurrency(language: CardLanguage): String = when (language) {
            CardLanguage.JAPANESE -> "JPY"
            CardLanguage.KOREAN -> "KRW"
            CardLanguage.ENGLISH -> "USD"
        }

        fun normalized(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(" ", "")
    }

    private data class MarketPrice(
        val price: Double,
        val currency: String,
        val source: String,
    )
}

internal object QueryAliases {
    private val groups = listOf(
        listOf("피카츄", "ピカチュウ", "Pikachu"),
        listOf("리자몽", "リザードン", "Charizard"),
        listOf("파이리", "ヒトカゲ", "Charmander"),
        listOf("리자드", "リザード", "Charmeleon"),
        listOf("이상해씨", "フシギダネ", "Bulbasaur"),
        listOf("이상해꽃", "フシギバナ", "Venusaur"),
        listOf("꼬부기", "ゼニガメ", "Squirtle"),
        listOf("거북왕", "カメックス", "Blastoise"),
        listOf("뮤", "ミュウ", "Mew"),
        listOf("뮤츠", "ミュウツー", "Mewtwo"),
        listOf("이브이", "イーブイ", "Eevee"),
        listOf("샤미드", "シャワーズ", "Vaporeon"),
        listOf("쥬피썬더", "サンダース", "Jolteon"),
        listOf("부스터", "ブースター", "Flareon"),
        listOf("에브이", "エーフィ", "Espeon"),
        listOf("블래키", "ブラッキー", "Umbreon"),
        listOf("리피아", "リーフィア", "Leafeon"),
        listOf("글레이시아", "グレイシア", "Glaceon"),
        listOf("님피아", "ニンフィア", "Sylveon"),
        listOf("루카리오", "ルカリオ", "Lucario"),
        listOf("가디안", "サーナイト", "Gardevoir"),
        listOf("팬텀", "ゲンガー", "Gengar"),
        listOf("망나뇽", "カイリュー", "Dragonite"),
        listOf("잠만보", "カビゴン", "Snorlax"),
        listOf("갸라도스", "ギャラドス", "Gyarados"),
        listOf("라프라스", "ラプラス", "Lapras"),
        listOf("레쿠쟈", "レックウザ", "Rayquaza"),
        listOf("가이오가", "カイオーガ", "Kyogre"),
        listOf("그란돈", "グラードン", "Groudon"),
        listOf("아르세우스", "アルセウス", "Arceus"),
        listOf("기라티나", "ギラティナ", "Giratina"),
        listOf("디아루가", "ディアルガ", "Dialga"),
        listOf("펄기아", "パルキア", "Palkia"),
        listOf("다크라이", "ダークライ", "Darkrai"),
        listOf("세레비", "セレビィ", "Celebi"),
        listOf("지라치", "ジラーチ", "Jirachi"),
        listOf("테라파고스", "テラパゴス", "Terapagos"),
        listOf("오거폰", "オーガポン", "Ogerpon"),
        listOf("마스카나", "マスカーニャ", "Meowscarada"),
        listOf("코라이돈", "コライドン", "Koraidon"),
        listOf("미라이돈", "ミライドン", "Miraidon"),
    )
    private val suffixes = listOf("ex", "VMAX", "VSTAR", "GX", "V")

    fun expand(
        query: String,
        language: CardLanguage,
    ): List<String> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val normalizedQuery = normalize(trimmed)
        val group = groups.firstOrNull { names ->
            names.any { normalizedQuery.contains(normalize(it)) }
        } ?: return listOf(trimmed)
        val suffix = suffixes.firstOrNull {
            trimmed.replace(" ", "").endsWith(it, ignoreCase = true)
        }.orEmpty()
        val preferredIndex = when (language) {
            CardLanguage.KOREAN -> 0
            CardLanguage.JAPANESE -> 1
            CardLanguage.ENGLISH -> 2
        }
        return buildList {
            add(group[preferredIndex] + suffix)
            add(trimmed)
            group.forEach { name ->
                add(name + suffix)
                if (suffix.isNotBlank()) add("$name $suffix")
            }
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(" ", "")
}
