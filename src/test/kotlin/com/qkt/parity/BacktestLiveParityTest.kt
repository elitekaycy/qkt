package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.execution.Trade
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.risk.RunawayBreakerRule
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Asserts that `Backtest` and `LiveSession` produce identical trade lists, final positions,
 * and total realized PnL when fed the same tick sequence through the same compiled strategy.
 *
 * The backtest path is foundational for investor-facing reports — this test enforces that
 * the report's contents reflect what live paper-trading would do given identical ticks.
 */
class BacktestLiveParityTest {
    private val symbol = "BTCUSDT"
    private val initialTs = 1_700_000_000_000L

    private fun ticks(): List<Tick> {
        // 60 ticks: rising 10000 → 10300 with two retracements deep enough to trigger sells.
        // Strategy buys when step % 12 == 0 and sells when step % 12 == 6.
        return (0 until 60).map { i ->
            val cycle = (i / 12) * 100
            val intra = if (i % 12 < 6) i % 12 * 10 else (12 - i % 12) * 10
            Tick(symbol, Money.of((10000 + cycle + intra).toString()), initialTs + i * 60_000L)
        }
    }

    private fun makeStrategy(): Strategy =
        object : Strategy {
            private var step = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                val s = step++
                when {
                    s % 12 == 0 -> emit(Signal.Buy(symbol, Money.of("0.1")))
                    s % 12 == 6 -> emit(Signal.Sell(symbol, Money.of("0.1")))
                }
            }
        }

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

    private fun <T> withQuietParityLogs(block: () -> T): T {
        val loggers =
            listOf(
                "com.qkt.app.LiveSession",
                "com.qkt.app.OrderManager",
                "com.qkt.app.TradingPipeline",
                "com.qkt.risk.RiskEngine",
            ).map { LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger }
        val previous = loggers.map { it.level }
        loggers.forEach { it.level = ch.qos.logback.classic.Level.ERROR }
        return try {
            block()
        } finally {
            loggers.zip(previous).forEach { (logger, level) -> logger.level = level }
        }
    }

    // The engine clock is driven by the tick being PROCESSED (LiveSession advances a MutableClock in
    // its consumer loop), so the feed just returns ticks — it no longer touches the clock.
    private class TickListFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            return if (i >= ticks.size) null else ticks[i]
        }

        override fun close() = Unit
    }

    private class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "ParityFake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = TickListFeed(ticks)
    }

    @Test
    fun `backtest and live-paper produce identical trades on the same ticks`() {
        val tickSeq = ticks()
        val startingBalance = BigDecimal("10000")

        val backtestResult =
            Backtest(
                strategies = listOf("s" to makeStrategy()),
                ticks = tickSeq,
                initialTimestamp = tickSeq.first().timestamp,
                startingBalance = startingBalance,
            ).run()

        val liveTrades = mutableListOf<Trade>()
        val liveClock = FixedClock(time = tickSeq.first().timestamp)
        val session =
            LiveSession(
                strategies = listOf("s" to makeStrategy()),
                source = FakeSource(tickSeq),
                symbols = listOf(symbol),
                clock = liveClock,
                onTrade = { trade, _, _ -> liveTrades.add(trade) },
            ).start()
        check(session.awaitTermination(Duration.ofSeconds(10))) { "live session did not terminate" }

        val backtestTrades = backtestResult.trades.map { it.trade }

        assertThat(backtestTrades).isNotEmpty
        assertThat(liveTrades).hasSize(backtestTrades.size)
        for (i in backtestTrades.indices) {
            val b = backtestTrades[i]
            val l = liveTrades[i]
            assertThat(l.symbol).isEqualTo(b.symbol)
            assertThat(l.side).isEqualTo(b.side)
            assertThat(l.quantity).isEqualByComparingTo(b.quantity)
            assertThat(l.price).isEqualByComparingTo(b.price)
            assertThat(l.timestamp).isEqualTo(b.timestamp)
            assertThat(l.orderId).isEqualTo(b.orderId)
        }
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

    @Test
    fun `compiled candle indicator bracket has full-state parity`() {
        val dsl =
            """
            STRATEGY candle_bracket VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN ema(btc.close, 2) CROSSES ABOVE ema(btc.close, 3)
              THEN BUY btc BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 3 }
              WHEN ema(btc.close, 2) CROSSES BELOW ema(btc.close, 3)
              THEN CLOSE btc
            """.trimIndent()
        val prices = listOf("100", "99", "98", "99", "101", "104", "103", "101", "98", "97", "97")
        val tape =
            prices.mapIndexed { index, price ->
                Tick("BACKTEST:BTCUSDT", Money.of(price), initialTs + index * 60_000L)
            }
        val firstWindowStart = initialTs - Math.floorMod(initialTs, 60_000L)
        val warmupCandles =
            (3 downTo 1).map { offset ->
                Candle(
                    symbol = "BACKTEST:BTCUSDT",
                    open = BigDecimal("100"),
                    high = BigDecimal("100"),
                    low = BigDecimal("100"),
                    close = BigDecimal("100"),
                    volume = BigDecimal.ZERO,
                    startTime = firstWindowStart - offset * 60_000L,
                    endTime = firstWindowStart - (offset - 1) * 60_000L,
                )
            }

        val result = DslParityHarness.run("candle_bracket", dsl, tape, warmupCandles)

        assertThat(result.backtest.trades).isNotEmpty
        assertThat(result.live).isEqualTo(result.backtest)
    }
}
