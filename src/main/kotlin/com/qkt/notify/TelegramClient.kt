package com.qkt.notify

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Single-request HTTP wrapper around the Telegram bot `sendMessage` endpoint.
 *
 * Returns a discriminated [Outcome] so the caller ([NotificationWorker]) can decide retry
 * vs degraded-mode vs drop. Knows nothing about queues, backoff, or scheduling.
 */
class TelegramClient(
    private val baseUrl: String,
    private val botToken: String,
    private val chatId: String,
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build(),
) {
    sealed interface Outcome {
        object Ok : Outcome

        data class RateLimited(
            val retryAfterMs: Long,
        ) : Outcome

        data class TransientError(
            val code: Int,
        ) : Outcome

        object AuthFailed : Outcome

        data class BadRequest(
            val code: Int,
            val body: String,
        ) : Outcome

        data class NetworkError(
            val message: String,
        ) : Outcome
    }

    fun send(text: String): Outcome {
        val body = """{"chat_id":"$chatId","text":"${escape(text)}"}"""
        val req =
            Request
                .Builder()
                .url("$baseUrl/bot$botToken/sendMessage")
                .post(body.toRequestBody(JSON))
                .build()
        return try {
            http.newCall(req).execute().use { res ->
                when (res.code) {
                    200 -> Outcome.Ok
                    429 -> {
                        val retryAfter = res.header("Retry-After")?.toLongOrNull() ?: 1L
                        Outcome.RateLimited(retryAfterMs = retryAfter * 1_000L)
                    }
                    401, 403 -> Outcome.AuthFailed
                    in 500..599 -> Outcome.TransientError(res.code)
                    in 400..499 -> Outcome.BadRequest(code = res.code, body = res.body?.string().orEmpty())
                    else -> Outcome.TransientError(res.code)
                }
            }
        } catch (e: IOException) {
            Outcome.NetworkError(e.message ?: e::class.java.simpleName)
        }
    }

    private fun escape(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
