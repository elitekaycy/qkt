package com.qkt.instrument

import com.qkt.common.CmeGlobexCalendar
import com.qkt.common.TradingCalendar
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FuturesCalendarsTest {
    private fun root(
        name: String,
        calendar: String?,
    ) = FuturesRoot(
        name,
        "USD",
        BigDecimal("50"),
        BigDecimal("0.25"),
        BigDecimal.ONE,
        BigDecimal.ONE,
        null,
        calendar,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
    )

    private val registry =
        ContractCatalogRegistry(
            listOf(
                root("CME:ES", "cme_globex"),
                root("CME:NQ", "cme_globex"),
                root("CME:YM", null),
                root("ICE:B", "fx"),
            ),
            mapOf("CME:ES" to "ESZ24", "CME:NQ" to "NQZ24", "CME:YM" to "YMZ24", "ICE:B" to "BZ24")
                .mapValues { (rootId, code) -> ContractCatalog(rootId, listOf(ListedContract(code, EXPIRY))) },
        )

    @Test
    fun `a run of futures that share a calendar uses it`() {
        assertThat(registry.futuresCalendar(listOf("CME:ESZ24", "CME:NQZ24"))).isSameAs(CmeGlobexCalendar)
    }

    @Test
    fun `any other symbol, a root without a calendar, or two calendars keep today's resolution`() {
        assertThat(registry.futuresCalendar(listOf("CME:ESZ24", "EXNESS:XAUUSD"))).isNull()
        assertThat(registry.futuresCalendar(listOf("CME:YMZ24"))).isNull()
        assertThat(registry.futuresCalendar(listOf("CME:ESZ24", "ICE:BZ24"))).isNull()
        assertThat(registry.futuresCalendar(emptyList())).isNull()
    }

    @Test
    fun `calendars are looked up by name`() {
        assertThat(TradingCalendar.named("cme_globex")).isSameAs(CmeGlobexCalendar)
        assertThat(TradingCalendar.named("crypto")).isSameAs(TradingCalendar.crypto())
        assertThat(TradingCalendar.named("cme_equity")).isNull()
    }

    @Test
    fun `a root naming an unknown calendar is refused with the known names`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.yaml")
        Files.writeString(
            file,
            "futures:\n  - { root: CME:ES, currency: USD, multiplier: 50, tickSize: 0.25, volumeStep: 1, volumeMin: 1, calendar: cme_equity }\n",
        )

        assertThatThrownBy { FuturesRootsFile.load(file) }
            .hasMessageContaining("cme_equity")
            .hasMessageContaining("cme_globex")
    }

    private companion object {
        const val EXPIRY = 1_734_710_400_000L
    }
}
