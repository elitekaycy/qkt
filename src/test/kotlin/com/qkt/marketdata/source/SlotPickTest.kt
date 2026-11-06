package com.qkt.marketdata.source

import com.qkt.instrument.FamilyDirectory
import com.qkt.instrument.FamilyEvent
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.quote
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.instrument.SlotSymbol
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.QuoteMapSource.Companion.at
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlotPickTest {
    private val events: List<FamilyEvent> =
        FamilyDirectory(SlotFamilyFixture.registry()).openEvents(SlotFamilyFixture.ROOT).at(at("10:00"))

    private fun pick(
        spec: String,
        quotes: List<Tick>,
        among: List<FamilyEvent> = events,
    ): String? {
        val slot = requireNotNull(SlotSymbol.parse("${SlotFamilyFixture.ROOT}@$spec"))
        val book = quotes.associateBy { it.symbol }
        return SlotPick.pick(slot, slot.rule, among, book::get)
    }

    @Test
    fun `only two-sided, uncrossed quotes are candidates`() {
        val quotes =
            listOf(
                quote(symbol(1, 1), 0L, "0.69", "0.71"),
                quote(symbol(1, 2), 0L, "0.70", null),
                quote(symbol(1, 3), 0L, null, "0.70"),
                quote(symbol(1, 4), 0L, "0.72", "0.68"),
            )

        assertThat(pick("front.p70", quotes)).isEqualTo(symbol(1, 1))
        assertThat(pick("front.p70", quotes.drop(1))).isNull()
    }

    @Test
    fun `next reads the second event, and a missing event picks nothing`() {
        val quotes = listOf(quote(symbol(2, 4), 0L, "0.10", "0.12"))

        assertThat(pick("next.top1", quotes)).isEqualTo(symbol(2, 4))
        assertThat(pick("next.top1", quotes, events.take(1))).isNull()
    }

    @Test
    fun `an event of one contract resolves to it whatever the strike part says`() {
        val single = listOf(events.first().copy(contracts = events.first().contracts.take(1)))

        assertThat(pick("front.above3", emptyList(), single)).isEqualTo(symbol(1, 1))
    }
}
