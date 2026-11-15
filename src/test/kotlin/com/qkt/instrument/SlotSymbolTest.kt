package com.qkt.instrument

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlotSymbolTest {
    @Test
    fun `a slot symbol carries its family, event and strike parts`() {
        val slot = requireNotNull(SlotSymbol.parse("KALSHI:KXINXU@next.above2"))

        assertThat(slot).isEqualTo(SlotSymbol("KALSHI:KXINXU", "next", "above2"))
        assertThat(slot.eventOffset).isEqualTo(1)
        assertThat(slot.symbol).isEqualTo("KALSHI:KXINXU@next.above2")
        assertThat(slot.rule).isNotNull()
        assertThat(QktSymbols.isFileSafe(slot.symbol)).isTrue()
    }

    @Test
    fun `continuous futures, plain symbols and malformed specs are no slots`() {
        val others =
            listOf(
                "CME:ES@front",
                "KALSHI:KXT_E1_T2",
                "KXINXU@front.p70",
                "KALSHI:KXINXU@later.p70",
                "KALSHI:KXINXU@front.p99",
                "KALSHI:KXINXU@front.",
            )
        for (symbol in others) {
            assertThat(SlotSymbol.isSlot(symbol)).describedAs(symbol).isFalse()
        }
        assertThat(SlotSymbol.isSlot("KALSHI:KXINXU@front.p70")).isTrue()
    }
}
