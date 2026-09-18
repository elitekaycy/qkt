package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.parity.BacktestLiveParityFixtures.FakeSource
import com.qkt.parity.BacktestLiveParityFixtures.initialTs
import com.qkt.parity.BacktestLiveParityFixtures.symbol
import com.qkt.parity.BacktestLiveParityFixtures.withQuietParityLogs
import com.qkt.risk.RunawayBreakerRule
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunawayBreakerReplayParityTest {
    private fun rapidRoundTripStrategy(): Strategy =
        object : Strategy {
            private var buy = true

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (buy) {
                    emit(Signal.Buy(symbol, Money.of("1")))
                } else {
                    emit(Signal.Sell(symbol, Money.of("1")))
                }
                buy = !buy
            }
        }

    private fun generatedStrategy(actions: IntArray): Strategy =
        object : Strategy {
            private var index = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                when (actions[index++]) {
                    1 -> emit(Signal.Buy(symbol, Money.of("1")))
                    2 -> emit(Signal.Sell(symbol, Money.of("1")))
                }
            }
        }

    private fun generatedCase(seed: Long): Pair<List<Tick>, IntArray> {
        var state = seed.takeIf { it != 0L } ?: -7046029254386353131L

        fun next(): Long {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return state
        }

        val actions = IntArray(16)
        actions[0] = 1
        actions[1] = 2
        for (index in 2 until actions.size) actions[index] = Math.floorMod(next(), 5L).toInt().coerceAtMost(2)
        val generatedTicks =
            actions.indices.map { index ->
                val cents = 10_000L + Math.floorMod(next(), 2_000L)
                Tick(
                    symbol,
                    BigDecimal.valueOf(cents, 2),
                    initialTs + index * 1_000L,
                )
            }
        return generatedTicks to actions
    }

    @Test
    fun `replay reports live breaker divergence and strict mode matches live`() {
        val tickSeq =
            (0 until 10).map { i ->
                Tick(symbol, Money.of((100 + i).toString()), initialTs + i * 1_000L)
            }
        val observed =
            Backtest(
                strategies = listOf("fast" to rapidRoundTripStrategy()),
                ticks = tickSeq,
                initialTimestamp = initialTs,
                runawayMaxRoundTrips = 2,
                runawayMaxRejections = 0,
            ).run()

        assertThat(observed.trades).hasSize(10)
        assertThat(observed.halts).isEmpty()
        val observedBreaker = requireNotNull(observed.runawayBreaker)
        assertThat(observedBreaker.enforceLiveBreakers).isFalse()
        assertThat(observedBreaker.trips).hasSize(1)
        assertThat(observedBreaker.trips.single().rule).isEqualTo(RunawayBreakerRule.ROUND_TRIPS)
        assertThat(observedBreaker.trips.single().count).isEqualTo(3)

        val strict =
            Backtest(
                strategies = listOf("fast" to rapidRoundTripStrategy()),
                ticks = tickSeq,
                initialTimestamp = initialTs,
                enforceLiveBreakers = true,
                runawayMaxRoundTrips = 2,
                runawayMaxRejections = 0,
            ).run()
        val liveTrades = mutableListOf<Trade>()
        val live =
            LiveSession(
                strategies = listOf("fast" to rapidRoundTripStrategy()),
                source = FakeSource(tickSeq),
                symbols = listOf(symbol),
                clock = FixedClock(initialTs),
                runawayMaxRoundTrips = 2,
                runawayMaxRejections = 0,
                onTrade = { trade, _, _ -> liveTrades.add(trade) },
            ).start()
        check(live.awaitTermination(Duration.ofSeconds(10))) { "live session did not terminate" }

        assertThat(strict.runawayBreaker!!.enforceLiveBreakers).isTrue()
        assertThat(strict.halts).hasSize(1)
        assertThat(strict.trades.map { it.trade }).containsExactlyElementsOf(liveTrades)
        assertThat(strict.trades).hasSizeLessThan(observed.trades.size)
    }

    @Test
    fun `strict replay matches live across 500 generated tick and signal cases`() {
        withQuietParityLogs {
            for (seed in 1L..500L) {
                val (tickSeq, actions) = generatedCase(seed)
                val threshold = 1 + (seed % 4).toInt()
                val backtestTrades =
                    Backtest(
                        strategies = listOf("generated" to generatedStrategy(actions.copyOf())),
                        ticks = tickSeq,
                        initialTimestamp = initialTs,
                        enforceLiveBreakers = true,
                        runawayMaxRoundTrips = threshold,
                        runawayMaxRejections = 0,
                    ).run()
                        .trades
                        .map { it.trade }
                val liveTrades = mutableListOf<Trade>()
                val live =
                    LiveSession(
                        strategies = listOf("generated" to generatedStrategy(actions.copyOf())),
                        source = FakeSource(tickSeq),
                        symbols = listOf(symbol),
                        clock = FixedClock(initialTs),
                        runawayMaxRoundTrips = threshold,
                        runawayMaxRejections = 0,
                        onTrade = { trade, _, _ -> liveTrades.add(trade) },
                    ).start()
                check(live.awaitTermination(Duration.ofSeconds(2))) { "live session did not terminate for seed $seed" }

                assertThat(liveTrades)
                    .`as`("strict breaker parity for generated seed %s", seed)
                    .containsExactlyElementsOf(backtestTrades)
            }
        }
    }
}
