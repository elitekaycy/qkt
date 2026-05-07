package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.EveryNthTickBuyStrategy
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestFromStoreTest {
    private val sample = Path.of("data/sample")

    @Test
    fun `fromStore wires DataStore end to end against sample data`() {
        val store = DefaultDataStore(root = sample)
        val request =
            MarketRequest(
                symbols = listOf("EURUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest
                .fromStore(
                    strategies =
                        listOf(
                            "test" to EveryNthTickBuyStrategy(symbol = "EURUSD", n = 3, size = Money.of("1")),
                        ),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.global.tradeCount).isGreaterThan(0)
    }

    @Test
    fun `fromStore over multiple symbols interleaves trades by timestamp`() {
        val store = DefaultDataStore(root = sample)
        val request =
            MarketRequest(
                symbols = listOf("EURUSD", "XAUUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest
                .fromStore(
                    strategies =
                        listOf(
                            "eur" to EveryNthTickBuyStrategy(symbol = "EURUSD", n = 1),
                            "xau" to EveryNthTickBuyStrategy(symbol = "XAUUSD", n = 1),
                        ),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.global.tradeCount).isEqualTo(20)
    }

    @Test
    fun `running same backtest twice produces identical result`() {
        val store = DefaultDataStore(root = sample)
        val request =
            MarketRequest(
                symbols = listOf("EURUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-16T00:00:00Z"),
            )

        fun runOnce() =
            Backtest
                .fromStore(
                    strategies = listOf("test" to EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2)),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        val a = runOnce()
        val b = runOnce()
        assertThat(b.global.tradeCount).isEqualTo(a.global.tradeCount)
        assertThat(b.global.totalPnL).isEqualByComparingTo(a.global.totalPnL)
        assertThat(b.global.maxDrawdown).isEqualByComparingTo(a.global.maxDrawdown)
    }

    @Test
    fun `fromStore with null from to runs over intersection of cached ranges`() {
        val store = DefaultDataStore(root = sample)
        val request = MarketRequest(symbols = listOf("EURUSD"))
        val result =
            Backtest
                .fromStore(
                    strategies = listOf("test" to EveryNthTickBuyStrategy(symbol = "EURUSD", n = 1)),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.global.tradeCount).isEqualTo(10)
    }

    @Test
    fun `BTCUSD empty Saturday produces no fills for that day`() {
        val store = DefaultDataStore(root = sample)
        val request =
            MarketRequest(
                symbols = listOf("BTCUSD"),
                from = Instant.parse("2024-01-16T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest
                .fromStore(
                    strategies = listOf("test" to EveryNthTickBuyStrategy(symbol = "BTCUSD", n = 1)),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.global.tradeCount).isEqualTo(0)
    }
}
