package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionMultiVendorTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    private class CapturingStrategy : Strategy {
        val seen: MutableList<Tick> = mutableListOf()

        override fun onTick(
            tick: Tick,
            emit: (Signal) -> Unit,
        ) {
            seen.add(tick)
        }
    }

    @Test
    fun `LiveSession routes per-symbol subscriptions to the right vendor`() {
        val tv = InMemoryMarketSource(name = "TV")
        tv.seedLive(
            "OANDA:EURUSD",
            listOf(Tick("OANDA:EURUSD", Money.of("1.10"), now.toEpochMilli())),
        )

        val binance = InMemoryMarketSource(name = "Binance")
        binance.seedLive(
            "BINANCE:BTCUSDT",
            listOf(Tick("BINANCE:BTCUSDT", Money.of("60000"), now.plus(Duration.ofSeconds(1)).toEpochMilli())),
        )

        val composite =
            CompositeMarketSource(
                routes =
                    listOf(
                        SymbolPattern.prefix("OANDA:") to tv,
                        SymbolPattern.prefix("BINANCE:") to binance,
                    ),
                fallback = tv,
            )

        val strategy = CapturingStrategy()
        LiveSession(
            strategies = listOf(strategy),
            rules = emptyList(),
            source = composite,
            symbols = listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = FixedClock(time = now.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
        ).start().awaitTermination(Duration.ofSeconds(2))

        assertThat(strategy.seen.map { it.symbol })
            .containsExactlyInAnyOrder("OANDA:EURUSD", "BINANCE:BTCUSDT")
    }

    @Test
    fun `multi vendor fan in returns ticks in arrival order across vendors`() {
        val tv = InMemoryMarketSource(name = "TV")
        tv.seedLive(
            "OANDA:EURUSD",
            listOf(
                Tick("OANDA:EURUSD", Money.of("1.10"), now.toEpochMilli()),
                Tick("OANDA:EURUSD", Money.of("1.11"), now.plus(Duration.ofSeconds(2)).toEpochMilli()),
            ),
        )

        val binance = InMemoryMarketSource(name = "Binance")
        binance.seedLive(
            "BINANCE:BTCUSDT",
            listOf(
                Tick("BINANCE:BTCUSDT", Money.of("60000"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
                Tick("BINANCE:BTCUSDT", Money.of("60500"), now.plus(Duration.ofSeconds(3)).toEpochMilli()),
            ),
        )

        val composite =
            CompositeMarketSource(
                routes =
                    listOf(
                        SymbolPattern.prefix("OANDA:") to tv,
                        SymbolPattern.prefix("BINANCE:") to binance,
                    ),
                fallback = tv,
            )

        val strategy = CapturingStrategy()
        LiveSession(
            strategies = listOf(strategy),
            rules = emptyList(),
            source = composite,
            symbols = listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = FixedClock(time = now.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
        ).start().awaitTermination(Duration.ofSeconds(2))

        assertThat(strategy.seen.map { it.symbol }).containsExactly(
            "OANDA:EURUSD",
            "BINANCE:BTCUSDT",
            "OANDA:EURUSD",
            "BINANCE:BTCUSDT",
        )
    }
}
