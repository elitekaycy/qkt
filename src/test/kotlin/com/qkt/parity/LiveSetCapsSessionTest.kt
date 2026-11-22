package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.cli.PerStrategyRisk
import com.qkt.cli.daemon.liveLimits
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.parity.DslParityState.TradeState
import com.qkt.parity.DslParityState.tradeState
import com.qkt.risk.SetCaps
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A set cap in a live session, through the session's own risk rules and real orders: the rotation set ranks two
 * contracts that both ask to be bought, and `max_open_positions: 1` on the set lets only one be held at a time.
 */
class LiveSetCapsSessionTest {
    private val id = "rotation"
    private val balance = BigDecimal("10000")

    /** The trades of one live run of the rotation set, with [risk] as its per-strategy limits. */
    private fun trades(risk: PerStrategyRisk?): List<TradeState> {
        val plan = DynamicSetFixtures.plan()
        val host = plan.strategy(emptyMap(), live = true) as DynamicSetHost
        val trades = mutableListOf<TradeState>()
        val handle =
            LiveSession(
                strategies = listOf(id to host),
                haltRules = emptyList(),
                source = LiveSetVenue(DynamicSetFixtures.tape(), emptyMap()),
                symbols = plan.ast.streams.map { it.qktSymbol },
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(T0),
                calendar = TradingCalendar.crypto(),
                perStrategyLimits = liveLimits(plan.ast, risk, host),
                onTrade = { trade, realized, owner -> trades += tradeState(owner, trade, realized) },
                initialBalance = balance,
                startingBalances = mapOf(id to balance),
            ).start()
        check(handle.awaitTermination(Duration.ofSeconds(20))) { "live session did not terminate" }
        return trades.toList()
    }

    /** The most contracts held at once over [trades]. */
    private fun mostHeld(trades: List<TradeState>): Int {
        val held = HashSet<String>()
        return trades.maxOfOrNull { trade ->
            if (trade.side == "BUY") held += trade.symbol else held -= trade.symbol
            held.size
        } ?: 0
    }

    @Test
    fun `a set cap of one open position refuses the second member's entry until the first is flat`() {
        val (free, capped) =
            BacktestLiveParityFixtures.withQuietParityLogs {
                trades(null) to trades(PerStrategyRisk(sets = mapOf("s" to SetCaps(maxOpenPositions = 1))))
            }

        assertThat(mostHeld(free)).isEqualTo(2)
        assertThat(capped.first().symbol).isEqualTo("BACKTEST:A")
        assertThat(mostHeld(capped)).isEqualTo(1)
    }
}
