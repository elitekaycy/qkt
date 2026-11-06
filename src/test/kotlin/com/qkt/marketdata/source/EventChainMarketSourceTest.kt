package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.quote
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.source.QuoteMapSource.Companion.at
import com.qkt.marketdata.source.QuoteMapSource.Companion.range
import java.math.RoundingMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * `ladder_breaks` and `implied_median` over the `KXS` family: values come after each instant the family quotes, from
 * each contract's latest quote, for the event the stream selects; an instant where a metric is undefined has no tick.
 */
class EventChainMarketSourceTest {
    private val breaks = "CHAIN:KALSHI.KXS.ladder_breaks.front"
    private val median = "CHAIN:KALSHI.KXS.implied_median.next"
    private val quotes =
        listOf(
            quote(symbol(1, 1), at("10:00"), "0.60", "0.62"),
            quote(symbol(1, 2), at("10:00"), "0.65", "0.70"),
            quote(symbol(1, 2), at("10:01"), "0.55", "0.60"),
            quote(symbol(2, 1), at("10:02"), "0.69", "0.71"),
            quote(symbol(2, 2), at("10:02"), "0.29", "0.31"),
            quote(symbol(3, 1), at("12:30"), "0.49", "0.51"),
        )
    private val source =
        EventChainMarketSource(QuoteMapSource(quotes), SlotFamilyFixture.registry(), listOf(breaks, median))

    @Test
    fun `each family instant gives one value of the selected event`() {
        val ticks = source.ticks(breaks, range()).toList()

        assertThat(ticks.map { it.timestamp to it.price.toPlainString() })
            .containsExactly(at("10:00") to "1", at("10:01") to "0", at("10:02") to "0")
        assertThat(ticks.map { it.symbol }.distinct()).containsExactly(breaks)
    }

    @Test
    fun `an undefined value has no tick and bars are flat per observation`() {
        val bars = source.bars(median, TimeWindow.parse("1m"), range()).toList()

        // H2 is quoted only from 10:02: 0.70 at 100 and 0.30 at 200 cross one half at 150.
        assertThat(bars.map { it.startTime }).containsExactly(at("10:02"))
        assertThat(bars.single().close.setScale(6, RoundingMode.HALF_EVEN)).isEqualByComparingTo("150")
        assertThat(bars.single().endTime).isEqualTo(at("10:03"))
        // After 12:00 only H3 is open, so there is no next event to value.
        assertThat(source.ticks(median, range("2024-03-04T12:00:00Z", "2024-03-04T13:00:00Z")).toList()).isEmpty()
    }

    @Test
    fun `it serves event streams only and refuses an undeclared family`() {
        assertThat(source.name).isEqualTo("event-chain")
        assertThat(source.capabilities).contains(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)
        assertThat(source.supports(breaks)).isTrue()
        assertThat(source.supports("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d")).isFalse()
        assertThat(source.ticks(breaks, range("2024-03-04T13:00:00Z", "2024-03-05T00:00:00Z")).toList()).isEmpty()
        assertThatThrownBy { EventChainMarketSource(QuoteMapSource(quotes), NoopInstrumentRegistry, listOf(breaks)) }
            .hasMessageContaining("KALSHI:KXS is not an event family")
    }
}
