package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.parity.BotSessionLiveArm.runLiveSession
import com.qkt.parity.BotSessionParityScript.SessionTrade
import com.qkt.parity.BotSessionParityScript.decisions
import com.qkt.parity.BotSessionParityScript.key
import com.qkt.parity.BotSessionParityScript.symbol
import com.qkt.parity.BotSessionParityScript.ticks
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotBridgeStrategy
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.ReplayBotRunBackend
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The gating parity claim for external (ML/agent) strategies: a bot run session in
 * BACKTEST mode (ReplayBotRunBackend over a paced ReplayEngine) and one in LIVE mode
 * (LiveBotRunBackend over a running LiveSession on a paper broker) produce the exact
 * same trade tape when the identical client script makes the identical decisions on
 * the identical ticks.
 *
 * The spec (2026-08-19-bot-run-sessions-design.md §9) allows intent-timing skew of up
 * to one bar for live sessions because live intents land at wall-clock arrival. This
 * test removes that freedom deterministically: the live feed is gated tick-by-tick and
 * released in lockstep with the client's own decision loop (see [runLiveSession]), so
 * every submit lands between the same two ticks as in the backtest arm. Under that
 * pacing the two modes must be byte-identical; any divergence is an engine bug, not
 * timing skew.
 */
class BotSessionBacktestLiveParityTest {
    /** Backtest arm: replay backend; each next() advances the engine one closed bar. */
    private fun runBacktestSession(): List<SessionTrade> {
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridge = BotBridgeStrategy()
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks(),
                candleWindow = TimeWindow.parse("1m"),
                initialTimestamp = ticks().first().timestamp,
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val session =
            BotRunSession(
                runId = "parity-backtest",
                backend = ReplayBotRunBackend(engine),
                bridges = mapOf("brain" to bridge),
                history = history,
                recorder = recorder,
            )
        var closedBars = 0
        while (session.next(symbol) != null) {
            closedBars++
            decisions()[closedBars]?.let { session.submit("brain", it) }
        }
        val result = session.finish() ?: error("backtest session must yield a result")
        return result.trades.map { it.trade.key() }
    }

    @Test
    fun `bot session trades are identical in backtest mode and live-paper mode`() {
        val backtest = runBacktestSession()
        val live = runLiveSession()

        assertThat(backtest).isNotEmpty()
        assertThat(live).hasSameSizeAs(backtest)
        live.zip(backtest).forEach { (l, b) ->
            assertThat(l.symbol).isEqualTo(b.symbol)
            assertThat(l.side).isEqualTo(b.side)
            assertThat(l.quantity).isEqualByComparingTo(b.quantity)
            assertThat(l.price).isEqualByComparingTo(b.price)
            assertThat(l.timestamp).isEqualTo(b.timestamp)
        }
    }
}
