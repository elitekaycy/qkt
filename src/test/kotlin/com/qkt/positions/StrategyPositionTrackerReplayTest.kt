package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Reproduction of #1096: an execution the leg book already holds must not be booked again.
 * Restart recovery re-publishes fills for entries that were already booked; the tracker must
 * treat a re-report on an owned venue ticket as the same execution, never as new quantity.
 */
class StrategyPositionTrackerReplayTest {
    private fun partial(
        clientOrderId: String,
        ticket: String,
        qty: String,
        cumulative: String = qty,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = clientOrderId,
        brokerOrderId = ticket,
        symbol = "ICM_S10:EURUSD",
        side = Side.SELL,
        price = Money.of("1.15764"),
        quantity = Money.of(qty),
        strategyId = "eurusd_rsi_fade",
        timestamp = 1L,
    )

    @Test
    fun `re-reporting the same execution on an owned ticket books it once`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("eurusd_rsi_fade", "dsl-eurusd_rsi_fade--26", "leg-26")
        intents.apply(tracker, partial("dsl-eurusd_rsi_fade--26", "1902345739", "3.56"), Money.of("3.56"))
        // restart recovery replays the same partial (cumulative 3.56) for the same ticket
        val replay =
            intents.applyDetailed(
                tracker,
                partial("dsl-eurusd_rsi_fade--26", "1902345739", "3.56"),
                Money.of("3.56"),
            )
        assertThat(replay.unbooked).isTrue()

        val leg = tracker.legBookFor("eurusd_rsi_fade", "ICM_S10:EURUSD")!!.all().single()
        assertThat(leg.brokerTicket).isEqualTo("1902345739")
        assertThat(leg.quantity).isEqualByComparingTo("3.56")
    }

    @Test
    fun `a genuine further slice on an owned ticket books only the venue's increment`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("eurusd_rsi_fade", "dsl-eurusd_rsi_fade--26", "leg-26")
        intents.apply(tracker, partial("dsl-eurusd_rsi_fade--26", "1902345739", "2.00"), Money.of("2.00"))
        intents.apply(tracker, partial("dsl-eurusd_rsi_fade--26", "1902345739", "1.56"), Money.of("3.56"))

        val leg = tracker.legBookFor("eurusd_rsi_fade", "ICM_S10:EURUSD")!!.all().single()
        assertThat(leg.quantity).isEqualByComparingTo("3.56")
    }

    @Test
    fun `a venue ticket belongs to exactly one leg`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("eurusd_rsi_fade", "dsl-eurusd_rsi_fade--26", "leg-26")
        intents.independentOpen("eurusd_rsi_fade", "dsl-eurusd_rsi_fade--2", "leg-2")
        intents.apply(tracker, partial("dsl-eurusd_rsi_fade--26", "1902345739", "3.56"), Money.of("3.56"))
        // a stale order mis-attributed to the same ticket reports a full fill for it
        val replay = intents.applyDetailed(tracker, partial("dsl-eurusd_rsi_fade--2", "1902345739", "3.56"))
        assertThat(replay.unbooked).isTrue()

        val book = tracker.legBookFor("eurusd_rsi_fade", "ICM_S10:EURUSD")!!
        assertThat(book.all()).hasSize(1)
        assertThat(book.all().single().quantity).isEqualByComparingTo("3.56")
    }
}
