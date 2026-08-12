package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DataStore
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.EveryNthTickBuyStrategy
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `fromStore seeds derived DSL warmup before the requested window`() {
        val delegate = DefaultDataStore(root = sample)
        val store =
            object : DataStore {
                override val root: Path = sample

                override fun manifest(symbol: String) = delegate.manifest(symbol.substringAfter(':'))

                override fun dayFile(
                    symbol: String,
                    day: java.time.LocalDate,
                ) = delegate.dayFile(symbol.substringAfter(':'), day)

                override fun openFeed(request: MarketRequest) =
                    delegate.openFeed(request.copy(symbols = request.symbols.map { it.substringAfter(':') }))

                override fun resolveRange(request: MarketRequest) =
                    delegate.resolveRange(request.copy(symbols = request.symbols.map { it.substringAfter(':') }))

                override fun prefetch(request: MarketRequest) =
                    delegate.prefetch(request.copy(symbols = request.symbols.map { it.substringAfter(':') }))

                override fun rebuildManifests() = delegate.rebuildManifests()
            }
        val parsed =
            Dsl.parse(
                """
                STRATEGY warm_cli VERSION 1
                SYMBOLS
                    eur = BACKTEST:EURUSD EVERY 1m WARMUP 2 BARS
                RULES
                    WHEN eur.close > 0 AND POSITION.eur = 0 THEN BUY eur SIZING 1
                """.trimIndent(),
            ) as ParseResult.Success
        val strategy = AstCompiler().compile(parsed.value)
        val from = Instant.parse("2024-01-15T00:03:00Z")
        val to = Instant.parse("2024-01-15T00:05:00Z")

        val result =
            Backtest
                .fromStore(
                    strategies = listOf("warm_cli" to strategy),
                    store = store,
                    request = MarketRequest(listOf("BACKTEST:EURUSD"), from, to),
                    candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.trades).hasSize(1)
        assertThat(
            result.trades
                .single()
                .trade.timestamp,
        ).isEqualTo(from.toEpochMilli() + 60_000L)
        assertThat(result.inputSummary?.streamCandles)
            .containsEntry("BACKTEST:EURUSD:1m", 2L)
        assertThat(result.inputSummary?.strategyCandleEvaluations)
            .containsEntry("warm_cli:eur:BACKTEST:EURUSD:1m", 2L)
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
    fun `BTCUSD empty requested range fails closed`() {
        val store = DefaultDataStore(root = sample)
        val request =
            MarketRequest(
                symbols = listOf("BTCUSD"),
                from = Instant.parse("2024-01-16T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        assertThatThrownBy {
            Backtest
                .fromStore(
                    strategies = listOf("test" to EveryNthTickBuyStrategy(symbol = "BTCUSD", n = 1)),
                    rules = emptyList(),
                    store = store,
                    request = request,
                )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no market data for BTCUSD")
    }
}
