package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionRight
import com.qkt.instrument.QuoteSource
import java.nio.file.Paths
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** The real book snapshot of `btc-usdc-book-20261001`; picks recomputed by an independent Python selector. */
class OptionSelectorTest {
    private val dir = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-book-20261001")).toURI())
    private val snapshot =
        ChainSnapshotStore(
            dir,
            QuoteSource.BOOK,
        ).readDay("DERIBIT:BTC_USDC", LocalDate.parse("2026-10-01")).single()
    private val listings =
        Json
            .decodeFromString(
                OptionCatalog.serializer(),
                dir.resolve("contracts/DERIBIT/BTC_USDC.options.json").toFile().readText(),
            ).contracts
            .associateBy { it.symbol }
    private val hour = 3_600_000L

    private fun pick(criteria: LegCriteria) = OptionSelector.select(snapshot, listings, criteria, hour)

    @Test
    fun `the nearest expiry in the window, then the delta nearest the target`() {
        val put = requireNotNull(pick(LegCriteria(OptionRight.PUT, 0.25, minDays = 7.0, maxDays = 30.0)))

        assertThat(put.contract).isEqualTo("BTC_USDC-9OCT26-81000-P")
        assertThat(put.delta).isCloseTo(-0.2586390357, within(1e-9))
        val wing = requireNotNull(pick(LegCriteria(OptionRight.CALL, 0.10, expiryMs = put.expiryMs)))
        assertThat(wing.contract).isEqualTo("BTC_USDC-9OCT26-89000-C")
        assertThat(wing.delta).isCloseTo(0.1072027745, within(1e-9))
        assertThat(
            pick(LegCriteria(OptionRight.CALL, 0.50, minDays = 0.0, maxDays = 1.0))?.contract,
        ).isEqualTo("BTC_USDC-1OCT26-83500-C")
    }

    @Test
    fun `no expiry in the window, or no fresh quote, selects nothing`() {
        assertThat(pick(LegCriteria(OptionRight.PUT, 0.25, minDays = 200.0, maxDays = 300.0))).isNull()
        val stale = ChainSnapshot(snapshot.root, snapshot.atMs, snapshot.quotes.map { it.copy(markAgeMs = 2 * hour) })
        assertThat(
            OptionSelector.select(
                stale,
                listings,
                LegCriteria(OptionRight.PUT, 0.25, minDays = 7.0, maxDays = 30.0),
                hour,
            ),
        ).isNull()
    }

    @Test
    fun `the expiry is chosen among quotes of the leg's own right`() {
        val nearestCallsOnly =
            ChainSnapshot(
                snapshot.root,
                snapshot.atMs,
                snapshot.quotes.filterNot {
                    listings.getValue(it.contract).expiryMs == firstExpiryIn(7.0, 30.0) &&
                        it.contract.endsWith("-P")
                },
            )

        val put =
            OptionSelector.select(
                nearestCallsOnly,
                listings,
                LegCriteria(OptionRight.PUT, 0.25, minDays = 7.0, maxDays = 30.0),
                hour,
            )

        assertThat(put).isNotNull()
        assertThat(put!!.expiryMs).isGreaterThan(firstExpiryIn(7.0, 30.0))
    }

    @Test
    fun `a leg names either a whole days window or an expiry`() {
        assertThatThrownBy {
            LegCriteria(
                OptionRight.PUT,
                0.25,
                minDays = 7.0,
                expiryMs = 1L,
            )
        }.hasMessageContaining("window")
        assertThatThrownBy { LegCriteria(OptionRight.PUT, 0.25, minDays = 7.0) }.hasMessageContaining("window")
        assertThatThrownBy { LegCriteria(OptionRight.PUT, 0.25) }.hasMessageContaining("window")
    }

    private fun firstExpiryIn(
        min: Double,
        max: Double,
    ): Long =
        listings.values.map { it.expiryMs }.distinct().sorted().first {
            (it - snapshot.atMs) / 86_400_000.0 in
                min..max
        }
}
