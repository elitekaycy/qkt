package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A category comes from the connector's instrument contract, read once, retried only after a while when it fails. */
class InstrumentCategoriesTest {
    private fun meta(
        symbol: String,
        category: String?,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal(100),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        category = category,
    )

    @Test
    fun `an answer is kept, a venue that cannot answer is asked again only after the back-off`() {
        val asked = mutableListOf<String>()
        var answer: InstrumentMeta? = null
        var now = 0L
        val categories =
            InstrumentCategories("exness", { s ->
                asked += s
                answer
            }, monotonicNanos = { now }, retryAfterNanos = 1_000L)
        assertThat(categories("AAPL")).isNull() // venue down
        assertThat(categories("AAPL")).isNull() // inside the back-off: not asked
        answer = meta("EXNESS:AAPL", "Stocks")
        now = 1_000L
        assertThat(categories("AAPL")).isEqualTo("Stocks")
        assertThat(categories("AAPL")).isEqualTo("Stocks")
        assertThat(asked).containsExactly("EXNESS:AAPL", "EXNESS:AAPL")
    }

    @Test
    fun `a symbol the venue files nowhere is remembered as uncategorised, and a throwing read is a failed one`() {
        var calls = 0
        val none =
            InstrumentCategories("exness", {
                calls++
                meta("EXNESS:EURUSD", null)
            })
        assertThat(none("EURUSD")).isNull()
        assertThat(none("EURUSD")).isNull()
        assertThat(calls).isEqualTo(1)
        val throwing = InstrumentCategories("exness", { error("unexpected body") })
        assertThat(throwing("AAPL")).isNull()
    }
}
