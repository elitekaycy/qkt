package com.qkt.marketdata.source

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketSourceCapabilityTest {
    @Test
    fun `enumerates all expected capabilities`() {
        assertThat(MarketSourceCapability.entries).containsExactlyInAnyOrder(
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
            MarketSourceCapability.TICKS,
        )
    }
}
