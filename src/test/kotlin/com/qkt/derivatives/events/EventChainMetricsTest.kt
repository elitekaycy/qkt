package com.qkt.derivatives.events

import com.qkt.instrument.CatalogStrike
import com.qkt.instrument.SlotFamilyFixture
import java.math.BigDecimal
import java.math.RoundingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventChainMetricsTest {
    private fun ladder(vararg quotes: Pair<String?, String?>): List<EventQuote> =
        quotes.mapIndexed { i, (bid, ask) ->
            val terms = SlotFamilyFixture.row(1, i + 1).terms(SlotFamilyFixture.ROOT)
            EventQuote(terms, bid?.let(::BigDecimal), ask?.let(::BigDecimal))
        }

    /** Exclusive buckets [0,10), [10,20), [20,30) with the given quotes. */
    private fun buckets(
        vararg quotes: Pair<String?, String?>,
        bounded: Boolean = true,
    ): List<EventQuote> =
        quotes.mapIndexed { i, (bid, ask) ->
            val strike =
                if (bounded || i > 0) {
                    CatalogStrike("between", "${i * 10}", "${i * 10 + 10}")
                } else {
                    CatalogStrike("less", cap = "10")
                }
            val row = SlotFamilyFixture.row(1, i + 1, exclusive = true, predicate = strike)
            val terms = row.terms(SlotFamilyFixture.ROOT)
            EventQuote(terms, bid?.let(::BigDecimal), ask?.let(::BigDecimal))
        }

    private fun value(
        metric: String,
        contracts: List<EventQuote>,
    ): BigDecimal? = requireNotNull(EventChainMetrics.of(metric)).value(contracts)

    @Test
    fun `the registry names its metrics`() {
        assertThat(EventChainMetrics.tokens)
            .isEqualTo("implied_median, implied_mean, overround, underround, ladder_breaks")
        assertThat(EventChainMetrics.of("atm_iv")).isNull()
    }

    @Test
    fun `implied median interpolates the strike where ladder mids cross one half`() {
        // Strikes 100..400 with mids 0.90, 0.70, 0.40, 0.10: the cross is between 200 and 300, 2/3 of the way.
        val quotes = ladder("0.89" to "0.91", "0.69" to "0.71", "0.39" to "0.41", "0.09" to "0.11")

        assertThat(value("implied_median", quotes)?.setScale(6, RoundingMode.HALF_EVEN))
            .isEqualByComparingTo("266.666667")
        assertThat(value("implied_median", ladder("0.49" to "0.51", "0.49" to "0.51"))).isEqualByComparingTo("100")
        assertThat(value("implied_median", ladder("0.89" to "0.91", null to "0.71", "0.69" to "0.71"))).isNull()
        assertThat(value("implied_median", ladder("0.89" to "0.91"))).isNull()
        assertThat(value("implied_median", buckets("0.3" to "0.4", "0.5" to "0.6"))).isNull()
    }

    @Test
    fun `ladder breaks count adjacent strikes whose higher bid exceeds the lower ask`() {
        val quotes = ladder("0.60" to "0.62", "0.65" to "0.70", "0.30" to "0.32", null to "0.20")

        assertThat(value("ladder_breaks", quotes)).isEqualByComparingTo("1")
        assertThat(value("ladder_breaks", ladder(null to "0.5", null to "0.4"))).isNull()
        assertThat(value("ladder_breaks", buckets("0.3" to "0.4", "0.5" to "0.6"))).isNull()
    }

    @Test
    fun `implied mean weighs bounded bucket midpoints by normalised mids`() {
        val quotes = buckets("0.19" to "0.21", "0.49" to "0.51", "0.29" to "0.31")

        // (5 x 0.2 + 15 x 0.5 + 25 x 0.3) / 1.0 = 16
        assertThat(value("implied_mean", quotes)).isEqualByComparingTo("16")
        assertThat(value("implied_mean", buckets("0.19" to "0.21", "0.49" to "0.51", bounded = false))).isNull()
        assertThat(value("implied_mean", buckets("0.19" to "0.21", null to "0.51"))).isNull()
        assertThat(value("implied_mean", buckets("0" to "0", "0" to "0"))).isNull()
        assertThat(value("implied_mean", ladder("0.4" to "0.5", "0.3" to "0.4"))).isNull()
    }

    @Test
    fun `overround and underround sum one side of an exclusive event`() {
        val quotes = buckets("0.18" to "0.22", null to "0.52", "0.27" to "0.31")

        assertThat(value("overround", quotes)).isEqualByComparingTo("0.05")
        assertThat(value("underround", quotes)).isEqualByComparingTo("0.55")
        assertThat(value("overround", buckets("0.18" to null, "0.5" to "0.52"))).isNull()
        assertThat(value("underround", buckets(null to null, "0.5" to "0.52"))).isNull()
        assertThat(value("overround", ladder("0.4" to "0.5", "0.3" to "0.4"))).isNull()
        assertThat(value("underround", emptyList())).isNull()
    }

    @Test
    fun `a crossed quote has no mid`() {
        val crossed = ladder("0.6" to "0.5").single()

        assertThat(crossed.mid).isNull()
        assertThat(ladder("0.4" to "0.6").single().mid).isEqualByComparingTo("0.5")
    }
}
