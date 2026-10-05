package com.qkt.connector.gateway

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A gateway account's live option marks: the newest quote carrying a mark IV, served only when declared. */
class GatewayOptionMarksTest {
    private val code = "BTC_USDC-30OCT26-110000-C"
    private val call = "DERIBIT:BTC_USDC_30OCT26_110000_C"
    private var declared = listOf("quotes", "option_marks")
    private val marks = GatewayOptionMarks("DERIBIT:") { declared }

    private fun quote(
        time: Long,
        markIv: String?,
        underlying: String? = "86575.5",
    ) = WireQuote(code, "85", "120", mark = "99.04", markIv = markIv, underlying = underlying, time = time)

    init {
        marks.listed(listOf(WireInstrument(code, "option", "USDC", "1", "5", "0.01", "0.01", underlying = "BTC_USDC")))
    }

    @Test
    fun `the newest quote carrying a mark iv is served by qkt symbol, with its forward and own time`() {
        marks.heard(quote(1_000, "45.46"))
        marks.heard(quote(2_000, "45.50"))
        marks.heard(quote(3_000, null))

        val served = marks.at(call, 9_999)

        assertThat(served?.markIv).isEqualByComparingTo("45.50")
        assertThat(served?.underlying).isEqualByComparingTo(BigDecimal("86575.5"))
        assertThat(served?.atMs).isEqualTo(2_000)
        assertThat(served?.markAgeMs).isZero
    }

    @Test
    fun `nothing is served before a quote, nor for a code the listing does not hold`() {
        assertThat(marks.at(call, 1)).isNull()
        marks.heard(quote(1_000, "45.46"))
        assertThat(marks.at("DERIBIT:BTC_USDC_30OCT26_120000_C", 1)).isNull()
    }

    @Test
    fun `a gateway that does not declare option marks is named as the problem`() {
        assertThat(marks.problem(call)).isNull()
        declared = listOf("quotes", "mark_prices")

        assertThat(marks.problem(call)).contains("does not serve option marks (capability 'option_marks')")
    }

    @Test
    fun `the gateway source serves a contract's option marks, and none for a whole root feed`() {
        val source = GatewayMarketSource("DERIBIT:", "http://127.0.0.1:1", "k", { emptyList() })

        assertThat(source.optionMarksFor(call)).isNotNull
        assertThat(source.optionMarksFor("OPTIONS:DERIBIT.BTC_USDC")).isNull()
        assertThat(source.optionMarksFor(call)?.problem(call)).contains("option_marks")
    }
}
