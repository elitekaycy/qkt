package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionListing
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ChainAnalyticsEdgesTest {
    private val day = 86_400_000L
    private val atMs = 100 * day
    private val hour = 3_600_000L

    private fun quote(
        contract: String,
        iv: String?,
        ageMs: Long = 0L,
    ) = ChainQuote(
        atMs,
        contract,
        null,
        null,
        BigDecimal("100"),
        iv?.let(::BigDecimal),
        BigDecimal("80000"),
        null,
        ageMs,
        QuoteSource.BOOK,
    )

    private fun listing(
        contract: String,
        expiryMs: Long,
    ) = OptionListing(contract, "80000", "call", expiryMs)

    @Test
    fun `a non-positive tenor is refused`() {
        val snapshot = ChainSnapshot("DERIBIT:BTC_USDC", atMs, listOf(quote("A", "50")))

        assertThatThrownBy { ChainAnalytics.value(ChainMetric.ATM_IV, 0, snapshot, emptyMap(), hour) }
            .hasMessage("chain tenor must be > 0 days: 0")
    }

    @Test
    fun `quotes without an iv, stale, uncatalogued or already expired define nothing`() {
        val snapshot =
            ChainSnapshot(
                "DERIBIT:BTC_USDC",
                atMs,
                listOf(quote("NOIV", null), quote("STALE", "50", 2 * hour), quote("LOOSE", "50"), quote("GONE", "50")),
            )
        val listings =
            mapOf(
                "NOIV" to listing("NOIV", atMs + 10 * day),
                "STALE" to listing("STALE", atMs + 10 * day),
                "GONE" to listing("GONE", atMs - day),
            )

        assertThat(ChainAnalytics.value(ChainMetric.ATM_IV, 7, snapshot, listings, hour)).isNull()
    }

    @Test
    fun `strikes exactly at the forward give that iv, flat across tenors`() {
        val snapshot = ChainSnapshot("DERIBIT:BTC_USDC", atMs, listOf(quote("NEAR", "50"), quote("FAR", "50")))
        val listings = mapOf("NEAR" to listing("NEAR", atMs + 10 * day), "FAR" to listing("FAR", atMs + 40 * day))

        assertThat(ChainAnalytics.value(ChainMetric.ATM_IV, 20, snapshot, listings, hour)).isCloseTo(50.0, within(1e-9))
        assertThat(ChainAnalytics.value(ChainMetric.ATM_IV, 60, snapshot, listings, hour)).isNull()
    }
}
