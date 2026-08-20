package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The parity claim of the bot-sessions spec: an external client submitting a decision
 * after bar N through a session produces the exact same trade tape and equity as a
 * scripted in-engine strategy making the same decision on the same bar — because both
 * are Signals entering the identical pipeline over the identical feed.
 */
class BotSessionParityTest {
    private fun ticks(): List<Tick> =
        (0 until 8).flatMap { bar ->
            val base = bar * 60_000L
            listOf(
                Tick("XAUUSD", BigDecimal(2400 + bar), base + 1_000L),
                Tick("XAUUSD", BigDecimal(2402 + bar), base + 30_000L),
            )
        } + Tick("XAUUSD", BigDecimal("2412"), 8 * 60_000L + 1_000L)

    /**
     * Buys 1 lot on the first tick strictly after the [afterBars]-th bar close —
     * the session contract: a decision made after `next` returns bar N executes on
     * the first tick replayed after the tick that closed bar N.
     */
    private class ScriptedStrategy(
        private val afterBars: Int,
    ) : Strategy {
        private var closed = 0
        private var fired = false
        private var closingTick = false

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (closingTick) {
                closingTick = false
                return
            }
            if (!fired && closed >= afterBars) {
                fired = true
                emit(Signal.Buy("XAUUSD", BigDecimal.ONE))
            }
        }

        override fun onCandle(
            candle: Candle,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            closed++
            closingTick = true
        }
    }

    @Test
    fun `session-submitted decision is byte-identical to the same scripted in-engine decision`() {
        val afterBars = 2

        val scripted =
            Backtest(
                strategies = listOf("brain" to ScriptedStrategy(afterBars)),
                ticks = ticks(),
                candleWindow = TimeWindow.parse("1m"),
                startingBalance = BigDecimal("10000"),
            ).run()

        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridge = BotBridgeStrategy()
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks(),
                candleWindow = TimeWindow.parse("1m"),
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val session =
            BotRunSession(
                runId = "parity",
                backend = ReplayBotRunBackend(engine),
                bridges = mapOf("brain" to bridge),
                history = history,
                recorder = recorder,
            )
        repeat(afterBars) { session.next("XAUUSD") }
        session.submit("brain", Signal.Buy("XAUUSD", BigDecimal.ONE))
        while (session.next("XAUUSD") != null) {
            // drain the window so both runs cover the same data
        }
        val viaSession = session.finish() ?: error("expected a result")

        assertThat(viaSession.trades).hasSameSizeAs(scripted.trades)
        viaSession.trades.zip(scripted.trades).forEach { (a, b) ->
            assertThat(a.trade.symbol).isEqualTo(b.trade.symbol)
            assertThat(a.trade.side).isEqualTo(b.trade.side)
            assertThat(a.trade.quantity).isEqualByComparingTo(b.trade.quantity)
            assertThat(a.trade.price).isEqualByComparingTo(b.trade.price)
            assertThat(a.trade.timestamp).isEqualTo(b.trade.timestamp)
            assertThat(a.strategyId).isEqualTo(b.strategyId)
        }
        assertThat(viaSession.global.totalPnL).isEqualByComparingTo(scripted.global.totalPnL)
    }
}
