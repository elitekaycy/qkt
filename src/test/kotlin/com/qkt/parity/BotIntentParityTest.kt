package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.strategy.Strategy
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionIdentities
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.ReplayBotRunBackend
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A bot session's intents trade exactly as a `.qkt` strategy whose `SCHEDULE` fires the same actions at
 * the same instants: the session is the strategy engine, not a side path. Each intent is sent after
 * `next` returns bar N (closed by the tick at N+1 min + 1 s) and fires on the following tick
 * (N+1 min + 30 s), which is where the reference schedules it.
 */
class BotIntentParityTest {
    private val gold = "EXNESS:XAUUSD"

    private val instruments =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal("100"),
                    volumeStep = BigDecimal("0.01"),
                    volumeMin = BigDecimal("0.01"),
                    volumeMax = null,
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    /** A wave, so stops, targets and trails all get their turn. */
    private val prices = listOf(2400, 2404, 2409, 2406, 2398, 2393, 2397, 2405, 2411, 2402, 2396, 2399, 2404, 2400)

    private fun ticks(): List<Tick> =
        prices.flatMapIndexed { bar, p ->
            listOf(
                Tick(gold, BigDecimal(p), bar * 60_000L + 1_000L),
                Tick(
                    gold,
                    BigDecimal(p + 1),
                    bar * 60_000L + 30_000L,
                ),
            )
        }

    /** After how many closed bars each action is sent, and the action. */
    private val script =
        listOf(
            1 to "BUY x SIZING 0.1 BRACKET { STOP LOSS BY 6, TAKE PROFIT BY 12 }",
            3 to "SELL x SIZING 0.1 BRACKET { STOP LOSS TRAILING 4 AFTER MFE >= 2, TAKE PROFIT BY 15 }",
            5 to "BUY x SIZING 20 PCT OF EQUITY EXIT AFTER 3m",
            9 to "SELL x SIZING 0.2",
            11 to "CLOSE x",
        )

    private fun backtest(strategies: List<Pair<String, Strategy>>) =
        Backtest(
            strategies = strategies,
            ticks = ticks(),
            candleWindow =
                com.qkt.candles.TimeWindow
                    .parse("1m"),
            startingBalance = BigDecimal("100000"),
            instruments = instruments,
        )

    private fun viaSession(): BacktestResult {
        val streams = BotSessionStreams(listOf(gold), listOf("1m"))
        val history = BarHistory(100)
        val recorder = BotSessionRecorder(history)
        val identities = BotSessionIdentities.compile(streams, listOf("brain"), history = history)
        val engine = backtest(identities.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder)).toEngine()
        val session = BotRunSession("parity", ReplayBotRunBackend(engine), identities, streams, history, recorder)
        var closed = 0
        for ((afterBars, action) in script) {
            while (closed < afterBars) {
                session.next(gold)
                closed++
            }
            session.submit(
                "brain",
                "STRATEGY bot VERSION 1\nSYMBOLS\n    x = $gold EVERY 1m\nRULES\n    WHEN true\n    THEN $action\n",
            )
        }
        while (session.next(gold) != null) Unit
        return session.finish()!!
    }

    private fun viaStrategy(): BacktestResult {
        val schedule =
            script.joinToString("\n") { (afterBars, action) ->
                "    AT 00:%02d:30 UTC THEN ${action.replace(Regex("\\bx\\b"), "g")}".format(afterBars)
            }
        val source =
            "STRATEGY ref VERSION 1\n\nSYMBOLS\n    g = $gold EVERY 1m\n\nSCHEDULE\n$schedule\n\n" +
                "RULES\n    WHEN 1 > 2 THEN BUY g SIZING 0.01\n"
        val ast = (Dsl.parse(source) as ParseResult.Success).value
        return backtest(listOf("brain" to AstCompiler().compile(ast))).run()
    }

    @Test
    fun `session intents trade exactly as the same actions scheduled in a strategy`() {
        val session = viaSession().trades
        val strategy = viaStrategy().trades

        assertThat(strategy).hasSizeGreaterThanOrEqualTo(5)
        assertThat(
            session.map {
                listOf(
                    it.trade.symbol,
                    it.trade.side,
                    it.trade.quantity.stripTrailingZeros(),
                    it.trade.price.stripTrailingZeros(),
                    it.trade.timestamp,
                    it.strategyId,
                )
            },
        ).isEqualTo(
            strategy.map {
                listOf(
                    it.trade.symbol,
                    it.trade.side,
                    it.trade.quantity.stripTrailingZeros(),
                    it.trade.price.stripTrailingZeros(),
                    it.trade.timestamp,
                    it.strategyId,
                )
            },
        )
    }
}
