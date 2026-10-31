package com.bigrocket.config

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a subscription URL. Blocking: call from a background dispatcher.
 * The body is size-capped, so a hostile or broken server cannot exhaust memory.
 */
object SubscriptionFetcher {

    private const val MAX_BYTES = 2 * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** @throws ConfigParseException for a bad URL, an HTTP error, an oversized or non-text body, or a network failure. */
    fun fetch(url: String): String {
        val request = try {
            Request.Builder().url(url.trim()).header("User-Agent", "BigRocket").build()
        } catch (_: IllegalArgumentException) {
            throw ConfigParseException("آدرس اشتراک نامعتبر است")
        }
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ConfigParseException("دریافت اشتراک ناموفق بود (HTTP ${response.code})")
                }
                val body = response.body ?: throw ConfigParseException("پاسخ اشتراک خالی است")
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                body.byteStream().use { stream ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MAX_BYTES) throw ConfigParseException("پاسخ اشتراک بیش از حد بزرگ است")
                        out.write(buffer, 0, n)
                    }
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: IOException) {
            throw ConfigParseException("اتصال به آدرس اشتراک ناموفق بود: ${e.message ?: "خطای شبکه"}")
        }
    }
}
