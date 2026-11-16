package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.marketdata.depth.BookDepthMarketSource
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.openinterest.OpenInterestMarketSource
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The market sources that serve one kind of data inherit the interface's neutral answers for the rest. */
class MarketSourceDefaultsTest {
    private val range = TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(1_000))

    private fun assertNeutral(
        source: MarketSource,
        flowAndMarksOverridden: Boolean = false,
    ) {
        assertThat(source.slotContractAt("X", 0L)).isNull()
        assertThat(source.capabilitiesFor("X")).isEqualTo(source.capabilities)
        if (!flowAndMarksOverridden) {
            assertThat(source.marksFor("X")).isNull()
            assertThat(source.optionMarksFor("X")).isNull()
            assertThat(source.tradeFlowFor("X")).isNull()
        }
        runCatching { source.sampledTicks("X", null, range).toList() }
    }

    @Test
    fun `book depth, open interest, hub and macro sources answer the neutral defaults`(
        @TempDir tmp: Path,
    ) {
        val depth = BookDepthMarketSource({ _, _, _ -> emptyList() })
        val oi = OpenInterestMarketSource({ _, _, _ -> emptyList() })
        val hub = HubMarketSource(tmp)
        val macro = MacroMarketSource(MacroSeriesStore(tmp))

        for (source in listOf<MarketSource>(depth, oi, hub, macro)) {
            assertNeutral(source)
            assertThat(source.tickDeliveryLagMsFor("X")).isEqualTo(0L)
            runCatching { source.tickSlice("X", 0L, 1L).toList() }
        }
    }

    @Test
    fun `the caching, shared and continuous wrappers keep the remaining defaults`(
        @TempDir tmp: Path,
    ) {
        val inner = HubMarketSource(tmp)
        val cached = CachedHistoricalMarketSource(inner)
        val shared = SharedLiveMarketSource(inner)
        val continuous =
            ContinuousMarketSource(
                inner,
                ContinuousChains(ContractCatalogRegistry(emptyList(), emptyMap(), emptyMap())),
                ContractCatalogRegistry(emptyList(), emptyMap(), emptyMap()),
            )

        assertThat(cached.slotContractAt("X", 0L)).isNull()
        assertThat(shared.slotContractAt("X", 0L)).isNull()
        assertThat(continuous.slotContractAt("X", 0L)).isNull()
        assertThat(cached.marksFor("X")).isNull()
        assertThat(shared.optionMarksFor("X")).isNull()
        assertThat(cached.tradeFlowFor("X")).isNull()
        for (source in listOf<MarketSource>(cached, shared, continuous)) {
            runCatching { source.sampledTicks("X", null, range) }
        }
        shared.close()
        cached.close()
    }
}
