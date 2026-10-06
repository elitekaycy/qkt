package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.nio.file.Paths
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The first real snapshot of `btc-usdc-book-live4`, 30 s later: short 0.19 of the 9OCT26 82000 put,
 * long 0.19 of the 79000 put. Expected values from an independent Python Black-76 (`math.erf`), see
 * the fixture's PROVENANCE.
 */
class StructureGreeksTest {
    private val dir = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-book-live4")).toURI())
    private val snapshot =
        ChainSnapshotStore(dir, QuoteSource.BOOK).readDay("DERIBIT:BTC_USDC", LocalDate.parse("2026-10-01")).first()
    private val listings =
        Json
            .decodeFromString(
                OptionCatalog.serializer(),
                dir.resolve("contracts/DERIBIT/BTC_USDC.options.json").toFile().readText(),
            ).contracts
            .associateBy { it.symbol }
    private val now = snapshot.atMs + 30_000
    private val spread =
        listOf(
            HeldLeg("BTC_USDC-9OCT26-82000-P", BigDecimal("-0.19"), BigDecimal.ONE),
            HeldLeg("BTC_USDC-9OCT26-79000-P", BigDecimal("0.19"), BigDecimal.ONE),
        )

    @Test
    fun `a put credit spread is long delta, short vega and collects theta`() {
        val greeks = requireNotNull(StructureGreeks.of(spread, snapshot, listings, 3_600_000L, now))

        assertThat(greeks.delta).isCloseTo(0.030679607082869577, within(1e-12))
        assertThat(greeks.gamma).isCloseTo(-7.757036237081573e-06, within(1e-15))
        assertThat(greeks.vega).isCloseTo(-3.537833577427546, within(1e-10))
        assertThat(greeks.theta).isCloseTo(6.148692334513596, within(1e-10))
    }

    @Test
    fun `a held leg without a usable IV leaves the Greeks undefined, never zero`() {
        assertThat(StructureGreeks.of(spread, snapshot, listings, maxQuoteAgeMs = -1L, nowMs = now)).isNull()
        val unquoted = spread + HeldLeg("BTC_USDC-NOPE", BigDecimal.ONE, BigDecimal.ONE)
        assertThat(StructureGreeks.of(unquoted, snapshot, listings, 3_600_000L, now)).isNull()
    }

    @Test
    fun `no held leg has no Greeks`() {
        assertThat(StructureGreeks.of(emptyList(), snapshot, listings, 3_600_000L, now)).isNull()
    }
}
