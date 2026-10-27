package com.qkt.instrument

import com.qkt.common.TradingCalendar
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Split factors on Exness's stock session (06:00-15:45 New York, 10:00 UTC in June): cumulative, horizon-bound. */
class SplitAdjustmentTest {
    private val calendar = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse")
    private val two = CorporateAction.Split(LocalDate.parse("2026-06-09"), BigDecimal(2))
    private val three = CorporateAction.Split(LocalDate.parse("2026-06-16"), BigDecimal(3))
    private val dividend = CorporateAction.Dividend(LocalDate.parse("2026-06-10"), BigDecimal("0.30"), "USD")
    private val actions =
        CorporateActionSource { if (it == "EXNESS:NVDA") listOf(two, dividend, three) else emptyList() }

    private fun ms(at: String) = Instant.parse(at).toEpochMilli()

    @Test
    fun `before both splits a price is divided by both, between them by the later one, after them by none`() {
        val adj = SplitAdjustment.of(actions, listOf("EXNESS:NVDA"), calendar, ms("2026-06-30T00:00:00Z"))
        assertThat(adj.factor("EXNESS:NVDA", ms("2026-06-08T15:00:00Z"))).isEqualByComparingTo("6")
        assertThat(adj.factor("EXNESS:NVDA", ms("2026-06-10T15:00:00Z"))).isEqualByComparingTo("3")
        assertThat(adj.factor("EXNESS:NVDA", ms("2026-06-17T15:00:00Z"))).isEqualByComparingTo("1")
    }

    @Test
    fun `a split takes effect at its ex-date session start, not at UTC midnight`() {
        val adj = SplitAdjustment.of(actions, listOf("EXNESS:NVDA"), calendar, ms("2026-06-12T00:00:00Z"))
        assertThat(adj.factor("EXNESS:NVDA", ms("2026-06-09T09:59:59Z"))).isEqualByComparingTo("2")
        assertThat(adj.factor("EXNESS:NVDA", ms("2026-06-09T10:00:00Z"))).isEqualByComparingTo("1")
        assertThat(adj.applies("EXNESS:NVDA", ms("2026-06-09T10:00:00Z"))).isTrue
    }

    @Test
    fun `a split after the run's end changes nothing in it`() {
        val adj = SplitAdjustment.of(actions, listOf("EXNESS:NVDA"), calendar, ms("2026-06-12T00:00:00Z"))
        assertThat(adj.factor("EXNESS:NVDA", ms("2026-06-08T15:00:00Z"))).isEqualByComparingTo("2")
        assertThat(adj.applies("EXNESS:NVDA", ms("2026-06-16T10:00:00Z"))).isFalse
        val before = SplitAdjustment.of(actions, listOf("EXNESS:NVDA"), calendar, ms("2026-06-09T10:00:00Z"))
        assertThat(before.isEmpty()).isTrue
    }

    @Test
    fun `a reverse split multiplies earlier prices`() {
        val reverse = CorporateAction.Split(LocalDate.parse("2026-06-09"), BigDecimal("0.125"))
        val adj =
            SplitAdjustment.of(CorporateActionSource { listOf(reverse) }, listOf("X:Y"), calendar, Long.MAX_VALUE)
        assertThat(BigDecimal(4).divide(adj.factor("X:Y", ms("2026-06-08T15:00:00Z")))).isEqualByComparingTo("32")
    }

    @Test
    fun `symbols without splits and runs without corporate actions are not adjusted`() {
        assertThat(SplitAdjustment.of(null, listOf("EXNESS:NVDA"), calendar, Long.MAX_VALUE).isEmpty()).isTrue
        val adj = SplitAdjustment.of(actions, listOf("EXNESS:NVDA", "EXNESS:AAPL"), calendar, Long.MAX_VALUE)
        assertThat(adj.symbols).containsExactly("EXNESS:NVDA")
        assertThat(adj.factor("EXNESS:AAPL", 0L)).isEqualByComparingTo("1")
    }

    @Test
    fun `the adjusted instruments drop applied splits and divide earlier dividends by the same factor`() {
        val registry =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String): InstrumentMeta? = null

                override fun corporateActions() = actions
            }
        val adj = SplitAdjustment.of(actions, listOf("EXNESS:NVDA"), calendar, ms("2026-06-12T00:00:00Z"))
        val seen = SplitAdjustedActions(registry, adj, calendar).corporateActions()!!.actions("EXNESS:NVDA")
        // The 2-for-1 is in the prices; the 3-for-1 lies after the run. The dividend is after the 2-for-1: unchanged.
        assertThat(seen).containsExactly(dividend, three)
        val late = SplitAdjustment.of(actions, listOf("EXNESS:NVDA"), calendar, ms("2026-06-30T00:00:00Z"))
        val all = SplitAdjustedActions(registry, late, calendar).corporateActions()!!.actions("EXNESS:NVDA")
        assertThat(all).containsExactly(dividend.copy(amount = BigDecimal("0.10")))
        assertThat(SplitAdjustedActions(registry, late, calendar).corporateActions()!!.actions("EXNESS:AAPL")).isEmpty()
    }
}
