package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.daemon.StateDir
import com.qkt.trade.session.BotSessionDescriptor
import com.qkt.trade.session.BotSessionFiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Thin HTTP client bot verbs use to talk to a running session. Resolution order:
 * `--run <id>` → `QKT_BOT_RUN` env → the single session under the state root
 * (`--state-dir` respected). Returns null when no session is running, in which
 * case verbs fall back to their direct venue path unchanged.
 */
class BotSessionClient(
    private val descriptor: BotSessionDescriptor,
) {
    private val http = HttpClient.newHttpClient()

    val runId: String get() = descriptor.runId

    /** `backtest` or `live` — some verbs stay venue-direct in live mode. */
    val mode: String get() = descriptor.mode

    fun get(path: String): String = send(request(path).GET().build())

    fun post(
        path: String,
        body: String,
    ): String = send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build())

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:${descriptor.port}$path"))
            .header("Authorization", "Bearer ${descriptor.token}")

    private fun send(req: HttpRequest): String {
        val response = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "session ${descriptor.runId} error ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }

    companion object {
        /** Resolves the session for this invocation, or null when none is running. */
        fun resolve(args: Args): BotSessionClient? {
            val stateRoot = StateDir.resolve(args.option("state-dir")).stateRoot
            val descriptor =
                BotSessionFiles.resolve(stateRoot, args.option("run")) ?: return null
            return BotSessionClient(descriptor)
        }
    }
}
