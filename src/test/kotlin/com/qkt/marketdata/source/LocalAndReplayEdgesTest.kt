package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.Manifest
import com.qkt.marketdata.store.ManifestStore
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalAndReplayEdgesTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `a manifest whose days have no files reads as a store without volume`() {
        val clock = FixedClock(time = Instant.parse("2024-01-20T00:00:00Z").toEpochMilli())
        ManifestStore(dir, clock).write(Manifest(symbol = "Z", ranges = listOf(DayRange("2024-01-15", "2024-01-17"))))
        val source = LocalMarketSource(DefaultDataStore(root = dir, clock = clock), clock)

        assertThat(source.capabilitiesFor("Z"))
            .containsExactlyInAnyOrder(MarketSourceCapability.BARS, MarketSourceCapability.TICKS)
    }

    @Test
    fun `a replay asked for no symbols is refused`() {
        assertThatThrownBy { ReplayMarketSource(dir.resolve("ticks.csv")).liveTicks(emptyList()) }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("ReplayMarketSource: no symbols supplied")
    }
}
