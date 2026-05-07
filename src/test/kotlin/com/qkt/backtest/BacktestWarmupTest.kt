package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestWarmupTest {
    private val day14 = Instant.parse("2024-01-14T00:00:00Z").toEpochMilli()
    private val day15 = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    private fun candle(
        close: String,
        startMs: Long,
    ): Candle =
        Candle(
            "X",
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of("1"),
            startMs,
            startMs + 60_000L,
        )

    private class CountingStrategy : Strategy {
        var ticks: Int = 0

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            ticks++
        }
    }

    private class TicksCapableSource(
        private val historical: MutableMap<String, MutableList<Tick>> = mutableMapOf(),
    ) : InMemoryMarketSource() {
        override val capabilities: Set<MarketSourceCapability> =
            setOf(
                MarketSourceCapability.LIVE_TICKS,
                MarketSourceCapability.BARS,
                MarketSourceCapability.TICKS,
            )

        fun seedHistoricalTicks(
            symbol: String,
            ticks: List<Tick>,
        ) {
            historical.getOrPut(symbol) { mutableListOf() }.addAll(ticks)
        }

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> =
            (historical[symbol] ?: emptyList())
                .asSequence()
                .filter { it.timestamp >= range.from.toEpochMilli() && it.timestamp < range.to.toEpochMilli() }
    }

    private fun seedSource(): TicksCapableSource {
        val src = TicksCapableSource()
        val warmupCandles = (0 until 30).map { i -> candle((100 + i).toString(), day14 + i * 60_000L) }
        src.seedBars("X", TimeWindow.ONE_MINUTE, warmupCandles)
        src.seedHistoricalTicks("X", listOf(Tick("X", Money.of("130"), day15 + 1_000L)))
        return src
    }

    @Test
    fun `WarmupSpec None backtest is bit identical to no warmup`() {
        val src = seedSource()
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.ofEpochMilli(day15),
                to = Instant.ofEpochMilli(day15 + 60_000L),
            )
        val a =
            Backtest
                .fromSource(
                    strategies = listOf("test" to CountingStrategy()),
                    source = src,
                    request = request,
                    warmupSpec = WarmupSpec.None,
                ).run()
        val b =
            Backtest
                .fromSource(
                    strategies = listOf("test" to CountingStrategy()),
                    source = src,
                    request = request,
                    warmupSpec = WarmupSpec.None,
                ).run()
        assertThat(a.global.tradeCount).isEqualTo(b.global.tradeCount)
        assertThat(a.global.totalPnL).isEqualByComparingTo(b.global.totalPnL)
    }

    @Test
    fun `WarmupSpec Bars feeds warmup ticks before main loop`() {
        val src = seedSource()
        val strategy = CountingStrategy()
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.ofEpochMilli(day15),
                to = Instant.ofEpochMilli(day15 + 60_000L),
            )

        Backtest
            .fromSource(
                strategies = listOf("test" to strategy),
                source = src,
                request = request,
                warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30),
            ).run()

        assertThat(strategy.ticks).isEqualTo(1)
    }

    @Test
    fun `Bars warmup backtest is deterministic across two runs`() {
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.ofEpochMilli(day15),
                to = Instant.ofEpochMilli(day15 + 60_000L),
            )

        fun runOnce(): BacktestResult {
            val src = seedSource()
            return Backtest
                .fromSource(
                    strategies = listOf("test" to CountingStrategy()),
                    source = src,
                    request = request,
                    warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30),
                ).run()
        }

        val a = runOnce()
        val b = runOnce()
        assertThat(a.global.tradeCount).isEqualTo(b.global.tradeCount)
        assertThat(a.global.totalPnL).isEqualByComparingTo(b.global.totalPnL)
        assertThat(a.global.realizedTotal).isEqualByComparingTo(b.global.realizedTotal)
        assertThat(a.global.unrealizedTotal).isEqualByComparingTo(b.global.unrealizedTotal)
        assertThat(a.global.maxDrawdown).isEqualByComparingTo(b.global.maxDrawdown)
    }
}
