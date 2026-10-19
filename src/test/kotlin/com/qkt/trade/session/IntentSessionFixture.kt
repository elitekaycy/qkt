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

    /** One tick at +1 s and one at +30 s of each 1m bar, at [prices] (one per bar). */
    private fun ticks(prices: List<Int>): List<Tick> =
        prices.flatMapIndexed { bar, p ->
            val base = bar * 60_000L
            listOf(Tick(gold, BigDecimal(p), base + 1_000L), Tick(gold, BigDecimal(p), base + 30_000L))
        }

    protected fun session(
        prices: List<Int>,
        identities: List<String> = listOf("brain"),
    ): BotRunSession {
        val streams = BotSessionStreams(listOf(gold), listOf("1m"))
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val strategies = BotSessionIdentities.compile(streams, identities)
        val engine =
            Backtest(
                strategies = strategies.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder),
                ticks = ticks(prices),
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
