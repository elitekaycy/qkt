package com.qkt.backtest

import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StoreMarketSourceHubTest {
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
    )

    @Test
    fun `a hub stream without a hub store under the data root fails before the first tick`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { source(dir, listOf("BACKTEST:XAUUSD", "HUB:fred.dgs10.value")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("hub data problems:")
            .hasMessageContaining("hub store not found at")
    }

    @Test
    fun `a run without observation streams reads the local store directly`(
        @TempDir dir: Path,
    ) {
        assertThat(source(dir, listOf("BACKTEST:XAUUSD"))).isInstanceOf(LocalMarketSource::class.java)
    }
}
