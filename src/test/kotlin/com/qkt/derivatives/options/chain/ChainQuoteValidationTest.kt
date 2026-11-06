package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRight
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainQuoteValidationTest {
    private fun quote(contract: String) =
        ChainQuote(
            atMs = 1L,
            contract = contract,
            bid = null,
            ask = null,
            mark = BigDecimal("100"),
            markIv = BigDecimal("0.5"),
            underlying = BigDecimal("83000"),
            rate = null,
            markAgeMs = 0L,
            source = QuoteSource.TRADE,
        )

    @Test
    fun `a contract code with a comma is refused`() {
        assertThatThrownBy { quote("BTC,USDC") }
            .hasMessage("ChainQuote.contract must be a code without commas: 'BTC,USDC'")
    }

    @Test
    fun `a csv row with the wrong column count names the line`() {
        assertThatThrownBy { ChainCsv.parse("1,BTC_USDC-2OCT26-92000-C,,") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("expected 11 columns, got 4: 1,BTC_USDC-2OCT26-92000-C,,")
        val q = quote("BTC_USDC-2OCT26-92000-C")
        assertThat(ChainCsv.parse(ChainCsv.row(q))).isEqualTo(q)
    }

    @Test
    fun `a days window that is negative or reversed is refused`() {
        assertThatThrownBy { LegCriteria(OptionRight.CALL, 0.25, minDays = 10.0, maxDays = 5.0) }
            .hasMessage("days window 10.0..5.0")
        assertThatThrownBy { LegCriteria(OptionRight.PUT, 0.25, minDays = -1.0, maxDays = 5.0) }
            .hasMessage("days window -1.0..5.0")
    }

    @Test
    fun `option marks have no problem unless the source names one`() {
        val marks =
            object : OptionMarks {
                override fun at(
                    qktSymbol: String,
                    atMs: Long,
                ): ChainQuote? = null
            }

        assertThat(marks.problem("DERIBIT:BTC_USDC_2OCT26_92000_C")).isNull()
    }

    @Test
    fun `stored marks of a root without a chain series say so`(
        @TempDir dir: Path,
    ) {
        val fixture = OptionChainFixture(dir)
        val listing = OptionListing(fixture.venueName, "92000", "call", fixture.expiryMs)
        val catalog = mapOf(fixture.root.root to OptionCatalog(fixture.root.root, listOf(listing)))
        val bare = OptionCatalogRegistry(listOf(fixture.root.copy(chains = null)), catalog, dir)

        assertThat(StoredOptionMarks(bare).problem(fixture.symbol))
            .isEqualTo("its root DERIBIT:BTC_USDC declares no chain series to replay (chains: book | trade)")
        assertThat(StoredOptionMarks(fixture.registry).problem(fixture.symbol)).isNull()
        assertThat(
            StoredOptionMarks(fixture.registry).problem("EXNESS:XAUUSD"),
        ).isEqualTo("it is not a catalogued option")
    }
}
