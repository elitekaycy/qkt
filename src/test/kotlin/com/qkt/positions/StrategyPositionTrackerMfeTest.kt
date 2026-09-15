package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionTrackerMfeTest {
    private fun fill(
        strategyId: String,
        clientOrderId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
        timestamp: Long = 0L,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = clientOrderId,
        brokerOrderId = clientOrderId,
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        strategyId = strategyId,
        timestamp = timestamp,
    )

    @Test
    fun `primaryMfeFor returns null before any fill`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isNull()
    }

    @Test
    fun `primary fill installs a fresh MFE tracker at zero`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("0")
    }

    @Test
    fun `BUY primary MFE rises on favorable ticks and never decreases`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        tracker.onTick("BTCUSDT", BigDecimal("105"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("5")
        tracker.onTick("BTCUSDT", BigDecimal("110"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
        tracker.onTick("BTCUSDT", BigDecimal("95")) // unfavorable — MFE stays
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
    }

    @Test
    fun `BUY primary MAE rises on adverse ticks and never decreases`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        tracker.onTick("BTCUSDT", BigDecimal("95"))
        assertThat(tracker.primaryMaeFor("alpha", "BTCUSDT")).isEqualByComparingTo("5")
        tracker.onTick("BTCUSDT", BigDecimal("98"))
        assertThat(tracker.primaryMaeFor("alpha", "BTCUSDT")).isEqualByComparingTo("5")
        tracker.onTick("BTCUSDT", BigDecimal("90"))
        assertThat(tracker.primaryMaeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
    }

    @Test
    fun `SELL primary MFE rises on downward ticks`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.SELL, "1", "100"))
        tracker.onTick("BTCUSDT", BigDecimal("95"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("5")
        tracker.onTick("BTCUSDT", BigDecimal("105")) // unfavorable for SELL — MFE stays
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("5")
    }

    @Test
    fun `closing primary fully removes the MFE tracker`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        tracker.onTick("BTCUSDT", BigDecimal("110"))
        // Close the primary
        intents.apply(tracker, fill("alpha", "c-2", "BTCUSDT", Side.SELL, "1", "110"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isNull()
    }

    @Test
    fun `flipping primary re-anchors MFE to the new entry`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        tracker.onTick("BTCUSDT", BigDecimal("110"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
        // Flip: SELL 2 against BUY 1 — net SELL 1 at trade price 110
        intents.apply(tracker, fill("alpha", "c-2", "BTCUSDT", Side.SELL, "2", "110"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("0")
        // Downward tick now grows MFE on the SELL primary
        tracker.onTick("BTCUSDT", BigDecimal("100"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
    }

    @Test
    fun `averaging fill re-anchors MFE to the new weighted entry`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        tracker.onTick("BTCUSDT", BigDecimal("110"))
        // Add to BUY at 130 — new weighted entry = (100+130)/2 = 115
        intents.apply(tracker, fill("alpha", "c-2", "BTCUSDT", Side.BUY, "1", "130"))
        // MFE reset because the tracker was rebuilt at the new entry
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("0")
        tracker.onTick("BTCUSDT", BigDecimal("125"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
    }

    @Test
    fun `an INDEPENDENT entry on a hedging venue tracks MFE and MAE like a primary`() {
        // Hedging venues open every plain BUY/SELL as an INDEPENDENT leg (no PRIMARY exists),
        // yet `POSITION.<stream>.mfe` must still measure the strategy's entry.
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "c-1", "leg-1")
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("0")

        tracker.onTick("BTCUSDT", BigDecimal("110"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
        tracker.onTick("BTCUSDT", BigDecimal("95"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
        assertThat(tracker.primaryMaeFor("alpha", "BTCUSDT")).isEqualByComparingTo("5")
    }

    @Test
    fun `the oldest INDEPENDENT leg anchors MFE and a later add does not re-anchor it`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "c-1", "leg-1")
        intents.independentOpen("alpha", "c-2", "leg-2")
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100", timestamp = 1L))
        tracker.onTick("BTCUSDT", BigDecimal("110"))
        intents.apply(tracker, fill("alpha", "c-2", "BTCUSDT", Side.BUY, "1", "110", timestamp = 2L))
        // Adding a peer leg keeps the entry leg's excursion intact.
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
    }

    @Test
    fun `closing the INDEPENDENT entry leg hands MFE to the next oldest leg or removes it`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "c-1", "leg-1")
        intents.independentOpen("alpha", "c-2", "leg-2")
        intents.close("alpha", "x-1", "leg-1")
        intents.close("alpha", "x-2", "leg-2")
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100", timestamp = 1L))
        intents.apply(tracker, fill("alpha", "c-2", "BTCUSDT", Side.BUY, "1", "120", timestamp = 2L))
        tracker.onTick("BTCUSDT", BigDecimal("130"))

        intents.apply(tracker, fill("alpha", "x-1", "BTCUSDT", Side.SELL, "1", "130", timestamp = 3L))
        // leg-2 (entry 120) is now the entry leg; its tracker starts fresh.
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("0")
        tracker.onTick("BTCUSDT", BigDecimal("125"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("5")

        intents.apply(tracker, fill("alpha", "x-2", "BTCUSDT", Side.SELL, "1", "125", timestamp = 4L))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isNull()
    }

    @Test
    fun `onTick on a symbol with no positions is a no-op`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        tracker.onTick("BTCUSDT", BigDecimal("100"))
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isNull()
    }

    @Test
    fun `STACK leg fills do not affect primary MFE tracker`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "c-1", "BTCUSDT", Side.BUY, "1", "100"))
        tracker.onTick("BTCUSDT", BigDecimal("110"))

        intents.stackOpen("alpha", "stack-entry", "stack-1", "primary-1")
        intents.apply(tracker, fill("alpha", "stack-entry", "BTCUSDT", Side.BUY, "0.5", "110"))
        // Primary MFE preserved
        assertThat(tracker.primaryMfeFor("alpha", "BTCUSDT")).isEqualByComparingTo("10")
    }
}
