package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.QuoteSource
import java.nio.file.Paths
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * A real Deribit BTC_USDC book snapshot (fixture `btc-usdc-book-20261001`, see its PROVENANCE).
 * Expected values come from an independent Python implementation of the plan's rules
 * (`math.erf` normal CDF), recorded in the PROVENANCE file.
 */
class ChainAnalyticsTest {
    private val dir = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-book-20261001")).toURI())
    private val snapshot =
        ChainSnapshotStore(
            dir,
            QuoteSource.BOOK,
        ).readDay("DERIBIT:BTC_USDC", LocalDate.parse("2026-10-01")).single()
    private val catalog =
        Json.decodeFromString(
            OptionCatalog.serializer(),
            dir.resolve("contracts/DERIBIT/BTC_USDC.options.json").toFile().readText(),
        )
    private val listings = catalog.contracts.associateBy { it.symbol }
    private val hour = 3_600_000L

    private fun value(
        metric: ChainMetric,
        days: Int,
    ) = ChainAnalytics.value(metric, days, snapshot, listings, hour)

    @Test
    fun `at-the-money volatility interpolates total variance between the expiries around the tenor`() {
        assertThat(value(ChainMetric.ATM_IV, 1)).isCloseTo(30.58214541001407, within(1e-9))
        assertThat(value(ChainMetric.ATM_IV, 7)).isCloseTo(30.891697558131362, within(1e-9))
        assertThat(value(ChainMetric.ATM_IV, 30)).isCloseTo(33.820839841412294, within(1e-9))
    }

    @Test
    fun `the 25-delta skew is the put wing less the call wing, interpolated in time`() {
        assertThat(value(ChainMetric.SKEW_25D, 7)).isCloseTo(0.662717312518918, within(1e-9))
        assertThat(value(ChainMetric.SKEW_25D, 30)).isCloseTo(1.068997868586103, within(1e-9))
    }

    @Test
    fun `a tenor beyond the listed expiries or a chain of stale marks has no value`() {
        assertThat(value(ChainMetric.ATM_IV, 90)).isNull()
        assertThat(value(ChainMetric.SKEW_25D, 400)).isNull()
        val stale = ChainSnapshot(snapshot.root, snapshot.atMs, snapshot.quotes.map { it.copy(markAgeMs = 2 * hour) })
        assertThat(ChainAnalytics.value(ChainMetric.ATM_IV, 30, stale, listings, hour)).isNull()
    }

    @Test
    fun `an expiry with only one wing contributes no skew`() {
        val callsOnly =
            ChainSnapshot(snapshot.root, snapshot.atMs, snapshot.quotes.filter { it.contract.endsWith("-C") })

        assertThat(ChainAnalytics.value(ChainMetric.SKEW_25D, 30, callsOnly, listings, hour)).isNull()
        assertThat(ChainAnalytics.value(ChainMetric.ATM_IV, 30, callsOnly, listings, hour)).isNotNull()
    }

    @Test
    fun `metric tokens name each metric`() {
        assertThat(ChainMetric.of("atm_iv")).isEqualTo(ChainMetric.ATM_IV)
        assertThat(ChainMetric.of("skew_25d")).isEqualTo(ChainMetric.SKEW_25D)
        assertThat(ChainMetric.of("put_call_oi")).isNull()
    }

    private fun withQuotes(edit: (List<ChainQuote>) -> List<ChainQuote>) =
        ChainSnapshot(snapshot.root, snapshot.atMs, edit(snapshot.quotes))

    @Test
    fun `a zero or missing mark IV and an uncatalogued quote are ignored, never fatal`() {
        val zero =
            withQuotes { qs ->
                qs.map { if (it.contract.endsWith("-C")) it.copy(markIv = java.math.BigDecimal.ZERO) else it }
            }
        val stray = withQuotes { qs -> qs + qs.first().copy(contract = "BTC_USDC-1JAN27-99000-C") }

        assertThat(ChainAnalytics.value(ChainMetric.SKEW_25D, 30, zero, listings, hour)).isNull()
        assertThat(ChainAnalytics.value(ChainMetric.ATM_IV, 30, zero, listings, hour)).isNotNull()
        assertThat(
            ChainAnalytics.value(ChainMetric.ATM_IV, 30, stray, listings, hour),
        ).isCloseTo(33.820839841412294, within(1e-9))
    }

    @Test
    fun `an expiry without strikes near the forward on both sides has no ATM value`() {
        val farOnly =
            withQuotes { qs ->
                qs.filter { q ->
                    val l = listings.getValue(q.contract)
                    l.expiryMs != listings.values.maxOf { it.expiryMs } ||
                        java.math
                            .BigDecimal(l.strike)
                            .toDouble()
                            .let { it < 70_000 || it > 100_000 }
                }
            }

        assertThat(ChainAnalytics.value(ChainMetric.ATM_IV, 80, farOnly, listings, hour)).isNull()
    }
}
