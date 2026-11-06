package com.qkt.derivatives.events

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventChainSymbolTest {
    @Test
    fun `an event stream ends in an event selector where an option stream has a tenor`() {
        val stream = EventChainSymbol.parse("CHAIN:KALSHI.KXINXU.implied_median.next")

        assertThat(stream).isEqualTo(EventChainSymbol("KALSHI:KXINXU", "implied_median", "next"))
        assertThat(stream.eventOffset).isEqualTo(1)
        assertThat(EventChainSymbol.isEventChain("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d")).isFalse()
        assertThat(EventChainSymbol.isEventChain("CHAIN:KALSHI.KXINXU.front")).isFalse()
        assertThat(EventChainSymbol.isEventChain("KALSHI:KXINXU.a.b.front")).isFalse()
    }

    @Test
    fun `a malformed or unknown metric fails naming the shape or the metrics`() {
        assertThatThrownBy { EventChainSymbol.parse("CHAIN:KALSHI.KXINXU.median.front") }
            .hasMessageContaining("unknown event metric 'median'")
            .hasMessageContaining("implied_median")
        assertThatThrownBy { EventChainSymbol.parse("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d") }
            .hasMessageContaining("CHAIN:<VENUE>.<FAMILY>.<metric>.<front|next>")
    }
}
