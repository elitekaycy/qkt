package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val day14 = Instant.parse("2024-01-14T00:00:00Z").toEpochMilli()

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

    private class CapturingStrategy : Strategy {
        val seen: MutableList<Tick> = mutableListOf()

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            seen.add(tick)
        }
    }

    @Test
    fun `start drives strategies with live ticks`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val strategy = CapturingStrategy()
        val clock = FixedClock(time = now.toEpochMilli())
        val session =
            LiveSession(
                strategies = listOf("test" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
            )

        val handle = session.start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(strategy.seen.map { it.price.compareTo(Money.of("100")) }.first()).isEqualTo(0)
        assertThat(strategy.seen).hasSize(2)
    }

    @Test
    fun `running becomes false after stop`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), now.toEpochMilli())))
        val session =
            LiveSession(
                strategies = emptyList(),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            )

        val handle = session.start()
        handle.stop()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(handle.running).isFalse()
    }

    @Test
    fun `effective warmup spec is widest among Warmable strategies`() {
        val src = InMemoryMarketSource()
        val warmupStart = now.minusSeconds(10 * 60).toEpochMilli()
        src.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            (0 until 10).map { i -> candle((100 + i).toString(), warmupStart + i * 60_000L) },
        )
        src.seedLive("X", listOf(Tick("X", Money.of("999"), now.toEpochMilli())))

        val small =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 3)

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val large =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 10)

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }

        val seenWarmup = mutableListOf<Tick>()
        val session =
            LiveSession(
                strategies = listOf("small" to small, "large" to large),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                onWarmupTick = { t -> seenWarmup.add(t) },
            )

        session.start().awaitTermination(Duration.ofSeconds(2))

        assertThat(seenWarmup).hasSize(10)
    }

    @Test
    fun `warmupOverride beats inferred Warmable specs`() {
        val src = InMemoryMarketSource()
        val warmupStart = now.minusSeconds(50 * 60).toEpochMilli()
        src.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            (0 until 50).map { i -> candle((100 + i).toString(), warmupStart + i * 60_000L) },
        )
        src.seedLive("X", listOf(Tick("X", Money.of("999"), now.toEpochMilli())))

        val warm =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 5)

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }

        val seenWarmup = mutableListOf<Tick>()
        val session =
            LiveSession(
                strategies = listOf("warm" to warm),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                warmupOverride = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30),
                onWarmupTick = { t -> seenWarmup.add(t) },
            )
        session.start().awaitTermination(Duration.ofSeconds(2))

        assertThat(seenWarmup).hasSize(30)
    }

    @Test
    fun `recentTrades returns the trades captured so far`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("X", Money.of("1")))
                }
            }
        val handle =
            LiveSession(
                strategies = listOf("test" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            ).start()
        handle.awaitTermination(Duration.ofSeconds(2))

        assertThat(handle.recentTrades().size).isEqualTo(2)
    }
}
