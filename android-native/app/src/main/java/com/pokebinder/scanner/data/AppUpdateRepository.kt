package com.pokebinder.scanner.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.pokebinder.scanner.BuildConfig
import com.pokebinder.scanner.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AppUpdateRepository(
    private val context: Context,
    private val latestReleaseUrl: String = BuildConfig.UPDATE_RELEASE_API,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun checkLatest(): AppUpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PokeBinder-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                error("업데이트 확인 실패 (${response.code})")
            }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name")
            val releaseUrl = json.optString("html_url")
            val asset = findApkAsset(json)
                ?: error("릴리스에서 APK 파일을 찾을 수 없습니다.")
            val latestVersion = versionFromTag(tagName)
            AppUpdateInfo(
                currentVersion = BuildConfig.VERSION_NAME,
                latestVersion = latestVersion,
                tagName = tagName,
                releaseUrl = releaseUrl,
                apkUrl = asset.downloadUrl,
                apkName = asset.name,
                apkSizeBytes = asset.size,
                isUpdateAvailable = isNewerVersion(latestVersion, BuildConfig.VERSION_NAME),
            )
        }
    }

    suspend fun downloadApk(update: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        if (update.apkUrl.isBlank()) error("다운로드할 APK 정보가 없습니다.")
        val request = Request.Builder()
            .url(update.apkUrl)
            .header("User-Agent", "PokeBinder-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        val updateDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
        val outputFile = File(updateDirectory, "PokeBinder-${update.latestVersion}.apk")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("APK 다운로드 실패 (${response.code})")
            }
            val body = response.body ?: error("APK 다운로드 응답이 비어 있습니다.")
            outputFile.outputStream().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
        outputFile
    }

    fun openInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun findApkAsset(json: JSONObject): ApkAsset? {
        val assets = json.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val contentType = asset.optString("content_type")
            if (!name.endsWith(".apk", ignoreCase = true) &&
                contentType != APK_MIME_TYPE
            ) {
                continue
            }
            val downloadUrl = asset.optString("browser_download_url")
            if (downloadUrl.isBlank()) continue
            return ApkAsset(
                name = name,
                downloadUrl = downloadUrl,
                size = asset.optLong("size", 0L),
            )
        }
        return null
    }

    private fun versionFromTag(tagName: String): String =
        VERSION_PATTERN.find(tagName)?.value.orEmpty()

    private fun isNewerVersion(
        latest: String,
        current: String,
    ): Boolean {
        val latestParts = versionParts(latest)
        val currentParts = versionParts(current)
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val left = latestParts.getOrElse(index) { 0 }
            val right = currentParts.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun versionParts(value: String): List<Int> =
        value.split('.')
            .mapNotNull { it.toIntOrNull() }

    private data class ApkAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
    )

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        val VERSION_PATTERN = Regex("""\d+(?:\.\d+){1,3}""")
    }
}
