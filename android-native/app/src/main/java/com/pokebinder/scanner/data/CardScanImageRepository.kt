package com.pokebinder.scanner.data

import com.pokebinder.scanner.BuildConfig
import com.pokebinder.scanner.model.RecognizedCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CardScanImageRepository(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun upload(
        session: SupabaseSession,
        card: RecognizedCard,
        jpegBytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val path = listOf(
            session.user.id,
            card.language.code,
            safeSegment(card.source),
            "${safeSegment(card.id)}.jpg",
        ).joinToString("/")
        val objectUrl = "${supabaseUrl.trimEnd('/')}/storage/v1/object/$BUCKET/$path"
        val request = Request.Builder()
            .url(objectUrl)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("x-upsert", "true")
            .put(jpegBytes.toRequestBody(JPEG_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("스캔 이미지 저장 오류 (${response.code}): ${body.take(160)}")
            }
        }
        createSignedUrl(session, path)
    }

    private fun createSignedUrl(
        session: SupabaseSession,
        path: String,
    ): String {
        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/storage/v1/object/sign/$BUCKET/$path")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(
                JSONObject()
                    .put("expiresIn", SIGNED_URL_SECONDS)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("스캔 이미지 URL 생성 오류 (${response.code})")
            }
            val signedPath = JSONObject(body).optString("signedURL")
                .ifBlank { error("스캔 이미지 URL을 확인할 수 없습니다.") }
            return if (signedPath.startsWith("http")) {
                signedPath
            } else if (signedPath.startsWith("/storage/v1/")) {
                "${supabaseUrl.trimEnd('/')}$signedPath"
            } else {
                "${supabaseUrl.trimEnd('/')}/storage/v1/${signedPath.trimStart('/')}"
            }
        }
    }

    private fun safeSegment(value: String): String = value
        .lowercase()
        .replace(Regex("""[^a-z0-9._-]+"""), "-")
        .trim('-')
        .ifBlank { "card" }

    private companion object {
        const val BUCKET = "card-scans"
        const val SIGNED_URL_SECONDS = 31_536_000
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
