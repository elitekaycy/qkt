package com.qkt.marketdata.source

import com.qkt.instrument.FamilyDirectory
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.quote
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.source.QuoteMapSource.Companion.at
import com.qkt.marketdata.source.QuoteMapSource.Companion.range
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FamilyTicksTest {
    private val contracts = FamilyDirectory(SlotFamilyFixture.registry()).contracts(SlotFamilyFixture.ROOT)

    @Test
    fun `contract ticks merge in time order, ties by catalog order, only while each contract is alive`() {
        val source =
            QuoteMapSource(
                listOf(
                    quote(symbol(2, 1), at("10:00"), "0.1", "0.2"),
                    quote(symbol(1, 2), at("10:00"), "0.3", "0.4"),
                    quote(symbol(1, 1), at("10:05"), "0.5", "0.6"),
                    quote(symbol(1, 1), at("11:00"), "0.5", "0.6"),
                    quote(symbol(3, 4), at("08:59"), "0.5", "0.6"),
                ),
            )

        val merged = FamilyTicks.merged(source, contracts, range()).toList()

        assertThat(merged.map { it.symbol to it.timestamp }).containsExactly(
            symbol(1, 2) to at("10:00"),
            symbol(2, 1) to at("10:00"),
            symbol(1, 1) to at("10:05"),
        )
    }

    @Test
    fun `a window outside every life reads nothing and opens nothing`() {
        val source = QuoteMapSource(listOf(quote(symbol(1, 1), at("10:00"), "0.1", "0.2")))
        val later = range("2024-03-05T00:00:00Z", "2024-03-06T00:00:00Z")

        assertThat(FamilyTicks.merged(source, contracts, later).toList()).isEmpty()
        assertThat(source.opened).isEmpty()
    }
}
