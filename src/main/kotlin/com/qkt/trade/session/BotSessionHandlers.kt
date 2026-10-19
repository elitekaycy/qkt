package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import com.qkt.marketdata.Candle
import com.qkt.trade.BotQuoteContext
import com.qkt.trade.compileBotAction
import com.qkt.trade.parseBotStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * What each session route answers, as JSON text. The engine is single-threaded in a backtest, so the
 * server serializes these there; live, intents only queue and reads only read.
 */
internal class BotSessionHandlers(
    private val session: BotRunSession,
    private val accountCurrency: String,
    private val onFinish: (BacktestResult?) -> String?,
    private val quoteContextFor: ((String) -> BotQuoteContext)?,
    private val readsJournal: Path?,
    private val trail: com.qkt.trade.BotTrail?,
    private val clock: com.qkt.common.Clock,
) {
    @Volatile
    var finished: Boolean = false
        private set

    fun status(): String =
        """{"ok":true,"api":2,"run":"${session.runId}","simNowMs":${session.simNowMs()},""" +
            """"equity":${session.equity()?.toPlainString() ?: "null"},""" +
            """"identities":[${session.identities().sorted().joinToString(",") { "\"$it\"" }}],""" +
            """"symbols":[${session.streams.symbols.joinToString(",") { "\"$it\"" }}],""" +
            """"finished":$finished}"""

    fun next(
        symbol: String,
        tf: String? = null,
    ): String {
        val bar = session.next(symbol, tf)
        journalRead("next", symbol, listOfNotNull(bar))
        val events = session.events().joinToString(",", "[", "]") { eventJson(it) }
        return if (bar == null) {
            """{"ok":true,"type":"end","events":$events}"""
        } else {
            barJson(bar).dropLast(1) + ""","events":$events}"""
        }
    }

    fun bars(
        symbol: String,
        count: Int,
        tf: String? = null,
    ): String {
        val served = session.bars(symbol, count, tf)
        journalRead("bars", symbol, served)
        return served.joinToString(",", "[", "]") { barJson(it) }
    }

    fun quote(symbol: String): String {
        val t = session.quote(symbol) ?: error("no quote yet for $symbol — call /next first")
        journalRead("quote", symbol, emptyList())
        return """{"ok":true,"symbol":"$symbol","bid":${(t.bid ?: t.price).toPlainString()},""" +
            """"ask":${(t.ask ?: t.price).toPlainString()},"timeMs":${t.timestamp}}"""
    }

    /** Net per symbol (`symbol`, `qty`, as before), with each identity's open legs under `legs`. */
    fun positions(): String {
        val legs = session.legs().groupBy { it.symbol }
        return session.positions().entries.joinToString(",", "[", "]") { (symbol, p) ->
            val rows = legs[symbol].orEmpty().joinToString(",", "[", "]") { legJson(it) }
            """{"symbol":"$symbol","qty":${p.quantity.toPlainString()},"legs":$rows}"""
        }
    }

    fun intents(): String = session.fates().joinToString(",", "[", "]") { fateJson(it) }

    fun orders(): String =
        session.workingOrders().joinToString(",", "[", "]") {
            """{"intentId":"${it.intentId}","identity":"${it.identity}","orderId":"${it.orderId}"}"""
        }

    /**
     * Queues intent [source] for [identity]. The reply's `lots`/`sl`/`tp` preview what the one-shot
     * compiler resolves at the cursor for a literal BUY/SELL (null otherwise); the engine sizes and
     * prices the order itself when it fires, and its fate is under `/intents`.
     */
    fun intent(
        identity: String,
        source: String,
    ): String {
        val submitted = session.submit(identity, source)
        val preview = preview(source)
        trail?.recordEvent(
            identity,
            "bot.session.intent",
            mapOf(
                "intentId" to submitted.intentId,
                "symbol" to submitted.symbol,
                "sha256" to submitted.sha256,
                "simMs" to session.simNowMs().toString(),
                "source" to source,
            ),
        )
        return """{"ok":true,"api":2,"queued":true,"intentId":"${submitted.intentId}",""" +
            """"orderId":"${submitted.intentId}","symbol":${submitted.symbol?.let { "\"$it\"" } ?: "null"},""" +
            """"lots":${preview?.request?.quantity?.toPlainString() ?: "null"},""" +
            """"sl":${preview?.stopLoss?.toPlainString() ?: "null"},""" +
            """"tp":${preview?.takeProfit?.toPlainString() ?: "null"},"sha256":"${submitted.sha256}"}"""
    }

    /**
     * Ends the session. [flatten] first closes every identity's book through the engine. The reply lists
     * the legs still open: a venue keeps the stops it holds, but an exit the engine holds (a trail, a timed
     * exit, any gateway bracket) only runs again when a session resumes over the same state.
     */
    fun finish(flatten: Boolean = false): String {
        if (finished) return """{"ok":true,"finished":true}"""
        if (flatten) session.flattenAll()
        val open = session.legs()
        val result = session.finish()
        val reportDir = onFinish(result)
        finished = true
        val note =
            if (open.isEmpty()) {
                "null"
            } else {
                "\"engine-held exits of these legs run again only when a session resumes over this state\""
            }
        return """{"ok":true,"finished":true,"trades":${result?.trades?.size ?: "null"},""" +
            """"reportDir":${reportDir?.let { "\"$it\"" } ?: "null"},""" +
            """"openLegs":${open.joinToString(",", "[", "]") { legJson(it) }},"note":$note}"""
    }

    private fun preview(source: String) =
        runCatching {
            val action = parseBotStrategy(source)
            val ctx = quoteContextFor?.invoke(action.qktSymbol) ?: defaultQuoteContext(action.qktSymbol)
            compileBotAction(action, ctx, id = "preview", timestamp = session.simNowMs(), strategyId = "preview")
        }.getOrNull()

    /** Backtest preview context: cursor quote and model equity (no venue metadata). */
    private fun defaultQuoteContext(symbol: String): BotQuoteContext {
        val tick = session.quote(symbol) ?: error("no market data yet for $symbol")
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

    /** Journals what the client was served: the verb, its symbol, and every bar it saw. */
    @Synchronized
    private fun journalRead(
        verb: String,
        symbol: String,
        served: List<Candle>,
    ) {
        val path = readsJournal ?: return
        val bars = served.joinToString(",", "[", "]") { barJson(it) }
        val line =
            """{"tsMs":${clock.now()},"simMs":${session.simNowMs()},"verb":"$verb","symbol":"$symbol",""" +
                """"delivered":${if (verb == "quote") 1 else served.size},"bars":$bars}""" + "\n"
        Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun barJson(bar: Candle): String =
        """{"ok":true,"type":"bar","symbol":"${bar.symbol}","timeMs":${bar.startTime},""" +
            """"open":${bar.open.toPlainString()},"high":${bar.high.toPlainString()},""" +
            """"low":${bar.low.toPlainString()},"close":${bar.close.toPlainString()},""" +
            """"volume":${bar.volume.toPlainString()},"endMs":${bar.endTime}}"""
}
