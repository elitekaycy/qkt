package com.qkt.marketdata

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A stored quote with one side (only an event import writes one) is priced at the side it has. */
class TickAssemblerOneSidedTest {
    private fun assemble(
        bid: String?,
        ask: String?,
    ) = TickAssembler.assemble(
        "KXT_E1_T2",
        1L,
        price = null,
        volume = null,
        bid = bid?.let(::BigDecimal),
        ask = ask?.let(::BigDecimal),
        bidVolume = null,
        askVolume = null,
        location = { "here" },
    )

    @Test
    fun `a bid-only or ask-only quote is priced at that side and a two-sided one at its mid`() {
        assertThat(assemble("0.41", null).price).isEqualByComparingTo("0.41")
        assertThat(assemble(null, "0.05").price).isEqualByComparingTo("0.05")
        assertThat(assemble("0.40", "0.42").price.toPlainString()).isEqualTo("0.41000000")
        assertThat(assemble(null, "0.05").mid).isNull()
    }

    @Test
    fun `a row with no price and no side is still refused`() {
        assertThatThrownBy { assemble(null, null) }.hasMessageContaining("here: row needs price OR bid OR ask")
    }
}
