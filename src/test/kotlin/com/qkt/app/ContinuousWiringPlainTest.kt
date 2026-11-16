package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.persistence.NoopStatePersistor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContinuousWiringPlainTest {
    private val clock = FixedClock(0L)

    @Test
    fun `a session with no instrument registry serves its source as given and has no chains`() {
        val wiring =
            ContinuousWiring(listOf("BACKTEST:XAUUSD"), null, NullMarketSource, clock, NoopStatePersistor(), "gold")

        assertThat(wiring.chains).isNull()
        assertThat(wiring.source).isSameAs(NullMarketSource)
    }

    @Test
    fun `a lane bus bound before the session's mailbox exists is refused`() {
        val wiring = ContinuousWiring(emptyList(), null, NullMarketSource, clock, NoopStatePersistor(), "gold")

        assertThatThrownBy { wiring.bindLane(EventBus(clock, MonotonicSequenceGenerator())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("a lane bus was bound before the session's mailbox existed")
    }
}
