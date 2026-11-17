package com.qkt.cli

import com.qkt.marketdata.hub.HubStoreConfig
import com.qkt.marketdata.source.ReplayMarketSource
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarketSourceFactoryRoutesTest {
    @Test
    fun `a configured hub store routes HUB streams to it and nothing else`(
        @TempDir tmp: Path,
    ) {
        val withHub =
            MarketSourceFactory.composite(emptyList(), source = "off", hub = HubStoreConfig(root = tmp))(emptyList())

        assertThat(withHub.supports("HUB:cpi")).isTrue()
        assertThat(withHub.supports("EXNESS:XAUUSD")).isFalse()
    }

    @Test
    fun `the replay source is the tick file QKT_REPLAY_TICKS names, and none without it`(
        @TempDir tmp: Path,
    ) {
        assertThat(MarketSourceFactory.replaySource(emptyMap())).isNull()
        assertThat(MarketSourceFactory.replaySource(mapOf("QKT_REPLAY_TICKS" to tmp.resolve("ticks.csv").toString())))
            .isInstanceOf(ReplayMarketSource::class.java)
    }
}
