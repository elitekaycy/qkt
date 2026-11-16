package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.quote
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.source.QuoteMapSource.Companion.at
import com.qkt.marketdata.source.QuoteMapSource.Companion.range
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A routing source hands a slot's windowed ticks and followed contract to its leaf; a plain source has neither. */
class CompositeSlotRoutingTest {
    private val slot = "KALSHI:KXS@front.atm"
    private val quotes =
        listOf("10:00", "10:01").map { quote(symbol(1, 3), at(it), "0.49", "0.51") }

    @Test
    fun `a plain source serves its ticks for any window and follows nothing`() {
        val plain = QuoteMapSource(quotes)

        assertThat(plain.sampledTicks(symbol(1, 3), TimeWindow.parse("1m"), range()).toList()).hasSize(2)
        assertThat(plain.slotContractAt(slot, at("10:02"))).isNull()
    }

    @Test
    fun `the composite routes slot reads to its leaf`() {
        val slots = SlotMarketSource(QuoteMapSource(quotes), SlotFamilyFixture.registry(), listOf(slot))
        val macro = SymbolPattern.prefix("MACRO:") to QuoteMapSource(emptyList())
        val composite = CompositeMarketSource(listOf(macro), slots)

        val ticks = composite.sampledTicks(slot, TimeWindow.parse("1m"), range()).filter { it.symbol == slot }.toList()

        assertThat(ticks.map { it.timestamp }).containsExactly(at("10:01"))
        assertThat(composite.slotContractAt(slot, at("10:02"))).isEqualTo(symbol(1, 3))
    }
}
