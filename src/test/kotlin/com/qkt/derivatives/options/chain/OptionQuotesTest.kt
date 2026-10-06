package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickStep
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OptionQuotesTest {
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5"), listOf(TickStep(BigDecimal("1000"), BigDecimal("20")))),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.TRADE,
            markSpread = BigDecimal("0.05"),
            maxQuoteAgeMinutes = 60,
        )

    private fun quote(
        mark: String,
        bid: String? = null,
        ask: String? = null,
        ageMs: Long = 0,
        source: QuoteSource = QuoteSource.TRADE,
    ) = ChainQuote(
        0,
        "BTC_USDC-25DEC26-92000-C",
        bid?.let(::BigDecimal),
        ask?.let(::BigDecimal),
        BigDecimal(mark),
        null,
        BigDecimal("83000"),
        null,
        ageMs,
        source,
    )

    @Test
    fun `a book quote trades on its own sides, a missing or zero side is absent and a crossed book has none`() {
        assertThat(OptionQuotes.sides(quote("100", "95", "105", source = QuoteSource.BOOK), root))
            .isEqualTo(QuoteSides(BigDecimal("95"), BigDecimal("105")))
        assertThat(OptionQuotes.sides(quote("0.2", null, "15", source = QuoteSource.BOOK), root))
            .isEqualTo(QuoteSides(null, BigDecimal("15")))
        assertThat(OptionQuotes.sides(quote("0.2", "0", "15", source = QuoteSource.BOOK), root))
            .isEqualTo(QuoteSides(null, BigDecimal("15")))
        assertThat(
            OptionQuotes.sides(quote("100", "110", "105", source = QuoteSource.BOOK), root),
        ).isEqualTo(QuoteSides.NONE)
    }

    @Test
    fun `a fresh trade mark gets the declared spread snapped outward on the tick grid`() {
        // 5% of 1500 = 75 > the 20 tick above 1000: 1425 / 1575 snap outward to 1420 / 1580.
        assertThat(
            OptionQuotes.sides(quote("1500"), root),
        ).isEqualTo(QuoteSides(BigDecimal("1420"), BigDecimal("1580")))
        // 5% of 100 = 5, the 5 tick: 95 / 105.
        assertThat(OptionQuotes.sides(quote("100"), root)).isEqualTo(QuoteSides(BigDecimal("95"), BigDecimal("105")))
        // 5% of 40 = 2 < the 5 tick, so the half-spread is one tick: 35 / 45.
        assertThat(OptionQuotes.sides(quote("40"), root)).isEqualTo(QuoteSides(BigDecimal("35"), BigDecimal("45")))
    }

    @Test
    fun `a cheap mark has no bid and a stale mark has no sides at all`() {
        assertThat(OptionQuotes.sides(quote("3"), root)).isEqualTo(QuoteSides(null, BigDecimal("10")))
        assertThat(
            OptionQuotes.sides(quote("100", ageMs = 3_600_000), root),
        ).isEqualTo(QuoteSides(BigDecimal("95"), BigDecimal("105")))
        assertThat(OptionQuotes.sides(quote("100", ageMs = 3_600_001), root)).isEqualTo(QuoteSides.NONE)
    }
}
