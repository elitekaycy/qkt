package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SharedLiveMarketSourceForwardingTest {
    @Test
    fun `historical reads and capabilities pass straight to the delegate`() {
        val probe = DelegationProbe()
        val shared = SharedLiveMarketSource(probe)

        assertThat(shared.capabilitiesFor("X")).containsExactly(MarketSourceCapability.TICKS)
        assertThat(shared.bars("X", TimeWindow(60_000L), probeRange()).toList()).hasSize(1)
        assertThat(shared.ticks("X", probeRange()).single().timestamp).isEqualTo(1L)
        assertThat(shared.tickSlice("X", 3L, 4L).single().timestamp).isEqualTo(2L)
        assertThat(probe.calls).containsExactly("capabilitiesFor:X", "bars:X:60000", "ticks:X", "tickSlice:X:3:4")
    }

    @Test
    fun `closing ends live subscribers closes the delegate once and refuses new feeds`() {
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate)
        val feed = shared.liveTicks(listOf("A"))

        shared.close()
        shared.close()

        assertThat(feed.next()).isNull()
        assertThat(delegate.awaitClosed("A")).isTrue()
        assertThatThrownBy { shared.liveTicks(listOf("A")) }.hasMessageContaining("is closed")
    }

    @Test
    fun `closing closes a closeable delegate`() {
        val probe = DelegationProbe()

        SharedLiveMarketSource(probe).close()

        assertThat(probe.closed).isEqualTo(1)
    }
}
