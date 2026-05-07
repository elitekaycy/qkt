package com.qkt.backtest

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.EveryNthTickBuyStrategy
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestFromSourceTest {
    private val sample = Path.of("data/sample")
    private val clockAt = FixedClock(time = Instant.parse("2024-01-17T00:00:00Z").toEpochMilli())

    @Test
    fun `fromSource matches fromStore output for the same data`() {
        val store = DefaultDataStore(root = sample, clock = clockAt)
        val source = LocalMarketSource(store, clockAt)
        val request =
            MarketRequest(
                symbols = listOf("EURUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )

        val viaSource =
            Backtest
                .fromSource(
                    strategies =
                        listOf(
                            "test" to EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2, size = Money.of("1")),
                        ),
                    source = source,
                    request = request,
                ).run()

        val viaStore =
            Backtest
                .fromStore(
                    strategies =
                        listOf(
                            "test" to EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2, size = Money.of("1")),
                        ),
                    store = store,
                    request = request,
                ).run()

        assertThat(viaSource.global.tradeCount).isEqualTo(viaStore.global.tradeCount)
        assertThat(viaSource.global.totalPnL).isEqualByComparingTo(viaStore.global.totalPnL)
    }
}
