package com.pokebinder.scanner.data

import com.pokebinder.scanner.BuildConfig
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognizedCard
import com.pokebinder.scanner.model.SessionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SupabaseCollectionRepository(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun loadCollection(session: SupabaseSession): List<SessionCard> =
        withContext(Dispatchers.IO) {
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/collection_cards"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("select", "*")
                .addQueryParameter("user_id", "eq.${session.user.id}")
                .addQueryParameter("order", "updated_at.desc")
                .build()
            val response = executeArray(
                authorizedRequest(url.toString(), session)
                    .get()
                    .build(),
            )
            response.mapNotNull(::parseCard)
        }

    suspend fun saveExact(
        session: SupabaseSession,
        item: SessionCard,
        isFavorite: Boolean,
    ): SessionCard = withContext(Dispatchers.IO) {
        if (item.quantity <= 0) {
            item.cloudId?.let { delete(session, it) }
            return@withContext item
        }

        val payload = cardToJson(session.user.id, item, isFavorite)
        if (item.cloudId == null) {
            val request = authorizedRequest(
                "${supabaseUrl.trimEnd('/')}/rest/v1/collection_cards",
                session,
            )
                .header("Prefer", "return=representation")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeArray(request).firstOrNull()?.let(::parseCard)
                ?: error("저장된 카드 정보를 확인할 수 없습니다.")
        } else {
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/collection_cards"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("id", "eq.${item.cloudId}")
                .addQueryParameter("user_id", "eq.${session.user.id}")
                .build()
            val patch = JSONObject()
                .put("quantity", item.quantity)
                .put("is_favorite", isFavorite)
            val request = authorizedRequest(url.toString(), session)
                .header("Prefer", "return=representation")
                .patch(patch.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeArray(request).firstOrNull()?.let(::parseCard)
                ?: error("수정된 카드 정보를 확인할 수 없습니다.")
        }
    }

    suspend fun delete(
        session: SupabaseSession,
        cloudId: String,
    ) = withContext(Dispatchers.IO) {
        val url = "${supabaseUrl.trimEnd('/')}/rest/v1/collection_cards"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("id", "eq.$cloudId")
            .addQueryParameter("user_id", "eq.${session.user.id}")
            .build()
        executeEmpty(
            authorizedRequest(url.toString(), session)
                .delete()
                .build(),
        )
    }

    private fun authorizedRequest(
        url: String,
        session: SupabaseSession,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("apikey", anonKey)
        .header("Authorization", "Bearer ${session.accessToken}")
        .header("Accept", "application/json")

    private fun executeArray(request: Request): List<JSONObject> {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessage(body, response.code))
            }
            if (body.isBlank()) return emptyList()
            val array = JSONArray(body)
            return buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::add)
                }
            }
        }
    }

    private fun executeEmpty(request: Request) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessage(body, response.code))
            }
        }
    }

    private fun cardToJson(
        userId: String,
        item: SessionCard,
        isFavorite: Boolean,
    ): JSONObject = JSONObject()
        .put("user_id", userId)
        .put("source", item.card.source)
        .put("language", item.card.language.code)
        .put("external_id", item.card.id)
        .put("name", item.card.name)
        .put("localized_names", JSONObject())
        .put("set_id", item.card.setId.takeIf { it.isNotBlank() })
        .put("set_name", item.card.setName)
        .put("card_number", item.card.number)
        .put("rarity", item.card.rarity.takeIf { it.isNotBlank() })
        .put("image_url", item.card.imageUrl)
        .put("image_high_url", item.card.imageHighUrl ?: item.card.imageUrl)
        .put("condition", item.condition)
        .put("finish", item.finish)
        .put("quantity", item.quantity)
        .put("market_price", item.card.marketPrice ?: 0.0)
        .put("currency", item.card.currency)
        .put("price_source", item.card.source)
        .put("raw", JSONObject())
        .put("is_favorite", isFavorite)

    private fun parseCard(json: JSONObject): SessionCard? {
        val externalId = json.optString("external_id")
        val name = json.optString("name")
        val cloudId = json.optString("id")
        if (externalId.isBlank() || name.isBlank() || cloudId.isBlank()) return null
        val language = when (json.optString("language")) {
            CardLanguage.KOREAN.code -> CardLanguage.KOREAN
            CardLanguage.ENGLISH.code -> CardLanguage.ENGLISH
            else -> CardLanguage.JAPANESE
        }
        return SessionCard(
            cloudId = cloudId,
            quantity = json.optInt("quantity", 1),
            condition = json.optString("condition", "NM"),
            finish = json.optString("finish", "normal"),
            isFavorite = json.optBoolean("is_favorite", false),
            card = RecognizedCard(
                id = externalId,
                name = name,
                setName = json.optString("set_name", "세트 정보 없음"),
                number = json.optString("card_number", "-"),
                imageUrl = json.optString("image_url").takeIf { it.isNotBlank() },
                marketPrice = json.optDouble("market_price")
                    .takeUnless { it.isNaN() || it <= 0.0 },
                currency = json.optString("currency", defaultCurrency(language)),
                confidence = 1.0,
                language = language,
                source = json.optString("source", "tcgdex"),
                setId = json.optString("set_id"),
                rarity = json.optString("rarity"),
                imageHighUrl = json.optString("image_high_url").takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun errorMessage(body: String, code: Int): String = runCatching {
        val json = JSONObject(body)
        json.optString("message")
            .ifBlank { json.optString("details") }
            .ifBlank { json.optString("hint") }
    }.getOrDefault("")
        .ifBlank { "Supabase 동기화 오류 ($code)" }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultCurrency(language: CardLanguage): String = when (language) {
            CardLanguage.JAPANESE -> "JPY"
            CardLanguage.KOREAN -> "KRW"
            CardLanguage.ENGLISH -> "USD"
        }
    }
}
