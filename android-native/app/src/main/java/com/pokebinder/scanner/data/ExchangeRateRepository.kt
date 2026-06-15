package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.DEFAULT_CURRENCY_RATES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ExchangeRateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun loadRates(): Map<String, Double> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://open.er-api.com/v6/latest/USD")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use DEFAULT_CURRENCY_RATES
                val body = response.body?.string().orEmpty()
                val rates = JSONObject(body).optJSONObject("rates")
                    ?: return@use DEFAULT_CURRENCY_RATES
                DEFAULT_CURRENCY_RATES.keys.associateWith { currency ->
                    rates.optDouble(currency)
                        .takeUnless { it.isNaN() || it <= 0.0 }
                        ?: DEFAULT_CURRENCY_RATES.getValue(currency)
                }
            }
        }.getOrDefault(DEFAULT_CURRENCY_RATES)
    }
}
