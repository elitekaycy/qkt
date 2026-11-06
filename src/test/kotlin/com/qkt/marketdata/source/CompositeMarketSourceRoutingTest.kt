package com.qkt.marketdata.source

import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.SideVolumes
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.marks.MarkPrices
import com.qkt.marketdata.marks.MarkSample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CompositeMarketSourceRoutingTest {
    private val marks: MarkPrices =
        object : MarkPrices {
            override fun at(
                symbol: String,
                windowMs: Long,
                atMs: Long,
            ): MarkSample? = null
        }
    private val flow: TradeFlow =
        object : TradeFlow {
            override fun window(
                symbol: String,
                kind: FlowKind,
                windowMs: Long,
                startMs: Long,
            ): SideVolumes? = null
        }
    private val venue = DelegationProbe("venue", marks = marks, flow = flow)
    private val fallback = DelegationProbe("fallback")
    private val composite = CompositeMarketSource(listOf(SymbolPattern.exact("VENUE:BTC") to venue), fallback)

    @Test
    fun `marks trade flow and capabilities come from the leaf serving the symbol`() {
        assertThat(composite.marksFor("VENUE:BTC")).isSameAs(marks)
        assertThat(composite.tradeFlowFor("VENUE:BTC")).isSameAs(flow)
        assertThat(composite.marksFor("VENUE:ETH")).isNull()
        assertThat(composite.tradeFlowFor("VENUE:ETH")).isNull()
        assertThat(composite.capabilitiesFor("VENUE:BTC")).containsExactly(MarketSourceCapability.TICKS)
        assertThat(venue.calls).containsExactly("capabilitiesFor:VENUE:BTC")
        assertThat(fallback.calls).isEmpty()
    }

    @Test
    fun `an exact pattern matches only its own symbol`() {
        val pattern = SymbolPattern.exact("VENUE:BTC")

        assertThat(pattern.matches("VENUE:BTC")).isTrue()
        assertThat(pattern.matches("VENUE:BTCUSD")).isFalse()
    }

    @Test
    fun `live ticks for no symbols are refused`() {
        assertThatThrownBy { composite.liveTicks(emptyList()) }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("no symbols supplied")
    }
}
