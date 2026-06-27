package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.CardTextHints
import com.pokebinder.scanner.model.RecognitionOutcome
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.scanner.CardImageEmbedder
import com.pokebinder.scanner.scanner.CardImageFingerprint
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
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OfficialJapaneseScanRepository(
    private val imageEmbedder: CardImageEmbedder,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val searchCache = ConcurrentHashMap<String, List<OfficialCard>>()
    private val numberCache = ConcurrentHashMap<String, String>()
    private val embeddingCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, FloatArray>(40, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, FloatArray>,
            ): Boolean = size > MAX_EMBEDDING_CACHE
        },
    )
    private val yuyuTeiPriceRepository = YuyuTeiPriceRepository(client)

    suspend fun recognize(
        query: CardImageFingerprint,
        hints: CardTextHints,
    ): RecognitionOutcome = withContext(Dispatchers.IO) {
        val names = hints.names
            .map(::cleanName)
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_NAMES)
        if (names.isEmpty()) return@withContext RecognitionOutcome.NoMatch

        val cards = coroutineScope {
            names.map { name ->
                async {
                    searchCache.computeIfAbsent(normalized(name)) {
                        searchOfficial(name)
                    }
                }
            }.awaitAll().flatten()
        }
            .distinctBy(OfficialCard::id)
            .filter { candidate ->
                names.any { normalized(it) == normalized(candidate.name) }
            }
            .take(MAX_IMAGE_CANDIDATES)
        if (cards.isEmpty()) return@withContext RecognitionOutcome.NoMatch

        val images = coroutineScope {
            cards.map { card ->
                async {
                    card to runCatching { download(card.imageUrl) }.getOrNull()
                }
            }.awaitAll()
        }
        val ranked = images.mapNotNull { (candidate, bytes) ->
            val embedding = synchronized(embeddingCache) {
                embeddingCache[candidate.imageUrl]
            } ?: bytes?.let {
                runCatching { imageEmbedder.embed(it).embedding }.getOrNull()
                    ?.also { value ->
                        synchronized(embeddingCache) {
                            embeddingCache[candidate.imageUrl] = value
                        }
                    }
            } ?: return@mapNotNull null
            candidate to cosineSimilarity(query.embedding, embedding)
        }
            .sortedByDescending { it.second }
            .take(MAX_RESULTS)
        if (ranked.isEmpty()) return@withContext RecognitionOutcome.NoMatch

        val results = coroutineScope {
            ranked.map { (candidate, similarity) ->
                async {
                    candidate.toRecognizedCard(
                        similarity = similarity,
                        scannedNumber = hints.localId,
                    )
                }
            }.awaitAll()
        }
        val best = results.firstOrNull() ?: return@withContext RecognitionOutcome.NoMatch
        if (best.confidence < MIN_CONFIDENCE) {
            return@withContext RecognitionOutcome.NoMatch
        }
        RecognitionOutcome.Match(best, results)
    }

    private fun searchOfficial(name: String): List<OfficialCard> {
        val url = "$OFFICIAL_SITE/card-search/resultAPI.php"
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
            val data = JSONObject(body).optJSONArray("cardList") ?: JSONArray()
            buildList {
                for (index in 0 until data.length()) {
                    val json = data.optJSONObject(index) ?: continue
                    val id = json.optString("cardID")
                    val nameValue = json.optString("cardNameAltText")
                    val imagePath = json.optString("cardThumbFile")
                    if (id.isBlank() || nameValue.isBlank() || imagePath.isBlank()) continue
                    add(
                        OfficialCard(
                            id = id,
                            name = nameValue,
                            setId = imagePath
                                .substringAfter("/large/", "")
                                .substringBefore('/'),
                            imageUrl = "$OFFICIAL_SITE$imagePath",
                        ),
                    )
                }
            }
        }
    }

    private fun OfficialCard.toRecognizedCard(
        similarity: Double,
        scannedNumber: String?,
    ): RecognizedCard {
        val number = numberCache.computeIfAbsent(id, ::fetchNumber)
        val numberBonus = if (
            scannedNumber != null &&
            normalizedNumber(scannedNumber) == normalizedNumber(number)
        ) {
            NUMBER_BONUS
        } else {
            0.0
        }
        val confidence = (
            NAME_BASE_SCORE + similarity.coerceIn(0.0, 1.0) * IMAGE_SCORE_WEIGHT + numberBonus
            ).coerceIn(0.0, 0.98)
        return yuyuTeiPriceRepository.enrich(
            RecognizedCard(
                id = id,
                name = name,
                setName = setId.ifBlank { "일본 공식 카드" },
                number = number.ifBlank { "-" },
                imageUrl = imageUrl,
                marketPrice = null,
                currency = "JPY",
                priceSource = "price-pending",
                confidence = confidence,
                language = CardLanguage.JAPANESE,
                source = "pokemon-card-official",
                setId = setId,
                imageHighUrl = imageUrl,
            ),
        )
    }

    private fun fetchNumber(id: String): String {
        val request = Request.Builder()
            .url("$OFFICIAL_SITE/card-search/details.php/card/$id/regu/all")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            OFFICIAL_NUMBER_REGEX.find(response.body?.string().orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        }
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }

    private data class OfficialCard(
        val id: String,
        val name: String,
        val setId: String,
        val imageUrl: String,
    )

    private companion object {
        const val OFFICIAL_SITE = "https://www.pokemon-card.com"
        const val USER_AGENT = "PokeBinder/0.15 (personal collection app)"
        const val MAX_NAMES = 2
        const val MAX_IMAGE_CANDIDATES = 12
        const val MAX_RESULTS = 5
        const val MAX_EMBEDDING_CACHE = 40
        const val NAME_BASE_SCORE = 0.28
        const val IMAGE_SCORE_WEIGHT = 0.62
        const val NUMBER_BONUS = 0.08
        const val MIN_CONFIDENCE = 0.60
        val OFFICIAL_NUMBER_REGEX = Regex("""&nbsp;\s*(\d{1,4})\s*&nbsp;\s*/""")

        fun cleanName(value: String): String = value
            .replace(Regex("""(?i)\s*HP\s*\d{1,3}.*$"""), "")
            .trim()

        fun normalized(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s　'’._\-]+"""), "")

        fun normalizedNumber(value: String): String =
            value.filter(Char::isDigit).trimStart('0').ifBlank { "0" }

        fun cosineSimilarity(first: FloatArray, second: FloatArray): Double {
            if (first.size != second.size || first.isEmpty()) return 0.0
            var dot = 0.0
            var firstNorm = 0.0
            var secondNorm = 0.0
            first.indices.forEach { index ->
                val left = first[index].toDouble()
                val right = second[index].toDouble()
                dot += left * right
                firstNorm += left * left
                secondNorm += right * right
            }
            if (firstNorm == 0.0 || secondNorm == 0.0) return 0.0
            return dot / kotlin.math.sqrt(firstNorm * secondNorm)
        }
    }
}
