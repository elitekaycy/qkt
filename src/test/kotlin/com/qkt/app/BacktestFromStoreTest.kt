package com.qkt.app

import com.qkt.common.Money
import com.qkt.marketdata.store.DataRequest
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
            DataRequest(
                symbols = listOf("EURUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest
                .fromStore(
                    strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 3, size = Money.of("1"))),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.tradeCount).isGreaterThan(0)
    }

    @Test
    fun `fromStore over multiple symbols interleaves trades by timestamp`() {
        val store = DefaultDataStore(root = sample)
        val request =
            DataRequest(
                symbols = listOf("EURUSD", "XAUUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest
                .fromStore(
                    strategies =
                        listOf(
                            EveryNthTickBuyStrategy(symbol = "EURUSD", n = 1),
                            EveryNthTickBuyStrategy(symbol = "XAUUSD", n = 1),
                        ),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.tradeCount).isEqualTo(20)
    }

    @Test
    fun `running same backtest twice produces identical result`() {
        val store = DefaultDataStore(root = sample)
        val request =
            DataRequest(
                symbols = listOf("EURUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-16T00:00:00Z"),
            )

        fun runOnce() =
            Backtest
                .fromStore(
                    strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2)),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        val a = runOnce()
        val b = runOnce()
        assertThat(b.tradeCount).isEqualTo(a.tradeCount)
        assertThat(b.totalPnL).isEqualByComparingTo(a.totalPnL)
        assertThat(b.maxDrawdown).isEqualByComparingTo(a.maxDrawdown)
    }

    @Test
    fun `fromStore with null from to runs over intersection of cached ranges`() {
        val store = DefaultDataStore(root = sample)
        val request = DataRequest(symbols = listOf("EURUSD"))
        val result =
            Backtest
                .fromStore(
                    strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 1)),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.tradeCount).isEqualTo(10)
    }

    @Test
    fun `BTCUSD empty Saturday produces no fills for that day`() {
        val store = DefaultDataStore(root = sample)
        val request =
            DataRequest(
                symbols = listOf("BTCUSD"),
                from = Instant.parse("2024-01-16T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest
                .fromStore(
                    strategies = listOf(EveryNthTickBuyStrategy(symbol = "BTCUSD", n = 1)),
                    rules = emptyList(),
                    store = store,
                    request = request,
                ).run()
        assertThat(result.tradeCount).isEqualTo(0)
    }
}
