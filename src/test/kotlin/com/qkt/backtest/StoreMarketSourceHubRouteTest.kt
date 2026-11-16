package com.qkt.backtest

import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A run declaring a hub stream over a present hub store routes that stream to the hub. */
class StoreMarketSourceHubRouteTest {
    @Test
    fun `a hub stream over a hub store is routed beside the local store`(
        @TempDir dir: Path,
    ) {
        val hub = dir.resolve("hub").also { Files.createDirectories(it.resolve("journal")) }

        val source =
            storeMarketSource(
                store = DefaultDataStore(root = dir.resolve("data"), fetcher = null),
                symbols = listOf("BACKTEST:XAUUSD", "HUB:fred.dgs10.value"),
                to = Instant.parse("2024-09-20T00:00:00Z"),
                hubStoreRoot = hub,
                barStore = null,
                forceBars = false,
                binaryBarStore = null,
            )

        assertThat(source).isInstanceOf(CompositeMarketSource::class.java)
        assertThat(source.supports("HUB:fred.dgs10.value")).isTrue()
    }
}
