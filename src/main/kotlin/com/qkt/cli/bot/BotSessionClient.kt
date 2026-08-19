package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.daemon.StateDir
import com.qkt.trade.session.BotSessionDescriptor
import com.qkt.trade.session.BotSessionFiles
import java.time.Duration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin HTTP client bot verbs use to talk to a running session. Resolution order:
 * `--run <id>` → `QKT_BOT_RUN` env → the single session under the state root
 * (`--state-dir` respected). Returns null when no session is running, in which
 * case verbs fall back to their direct venue path unchanged.
 *
 * Uses OkHttp (the same transport as [com.qkt.cli.daemon.ControlClient]) so the
 * verbs work in the slim jlink runtime image, which does not bundle the
 * `java.net.http` module. The read timeout is long because `bot next` blocks in a
 * live session until the next bar closes.
 */
class BotSessionClient(
    private val descriptor: BotSessionDescriptor,
    private val http: OkHttpClient = defaultHttp(),
) {
    val runId: String get() = descriptor.runId

    /** `backtest` or `live` — some verbs stay venue-direct in live mode. */
    val mode: String get() = descriptor.mode

    fun get(path: String): String = send(authenticated("http://127.0.0.1:${descriptor.port}$path").build())

    fun post(
        path: String,
        body: String,
    ): String =
        send(
            authenticated("http://127.0.0.1:${descriptor.port}$path")
                .post(body.toRequestBody(JSON))
                .build(),
        )

    private fun authenticated(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${descriptor.token}")

    private fun send(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "session ${descriptor.runId} error ${response.code}: $body"
            }
            return body
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private fun defaultHttp(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(30))
                .build()

        /** Resolves the session for this invocation, or null when none is running. */
        fun resolve(args: Args): BotSessionClient? {
            val stateRoot = StateDir.resolve(args.option("state-dir")).stateRoot
            val descriptor =
                BotSessionFiles.resolve(stateRoot, args.option("run")) ?: return null
            return BotSessionClient(descriptor)
        }
    }
}
