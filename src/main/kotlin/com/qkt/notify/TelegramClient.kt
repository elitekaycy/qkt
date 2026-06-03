package com.qkt.notify

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One inbound Telegram update. [chatId]/[text] are null when the update isn't a text message. */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: Long?,
    val text: String?,
)

/** Result of a getUpdates long-poll. [Failed] = network/HTTP/parse error; the caller backs off. */
sealed interface UpdatesOutcome {
    data class Received(
        val updates: List<TelegramUpdate>,
    ) : UpdatesOutcome

    data object Failed : UpdatesOutcome
}

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

    fun getUpdates(
        offset: Long,
        timeoutSeconds: Int,
    ): UpdatesOutcome {
        val req =
            Request
                .Builder()
                .url("$baseUrl/bot$botToken/getUpdates?offset=$offset&timeout=$timeoutSeconds")
                .get()
                .build()
        return try {
            http.newCall(req).execute().use { res ->
                if (res.code != 200) return UpdatesOutcome.Failed
                val body = res.body?.string() ?: return UpdatesOutcome.Failed
                parseUpdates(body)
            }
        } catch (e: IOException) {
            UpdatesOutcome.Failed
        }
    }

    private fun parseUpdates(body: String): UpdatesOutcome {
        return try {
            val root = JSON_PARSER.parseToJsonElement(body).jsonObject
            val ok = root["ok"]?.jsonPrimitive?.content
            if (ok != "true") return UpdatesOutcome.Failed
            val result = root["result"]?.jsonArray ?: return UpdatesOutcome.Received(emptyList())
            val updates =
                result.map { element ->
                    val obj = element.jsonObject
                    val updateId =
                        obj["update_id"]?.jsonPrimitive?.content?.toLongOrNull()
                            ?: return UpdatesOutcome.Failed
                    val message = obj["message"]?.jsonObject
                    val chat = message?.get("chat")?.jsonObject
                    val chatId =
                        chat
                            ?.get("id")
                            ?.jsonPrimitive
                            ?.content
                            ?.toLongOrNull()
                    val text = message?.get("text")?.jsonPrimitive?.content
                    TelegramUpdate(updateId = updateId, chatId = chatId, text = text)
                }
            UpdatesOutcome.Received(updates)
        } catch (e: Exception) {
            UpdatesOutcome.Failed
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
        val JSON_PARSER = Json { ignoreUnknownKeys = true }
    }
}
