package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A hub slot is where a DSL stream's bars are built, so a late tick dropped there is a bar that
 * silently disagrees with the venue. Counting only the default window aggregator reported zero
 * for a multi-stream strategy while its bars were under-filling — observed live when a venue
 * stall backfilled a burst of ticks whose bars had already closed.
 */
class CandleHubLateDropTest {
    private fun tick(
        ms: Long,
        price: String = "4700.0",
    ) = Tick("EXNESS:XAUUSD", Money.of(price), ms)

    @Test
    fun `late ticks dropped inside hub slots are counted`() {
        val hub = CandleHub()
        val key = HubKey("EXNESS", "XAUUSD", "1m")
        hub.register(key, retention = 5, strategyId = "s")
        assertThat(hub.droppedLateTicks()).isZero()

        hub.feed(tick(60_000L))
        // Close the first bar by crossing its boundary, then deliver a tick that belonged to it.
        hub.feed(tick(120_001L))
        hub.feed(tick(90_000L))

        assertThat(hub.droppedLateTicks()).isEqualTo(1L)
    }

    @Test
    fun `drops are summed across every registered slot`() {
        val hub = CandleHub()
        val fast = HubKey("EXNESS", "XAUUSD", "1m")
        val slow = HubKey("EXNESS", "XAUUSD", "5m")
        hub.register(fast, retention = 5, strategyId = "s")
        hub.register(slow, retention = 5, strategyId = "s")

        hub.feed(tick(300_000L))
        hub.feed(tick(600_001L))
        hub.feed(tick(420_000L))

        // The one late tick belonged to a closed bar in BOTH slots.
        assertThat(hub.droppedLateTicks()).isEqualTo(2L)
    }
}
