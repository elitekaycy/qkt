package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.instrument.OptionCatalogRegistry
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionRootUndeclaredTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `a root not declared under options is refused naming it`() {
        val source = OptionRootMarketSource(OptionCatalogRegistry(emptyList(), emptyMap(), dir))
        val window = TimeRange(Instant.parse("2026-09-25T00:00:00Z"), Instant.parse("2026-09-26T00:00:00Z"))

        assertThatThrownBy { source.ticks("OPTIONS:DERIBIT.BTC_USDC", window) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("DERIBIT:BTC_USDC of OPTIONS:DERIBIT.BTC_USDC")
            .hasMessageContaining("is not declared under options: in instruments.yaml")
    }
}
