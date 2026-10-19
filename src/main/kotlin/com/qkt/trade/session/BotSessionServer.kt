package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import com.qkt.trade.BotQuoteContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local HTTP control surface for one [BotRunSession]. One-shot `qkt bot` verbs are
 * thin clients of these routes; the engine is single-threaded, so all routes run on
 * a single-thread executor. Bearer-token auth on every route (loopback bind).
 *
 * Routes: `GET /status|/bars|/quote|/positions`, `POST /next|/intent|/finish`.
 * `/intent` accepts the canonical bot DSL (as rendered by `renderBotStrategy`),
 * compiles it against the cursor quote, and enqueues it — the pipeline's risk
 * engine admits or rejects it on the next replayed tick.
 */
class BotSessionServer(
    private val session: BotRunSession,
    private val token: String,
    private val accountCurrency: String,
    /** Called on `/finish` with the backtest result (null live): write artifacts, return their dir. */
    private val onFinish: (BacktestResult?) -> String?,
    /**
     * Point-in-time facts intents compile against. Backtest builds it from session
     * state; live builds it from the venue gateway (contract size, volume steps).
     * Null uses the backtest default (cursor quote + model equity).
     */
    private val quoteContextFor: ((String) -> BotQuoteContext)? = null,
    /** Serialize engine-touching routes in backtest; live routes are queue/reads and can overlap. */
    serverThreads: Int = 1,
    /** When set, every next/bars/quote delivery is journaled here (what the agent saw). */
    private val readsJournal: java.nio.file.Path? = null,
    /** When set, intents are journaled/egressed (run-tagged) like one-shot bot commands. */
    private val trail: com.qkt.trade.BotTrail? = null,
    private val clock: com.qkt.common.Clock = com.qkt.common.SystemClock(),
    bind: String = "127.0.0.1",
    port: Int = 0,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0)
    private val handlers =
        BotSessionHandlers(session, accountCurrency, onFinish, quoteContextFor, readsJournal, trail, clock)

    val finished: Boolean get() = handlers.finished

    val boundPort: Int get() = server.address.port

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.executor = Executors.newFixedThreadPool(serverThreads)
    }

    fun start() {
        server.start()
    }

    override fun close() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        try {
            if (!authorized(ex)) {
                ex.responseHeaders.add("WWW-Authenticate", "Bearer")
                return respond(ex, 401, """{"ok":false,"error":"unauthorized"}""")
            }
            val path = ex.requestURI.path
            val method = ex.requestMethod
            val reply =
                when {
                    method == "GET" && path == "/status" -> handlers.status()
                    method == "POST" && path == "/next" ->
                        body(ex).let { b -> handlers.next(field(b, "symbol"), b["tf"]?.jsonPrimitive?.contentOrNull) }
                    method == "GET" && path == "/bars" ->
                        query(ex).let { q ->
                            handlers.bars(q.require("symbol"), q["count"]?.toIntOrNull() ?: 100, q["tf"])
                        }
                    method == "GET" && path == "/quote" -> handlers.quote(query(ex).require("symbol"))
                    method == "GET" && path == "/positions" -> handlers.positions()
                    method == "GET" && path == "/intents" -> handlers.intents()
                    method == "GET" && path == "/orders" -> handlers.orders()
                    method == "POST" && path == "/intent" ->
                        body(ex).let { b -> handlers.intent(field(b, "identity"), field(b, "source")) }
                    method == "POST" && path == "/finish" -> handlers.finish()
                    else -> return respond(ex, 404, """{"ok":false,"error":"not found"}""")
                }
            respond(ex, 200, reply)
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, errorJson(e))
        } catch (e: IllegalStateException) {
            respond(ex, 400, errorJson(e))
        }
    }

    private fun Map<String, String>.require(key: String): String = this[key] ?: error("missing query param '$key'")

    private fun body(ex: HttpExchange) =
        Json
            .parseToJsonElement(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            .jsonObject

    private fun field(
        obj: kotlinx.serialization.json.JsonObject,
        key: String,
    ): String = obj[key]?.jsonPrimitive?.contentOrNull ?: error("missing field '$key'")

    private fun query(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "")
            .split('&')
            .filter { it.contains('=') }
            .associate {
                val i = it.indexOf('=')
                java.net.URLDecoder.decode(it.substring(0, i), StandardCharsets.UTF_8) to
                    java.net.URLDecoder.decode(it.substring(i + 1), StandardCharsets.UTF_8)
            }

    private fun errorJson(e: Exception): String =
        """{"ok":false,"error":"${(e.message ?: e.javaClass.simpleName).replace("\"", "'")}"}"""

    private fun authorized(ex: HttpExchange): Boolean {
        val header = ex.requestHeaders.getFirst("Authorization") ?: return false
        val prefix = "Bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        return MessageDigest.isEqual(
            header.substring(prefix.length).toByteArray(StandardCharsets.UTF_8),
            token.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun respond(
        ex: HttpExchange,
        code: Int,
        json: String,
    ) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
