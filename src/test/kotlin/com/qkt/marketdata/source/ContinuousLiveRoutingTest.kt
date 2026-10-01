package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Live ticks of continuous streams come from their live feeds; every other symbol's from the account, as before. */
class ContinuousLiveRoutingTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val catalog =
        ContractCatalog(
            root.root,
            listOf(
                ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
            ),
        )
    private val history =
        RollHistory(
            root.root,
            "8d@08:00",
            listOf(RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800")),
        )
    private val registry =
        ContractCatalogRegistry(listOf(root), mapOf(root.root to catalog), mapOf(root.root to history))
    private val asked = mutableListOf<List<String>>()

    private val account =
        object : MarketSource {
            override val name = "gateway"
            override val capabilities = emptySet<MarketSourceCapability>()

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed {
                asked += symbols
                val queue = ArrayDeque(symbols.map { Tick(it, BigDecimal("97000"), ms("2024-12-01T00:00:00Z")) })
                return object : TickFeed {
                    override fun next() = queue.removeFirstOrNull()
                }
            }

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ) = emptySequence<Candle>()
        }

    private fun source(
        dir: Path,
        live: Boolean,
    ): ContinuousMarketSource {
        val chains = ContinuousChains(requireNotNull(registry.futures()))
        val clock =
            object : Clock {
                override fun now() = ms("2024-12-01T00:00:00Z")
            }
        val streams =
            LiveContinuousStreams(chains, requireNotNull(registry.futures()), RollHistoryStore(dir), account, clock)
        return ContinuousMarketSource(account, chains, registry, if (live) streams else null)
    }

    @Test
    fun `a session without continuous streams reads the account directly, unchanged`(
        @TempDir dir: Path,
    ) {
        val feed = source(dir, live = false).liveTicks(listOf("EXNESS:XAUUSD"))

        assertThat(generateSequence { feed.next() }.map { it.symbol }.toList()).containsExactly("EXNESS:XAUUSD")
        assertThat(asked).containsExactly(listOf("EXNESS:XAUUSD"))
    }

    @Test
    fun `continuous streams are served in the series beside the account's other symbols`(
        @TempDir dir: Path,
    ) {
        val feed = source(dir, live = true).liveTicks(listOf("BINANCE_UM:BTCUSDT@front", "EXNESS:XAUUSD"))

        val symbols = generateSequence { feed.next() }.map { it.symbol }.toSet()
        feed.close()

        assertThat(symbols).containsExactlyInAnyOrder("BINANCE_UM:BTCUSDT@front", "EXNESS:XAUUSD")
        assertThat(asked).contains(listOf("EXNESS:XAUUSD"), listOf("BINANCE_UM:BTCUSDT_241227"))
    }

    @Test
    fun `continuous streams without live streams to serve them are refused by name`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { source(dir, live = false).liveTicks(listOf("BINANCE_UM:BTCUSDT@front")) }
            .hasMessageContaining("BINANCE_UM:BTCUSDT@front")
    }
}
