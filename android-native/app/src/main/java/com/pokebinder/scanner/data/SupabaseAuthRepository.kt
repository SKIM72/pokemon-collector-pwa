package com.pokebinder.scanner.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pokebinder.scanner.BuildConfig
import com.pokebinder.scanner.model.AuthUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val user: AuthUser,
)

sealed interface AuthOutcome {
    data class SignedIn(val session: SupabaseSession) : AuthOutcome
    data class Notice(val message: String) : AuthOutcome
    data class Failure(val message: String) : AuthOutcome
}

class SupabaseAuthRepository(
    context: Context,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    private val sessionStore = SessionStore(context)

    val isConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    suspend fun restoreSession(): SupabaseSession? {
        val stored = sessionStore.load() ?: return null
        return ensureFresh(stored)
    }

    suspend fun ensureFresh(session: SupabaseSession): SupabaseSession? {
        val now = System.currentTimeMillis() / 1_000
        if (session.expiresAtEpochSeconds > now + 60) return session
        return refresh(session.refreshToken).also { refreshed ->
            if (refreshed == null) sessionStore.clear()
        }
    }

    suspend fun signIn(email: String, password: String): AuthOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        requestSession(
            path = "/auth/v1/token?grant_type=password",
            payload = payload,
        )
    }

    suspend fun signUp(
        email: String,
        password: String,
        username: String,
    ): AuthOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("data", JSONObject().put("username", username.trim()))
        val response = execute(
            Request.Builder()
                .url("${supabaseUrl.trimEnd('/')}/auth/v1/signup")
                .header("apikey", anonKey)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        if (!response.success) return@withContext AuthOutcome.Failure(response.errorMessage)

        val json = response.json
        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) {
            return@withContext AuthOutcome.Notice(
                "회원가입 요청이 완료되었습니다. 이메일의 인증 링크를 확인해 주세요.",
            )
        }
        parseSession(json)?.let {
            sessionStore.save(it)
            AuthOutcome.SignedIn(it)
        } ?: AuthOutcome.Failure("회원가입 응답을 확인할 수 없습니다.")
    }

    suspend fun sendPasswordReset(email: String): AuthOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", email.trim())
        val url = "${supabaseUrl.trimEnd('/')}/auth/v1/recover"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("redirect_to", BuildConfig.PASSWORD_RESET_REDIRECT_URL)
            .build()
        val response = execute(
            Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        if (response.success) {
            AuthOutcome.Notice("비밀번호 재설정 메일을 보냈습니다.")
        } else {
            AuthOutcome.Failure(response.errorMessage)
        }
    }

    suspend fun updateProfile(
        session: SupabaseSession,
        username: String,
    ): AuthOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("data", JSONObject().put("username", username.trim()))
        updateUser(session, payload)
    }

    suspend fun updatePassword(
        session: SupabaseSession,
        password: String,
    ): AuthOutcome = withContext(Dispatchers.IO) {
        updateUser(session, JSONObject().put("password", password))
    }

    suspend fun signOut(session: SupabaseSession?) = withContext(Dispatchers.IO) {
        session?.let {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("${supabaseUrl.trimEnd('/')}/auth/v1/logout")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer ${it.accessToken}")
                        .post(ByteArray(0).toRequestBody(null))
                        .build(),
                ).execute().close()
            }
        }
        sessionStore.clear()
    }

    private suspend fun refresh(refreshToken: String): SupabaseSession? = withContext(Dispatchers.IO) {
        val outcome = requestSession(
            path = "/auth/v1/token?grant_type=refresh_token",
            payload = JSONObject().put("refresh_token", refreshToken),
        )
        (outcome as? AuthOutcome.SignedIn)?.session
    }

    private fun requestSession(
        path: String,
        payload: JSONObject,
    ): AuthOutcome {
        val response = execute(
            Request.Builder()
                .url("${supabaseUrl.trimEnd('/')}$path")
                .header("apikey", anonKey)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        if (!response.success) return AuthOutcome.Failure(response.errorMessage)
        val session = parseSession(response.json)
            ?: return AuthOutcome.Failure("로그인 응답을 확인할 수 없습니다.")
        sessionStore.save(session)
        return AuthOutcome.SignedIn(session)
    }

    private fun updateUser(
        session: SupabaseSession,
        payload: JSONObject,
    ): AuthOutcome {
        val response = execute(
            Request.Builder()
                .url("${supabaseUrl.trimEnd('/')}/auth/v1/user")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        if (!response.success) return AuthOutcome.Failure(response.errorMessage)
        val user = parseUser(response.json)
            ?: return AuthOutcome.Failure("사용자 정보를 확인할 수 없습니다.")
        val updated = session.copy(user = user)
        sessionStore.save(updated)
        return AuthOutcome.SignedIn(updated)
    }

    private fun parseSession(json: JSONObject): SupabaseSession? {
        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        val user = json.optJSONObject("user")?.let(::parseUser)
        if (accessToken.isBlank() || refreshToken.isBlank() || user == null) return null
        val expiresIn = json.optLong("expires_in", 3_600L)
        return SupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1_000 + expiresIn,
            user = user,
        )
    }

    private fun parseUser(json: JSONObject): AuthUser? {
        val id = json.optString("id")
        val email = json.optString("email")
        if (id.isBlank() || email.isBlank()) return null
        val metadata = json.optJSONObject("user_metadata")
            ?: json.optJSONObject("raw_user_meta_data")
        return AuthUser(
            id = id,
            email = email,
            username = metadata?.optString("username").orEmpty(),
        )
    }

    private fun execute(request: Request): JsonResponse = runCatching {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            JsonResponse(
                success = response.isSuccessful,
                json = json,
                errorMessage = if (response.isSuccessful) {
                    ""
                } else {
                    json.optString("msg")
                        .ifBlank { json.optString("message") }
                        .ifBlank { json.optString("error_description") }
                        .ifBlank { json.optString("error") }
                        .ifBlank { "Supabase 인증 오류 (${response.code})" }
                },
            )
        }
    }.getOrElse { error ->
        JsonResponse(
            success = false,
            json = JSONObject(),
            errorMessage = error.message ?: "Supabase에 연결할 수 없습니다.",
        )
    }

    private data class JsonResponse(
        val success: Boolean,
        val json: JSONObject,
        val errorMessage: String,
    )

    private class SessionStore(context: Context) {
        private val preferences = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "pokebinder_supabase_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            context.getSharedPreferences(
                "pokebinder_supabase_session_fallback",
                Context.MODE_PRIVATE,
            )
        }

        fun save(session: SupabaseSession) {
            preferences.edit()
                .putString("access_token", session.accessToken)
                .putString("refresh_token", session.refreshToken)
                .putLong("expires_at", session.expiresAtEpochSeconds)
                .putString("user_id", session.user.id)
                .putString("email", session.user.email)
                .putString("username", session.user.username)
                .apply()
        }

        fun load(): SupabaseSession? {
            val accessToken = preferences.getString("access_token", null) ?: return null
            val refreshToken = preferences.getString("refresh_token", null) ?: return null
            val userId = preferences.getString("user_id", null) ?: return null
            val email = preferences.getString("email", null) ?: return null
            return SupabaseSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSeconds = preferences.getLong("expires_at", 0L),
                user = AuthUser(
                    id = userId,
                    email = email,
                    username = preferences.getString("username", "").orEmpty(),
                ),
            )
        }

        fun clear() {
            preferences.edit().clear().apply()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
