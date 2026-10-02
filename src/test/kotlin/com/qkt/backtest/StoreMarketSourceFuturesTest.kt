package com.qkt.backtest

import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.marketdata.source.ContinuousMarketSource
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.store.DefaultDataStore
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StoreMarketSourceFuturesTest {
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
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to ContractCatalog(root.root, listOf(ListedContract("BTCUSDT_240927", 1_727_424_000_000L))),
            ),
            mapOf(root.root to RollHistory(root.root, "8d@08:00", emptyList())),
        )

    private fun source(
        dir: Path,
        symbols: List<String>,
    ) = storeMarketSource(
        store = DefaultDataStore(root = dir, fetcher = null),
        symbols = symbols,
        to = Instant.parse("2024-09-20T00:00:00Z"),
        hubStoreRoot = null,
        barStore = null,
        forceBars = false,
        binaryBarStore = null,
        instruments = registry,
    )

    @Test
    fun `a run with futures symbols reads through the continuous source`(
        @TempDir dir: Path,
    ) {
        assertThat(source(dir, listOf("BINANCE_UM:BTCUSDT@front"))).isInstanceOf(ContinuousMarketSource::class.java)
        assertThat(source(dir, listOf("BINANCE_UM:BTCUSDT_240927"))).isInstanceOf(ContinuousMarketSource::class.java)
    }

    @Test
    fun `a run without futures symbols builds exactly today's source`(
        @TempDir dir: Path,
    ) {
        assertThat(source(dir, listOf("BACKTEST:XAUUSD"))).isInstanceOf(LocalMarketSource::class.java)
    }
}
