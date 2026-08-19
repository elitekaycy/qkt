package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.trade.BotQuoteContext
import com.qkt.trade.compileBotAction
import com.qkt.trade.parseBotStrategy
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
    bind: String = "127.0.0.1",
    port: Int = 0,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0)

    @Volatile
    var finished: Boolean = false
        private set

    private var intentSeq = 0L

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
            when {
                method == "GET" && path == "/status" -> status(ex)
                method == "POST" && path == "/next" -> next(ex)
                method == "GET" && path == "/bars" -> bars(ex)
                method == "GET" && path == "/quote" -> quote(ex)
                method == "GET" && path == "/positions" -> positions(ex)
                method == "POST" && path == "/intent" -> intent(ex)
                method == "POST" && path == "/finish" -> finish(ex)
                else -> respond(ex, 404, """{"ok":false,"error":"not found"}""")
            }
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, errorJson(e))
        } catch (e: IllegalStateException) {
            respond(ex, 400, errorJson(e))
        }
    }

    private fun status(ex: HttpExchange) =
        respond(
            ex,
            200,
            """{"ok":true,"run":"${session.runId}","simNowMs":${session.simNowMs()},""" +
                """"equity":${session.equity()?.toPlainString() ?: "null"},""" +
                """"identities":[${session.identities().sorted().joinToString(",") { "\"$it\"" }}],""" +
                """"finished":$finished}""",
        )

    private fun next(ex: HttpExchange) {
        val symbol = field(body(ex), "symbol")
        val bar = session.next(symbol)
        if (bar == null) {
            journalRead("next", symbol, delivered = 0)
            respond(ex, 200, """{"ok":true,"type":"end"}""")
        } else {
            journalRead("next", symbol, delivered = 1)
            respond(ex, 200, barJson(bar))
        }
    }

    private fun bars(ex: HttpExchange) {
        val q = query(ex)
        val symbol = q["symbol"] ?: error("missing query param 'symbol'")
        val count = q["count"]?.toIntOrNull() ?: 100
        val served = session.bars(symbol, count)
        journalRead("bars", symbol, delivered = served.size)
        respond(ex, 200, served.joinToString(",", "[", "]") { barJson(it) })
    }

    private fun quote(ex: HttpExchange) {
        val symbol = query(ex)["symbol"] ?: error("missing query param 'symbol'")
        val t = session.quote(symbol) ?: error("no quote yet for $symbol — call /next first")
        journalRead("quote", symbol, delivered = 1)
        val bid = t.bid ?: t.price
        val ask = t.ask ?: t.price
        respond(
            ex,
            200,
            """{"ok":true,"symbol":"$symbol","bid":${bid.toPlainString()},""" +
                """"ask":${ask.toPlainString()},"timeMs":${t.timestamp}}""",
        )
    }

    private fun positions(ex: HttpExchange) {
        val rows =
            session.positions().entries.joinToString(",", "[", "]") { (symbol, p) ->
                """{"symbol":"$symbol","qty":${p.quantity.toPlainString()}}"""
            }
        respond(ex, 200, rows)
    }

    private fun intent(ex: HttpExchange) {
        val obj = body(ex)
        val identity = field(obj, "identity")
        val source = field(obj, "source")
        val action = parseBotStrategy(source)
        val ctx = quoteContextFor?.invoke(action.qktSymbol) ?: defaultQuoteContext(action.qktSymbol)
        val ts = session.simNowMs()
        val id = "bot-${session.runId}-$identity-$ts-${intentSeq++}"
        val compiled = compileBotAction(action, ctx, id = id, timestamp = ts, strategyId = identity)
        session.submit(identity, Signal.Submit(compiled.request))
        trail?.recordEvent(
            identity,
            "bot.session.intent",
            mapOf(
                "orderId" to id,
                "symbol" to action.qktSymbol,
                "sha256" to action.sha256,
                "lots" to compiled.request.quantity.toPlainString(),
                "simMs" to ts.toString(),
            ),
        )
        respond(
            ex,
            200,
            """{"ok":true,"queued":true,"orderId":"$id","symbol":"${action.qktSymbol}",""" +
                """"lots":${compiled.request.quantity.toPlainString()},""" +
                """"sl":${compiled.stopLoss?.toPlainString() ?: "null"},""" +
                """"tp":${compiled.takeProfit?.toPlainString() ?: "null"},"sha256":"${action.sha256}"}""",
        )
    }

    private fun finish(ex: HttpExchange) {
        if (finished) return respond(ex, 200, """{"ok":true,"finished":true}""")
        val result = session.finish()
        val reportDir = onFinish(result)
        finished = true
        respond(
            ex,
            200,
            """{"ok":true,"finished":true,"trades":${result?.trades?.size ?: "null"},""" +
                """"reportDir":${reportDir?.let { "\"$it\"" } ?: "null"}}""",
        )
    }

    /** Backtest intent context: cursor quote + model equity (no venue metadata). */
    private fun defaultQuoteContext(symbol: String): BotQuoteContext {
        val tick =
            session.quote(symbol)
                ?: error("no market data yet for $symbol — call /next first")
        return BotQuoteContext(
            bid = tick.bid ?: tick.price,
            ask = tick.ask ?: tick.price,
            equity = session.equity(),
            balance = session.equity(),
            contractSize = null,
            accountCurrency = accountCurrency,
            quoteCurrency = accountCurrency,
            volumeMin = null,
            volumeStep = null,
            volumeMax = null,
            digits = null,
        )
    }

    @Synchronized
    private fun journalRead(
        verb: String,
        symbol: String,
        delivered: Int,
    ) {
        val path = readsJournal ?: return
        val line =
            """{"tsMs":${System.currentTimeMillis()},"simMs":${session.simNowMs()},""" +
                """"verb":"$verb","symbol":"$symbol","delivered":$delivered}""" + "\n"
        java.nio.file.Files.writeString(
            path,
            line,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    private fun barJson(bar: Candle): String =
        """{"ok":true,"type":"bar","symbol":"${bar.symbol}","timeMs":${bar.startTime},""" +
            """"open":${bar.open.toPlainString()},"high":${bar.high.toPlainString()},""" +
            """"low":${bar.low.toPlainString()},"close":${bar.close.toPlainString()},""" +
            """"volume":${bar.volume.toPlainString()},"endMs":${bar.endTime}}"""

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
