package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetFixtures.Line
import com.qkt.events.SetMembershipChanged
import com.qkt.marketdata.Tick
import com.qkt.parity.DslParityState.TradeState
import com.qkt.parity.DslParityState.tradeState
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A `DYNAMIC` set's members join, stick while held and are released identically in a backtest and a live session
 * over the same catalog and ticks: the same membership journal at the same window closes, and the same trades.
 */
class DynamicSetParityTest {
    private val id = "rotation"
    private val balance = BigDecimal("10000")

    private class Run(
        val journal: List<Line>,
        val trades: List<TradeState>,
    )

    private fun backtest(
        source: String,
        ticks: List<Tick>,
    ): Run {
        val journal = mutableListOf<Line>()
        val strategy = DynamicSetFixtures.plan(source).strategy(emptyMap()) { journal += DynamicSetFixtures.line(it) }
        val result =
            Backtest(
                strategies = listOf(id to strategy),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
                initialTimestamp = ticks.first().timestamp,
                startingBalance = balance,
                startingBalances = mapOf(id to balance),
            ).run()
        return Run(journal, result.trades.map { tradeState(it.strategyId, it.trade, it.realized) })
    }

    private fun live(
        source: String,
        ticks: List<Tick>,
    ): Run {
        val clock = FixedClock(ticks.first().timestamp)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = mutableListOf<Line>()
        bus.subscribe<SetMembershipChanged> { journal += DynamicSetFixtures.line(it) }
        val trades = mutableListOf<TradeState>()
        val handle =
            LiveSession(
                strategies = listOf(id to DynamicSetFixtures.plan(source).strategy(emptyMap())),
                haltRules = emptyList(),
                source = TapeSource(ticks, emptyList(), emptyMap()),
                symbols = ticks.map { it.symbol }.distinct(),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                onTrade = { trade, realized, owner -> trades += tradeState(owner, trade, realized) },
                initialBalance = balance,
                startingBalances = mapOf(id to balance),
                busOverride = bus,
            ).start()
        check(handle.awaitTermination(Duration.ofSeconds(10))) { "live parity session did not terminate" }
        return Run(journal.toList(), trades.toList())
    }

    @Test
    fun `members join, stick while held and are released alike in a backtest and live`() {
        val (backtest, live) = both(DynamicSetFixtures.source, DynamicSetFixtures.tape())

        assertThat(backtest.journal).containsExactlyElementsOf(DynamicSetFixtures.expectedJournal)
        assertThat(live.journal).containsExactlyElementsOf(backtest.journal)
        assertThat(live.trades).containsExactlyElementsOf(backtest.trades)
        assertThat(backtest.trades.map { it.symbol to it.side }).containsExactly(
            "BACKTEST:A" to "BUY",
            "BACKTEST:B" to "BUY",
            "BACKTEST:A" to "SELL",
        )
        val sell = backtest.trades.last()
        assertThat(sell.timestamp).isBetween(DynamicSetFixtures.T0 + 6 * 60_000L, DynamicSetFixtures.T0 + 7 * 60_000L)
    }

    @Test
    fun `a set ranked by traded volume rotates alike in a backtest and live, from the figures before each close`() {
        val (backtest, live) = both(DynamicSetFixtures.marketSource, DynamicSetFixtures.marketTape())

        assertThat(backtest.journal).containsExactlyElementsOf(DynamicSetFixtures.expectedMarketJournal)
        assertThat(live.journal).containsExactlyElementsOf(backtest.journal)
        assertThat(live.trades).containsExactlyElementsOf(backtest.trades)
        assertThat(backtest.trades.map { it.symbol to it.side }).containsExactlyInAnyOrder(
            "BACKTEST:A" to "BUY",
            "BACKTEST:A" to "SELL",
            "BACKTEST:B" to "BUY",
        )
    }

    private fun both(
        source: String,
        ticks: List<Tick>,
    ): Pair<Run, Run> =
        BacktestLiveParityFixtures.withQuietParityLogs { backtest(source, ticks) to live(source, ticks) }
}
