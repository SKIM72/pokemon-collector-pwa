package com.pokebinder.scanner.data

import android.util.Base64
import com.pokebinder.scanner.BuildConfig
import com.pokebinder.scanner.model.CardLanguage
import com.pokebinder.scanner.model.RecognitionOutcome
import com.pokebinder.scanner.model.RecognizedCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface CardRecognitionRepository {
    suspend fun recognize(jpegBytes: ByteArray, language: CardLanguage): RecognitionOutcome
}

class EdgeFunctionCardRecognitionRepository(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
    private val functionName: String = BuildConfig.CARD_RECOGNITION_FUNCTION,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
) : CardRecognitionRepository {

    override suspend fun recognize(
        jpegBytes: ByteArray,
        language: CardLanguage,
    ): RecognitionOutcome = withContext(Dispatchers.IO) {
        if (supabaseUrl.isBlank() || anonKey.isBlank()) {
            return@withContext RecognitionOutcome.Unavailable(
                "인식 서버 설정 전입니다. 카메라 자동 촬영은 정상 작동 중입니다.",
            )
        }

        val payload = JSONObject()
            .put("language", language.code)
            .put("imageBase64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))

        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/$functionName")
            .header("Authorization", "Bearer $anonKey")
            .header("apikey", anonKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@use RecognitionOutcome.Unavailable(
                        "Supabase의 $functionName 함수를 배포하면 이미지 인식이 연결됩니다.",
                    )
                }

                if (!response.isSuccessful) {
                    return@use RecognitionOutcome.Unavailable(
                        "인식 서버 응답 오류 (${response.code})",
                    )
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use RecognitionOutcome.NoMatch

                val json = JSONObject(body)
                val cardJson = json.optJSONObject("card") ?: json
                val id = cardJson.optString("id")
                val name = cardJson.optString("name")
                if (id.isBlank() || name.isBlank()) return@use RecognitionOutcome.NoMatch

                RecognitionOutcome.Match(
                    RecognizedCard(
                        id = id,
                        name = name,
                        setName = cardJson.optString("setName", "세트 정보 없음"),
                        number = cardJson.optString("number", "-"),
                        imageUrl = cardJson.optString("imageUrl").takeIf { it.isNotBlank() },
                        marketPrice = cardJson.optDouble("marketPrice")
                            .takeUnless { it.isNaN() || it <= 0.0 },
                        currency = cardJson.optString(
                            "currency",
                            when (language) {
                                CardLanguage.JAPANESE -> "JPY"
                                CardLanguage.KOREAN -> "KRW"
                                CardLanguage.ENGLISH -> "USD"
                            },
                        ),
                        confidence = cardJson.optDouble("confidence", 0.0)
                            .coerceIn(0.0, 1.0),
                        language = language,
                    ),
                )
            }
        }.getOrElse { error ->
            RecognitionOutcome.Unavailable(
                error.message ?: "인식 서버에 연결할 수 없습니다.",
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
