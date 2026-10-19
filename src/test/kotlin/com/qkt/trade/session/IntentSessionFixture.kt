package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/** A backtest session over 1m gold bars with a 100-oz contract, for tests that drive intents. */
abstract class IntentSessionFixture {
    protected val gold = "EXNESS:XAUUSD"

    protected val goldSpec =
        object : com.qkt.instrument.InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                com.qkt.instrument.InstrumentMeta(
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

    protected val silver = "EXNESS:XAGUSD"

    /**
     * One tick at +1 s and one at +30 s of each 1m bar, at [prices] (one per bar); silver trades at a
     * hundredth of gold, a second later, so the two symbols close their bars on different ticks.
     */
    private fun ticks(
        prices: List<Int>,
        symbols: List<String>,
    ): List<Tick> =
        prices.flatMapIndexed { bar, p ->
            val base = bar * 60_000L
            symbols.flatMap { sym ->
                val (price, lag) = if (sym == gold) BigDecimal(p) to 0L else BigDecimal(p).movePointLeft(2) to 1_000L
                listOf(Tick(sym, price, base + 1_000L + lag), Tick(sym, price + BigDecimal.ONE, base + 30_000L + lag))
            }
        }

    protected fun session(
        prices: List<Int>,
        identities: List<String> = listOf("brain"),
        symbols: List<String> = listOf(gold),
        timeframes: List<String> = listOf("1m"),
    ): BotRunSession {
        val streams = BotSessionStreams(symbols, timeframes)
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val strategies = BotSessionIdentities.compile(streams, identities, history = history)
        val engine =
            Backtest(
                strategies = strategies.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder),
                ticks = ticks(prices, symbols),
                candleWindow = streams.baseWindow,
                startingBalance = BigDecimal("10000"),
                instruments = goldSpec,
            ).toEngine()
        return BotRunSession("t", ReplayBotRunBackend(engine), strategies, streams, history, recorder)
    }

    protected fun intent(action: String) =
        "STRATEGY bot VERSION 1\n\nSYMBOLS\n    x = $gold EVERY 1m\n\nRULES\n    WHEN true\n    THEN $action\n"

    protected fun BotRunSession.drain(): BacktestResult {
        while (next(gold) != null) Unit
        return finish()!!
    }
}
