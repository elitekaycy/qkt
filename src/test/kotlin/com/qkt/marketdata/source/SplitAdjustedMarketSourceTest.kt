package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionSource
import com.qkt.instrument.SplitAdjustment
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.marks.MarkPrices
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A 10-for-1 split on 2026-06-09 (session from 10:00 UTC): earlier prices divided by 10, volumes multiplied. */
class SplitAdjustedMarketSourceTest {
    private val split = CorporateAction.Split(LocalDate.parse("2026-06-09"), BigDecimal(10))
    private val calendar = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse")
    private val adjustment =
        SplitAdjustment.of(
            CorporateActionSource { if (it == "EXNESS:NVDA") listOf(split) else emptyList() },
            listOf("EXNESS:NVDA", "EXNESS:AAPL"),
            calendar,
            Long.MAX_VALUE,
        )

    private fun ms(at: String) = Instant.parse(at).toEpochMilli()

    private fun tick(
        symbol: String,
        at: String,
        price: String,
    ) = Tick(
        symbol,
        BigDecimal(price),
        ms(at),
        volume = BigDecimal(5),
        bid = BigDecimal(price) - BigDecimal("0.5"),
        ask = BigDecimal(price) + BigDecimal("0.5"),
        bidVolume = BigDecimal(2),
        askVolume = BigDecimal(3),
    )

    private val ticks =
        listOf(
            tick("EXNESS:NVDA", "2026-06-08T15:00:00Z", "1200"),
            tick("EXNESS:NVDA", "2026-06-09T10:00:00Z", "121"),
        )
    private val aapl = tick("EXNESS:AAPL", "2026-06-08T15:00:00Z", "300")

    private inner class Inner(
        private val marks: MarkPrices? = null,
    ) : MarketSource {
        override val name = "inner"
        override val capabilities = setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

        override fun supports(symbol: String) = true

        override fun marksFor(symbol: String) = marks

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ) = (ticks + aapl).filter { it.symbol == symbol }.asSequence()

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ) = sequenceOf(
            Candle(
                symbol,
                BigDecimal(1190),
                BigDecimal(1210),
                BigDecimal(1180),
                BigDecimal(1200),
                BigDecimal(7),
                0L,
                ms("2026-06-09T10:00:00Z"),
            ),
            Candle(
                symbol,
                BigDecimal(120),
                BigDecimal(122),
                BigDecimal(119),
                BigDecimal(121),
                BigDecimal(7),
                ms("2026-06-09T10:00:00Z"),
                ms("2026-06-09T10:01:00Z"),
            ),
        )
    }

    private object NoMarks : MarkPrices {
        override fun at(
            symbol: String,
            windowMs: Long,
            atMs: Long,
        ) = null
    }

    private val range = TimeRange(Instant.EPOCH, Instant.parse("2026-07-01T00:00:00Z"))

    @Test
    fun `ticks before the ex-date session are divided by the ratio, volumes multiplied, later ticks raw`() {
        val (before, after) = SplitAdjustedMarketSource(Inner(), adjustment).ticks("EXNESS:NVDA", range).toList()
        assertThat(before.price).isEqualByComparingTo("120")
        assertThat(before.bid).isEqualByComparingTo("119.95")
        assertThat(before.ask).isEqualByComparingTo("120.05")
        assertThat(before.volume).isEqualByComparingTo("50")
        assertThat(before.bidVolume).isEqualByComparingTo("20")
        assertThat(before.askVolume).isEqualByComparingTo("30")
        assertThat(after).isSameAs(ticks[1])
    }

    @Test
    fun `tick slices read the same adjusted series`() {
        val source = SplitAdjustedMarketSource(Inner(), adjustment)
        val sliced = source.tickSlice("EXNESS:NVDA", 0L, ms("2026-06-10T00:00:00Z")).toList()
        assertThat(
            sliced.map {
                it.price
            },
        ).containsExactlyElementsOf(source.ticks("EXNESS:NVDA", range).map { it.price }.toList())
    }

    @Test
    fun `a bar is adjusted by its last instant, so a bar ending at the ex-date open is the old units`() {
        val (old, new) =
            SplitAdjustedMarketSource(
                Inner(),
                adjustment,
            ).bars("EXNESS:NVDA", TimeWindow.ONE_MINUTE, range).toList()
        assertThat(listOf(old.open, old.high, old.low, old.close).map { it.toPlainString() })
            .containsExactly("119.00000000", "121.00000000", "118.00000000", "120.00000000")
        assertThat(old.volume).isEqualByComparingTo("70")
        assertThat(new.close).isEqualByComparingTo("121")
        assertThat(new.volume).isEqualByComparingTo("7")
    }

    @Test
    fun `a symbol without a split is served exactly as the inner source serves it`() {
        val served = SplitAdjustedMarketSource(Inner(), adjustment).ticks("EXNESS:AAPL", range).single()
        assertThat(served).isSameAs(aapl)
    }

    @Test
    fun `live ticks and unadjusted mark series of a split symbol are refused`() {
        val source = SplitAdjustedMarketSource(Inner(marks = NoMarks), adjustment)
        assertThatThrownBy { source.liveTicks(listOf("EXNESS:NVDA")) }.hasMessageContaining("trade raw prices live")
        assertThatThrownBy { source.marksFor("EXNESS:NVDA") }.hasMessageContaining("not split-adjusted")
        assertThat(source.marksFor("EXNESS:AAPL")).isNotNull
    }
}
