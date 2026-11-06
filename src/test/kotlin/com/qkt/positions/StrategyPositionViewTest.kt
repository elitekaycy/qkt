package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionViewTest {
    @Test
    fun `a view that tracks no legs answers the leg questions with nothing`() {
        val view =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions(): Map<String, Position> = emptyMap()
            }

        assertThat(view.mfeFor("X")).isNull()
        assertThat(view.maeFor("X")).isNull()
        assertThat(view.openCountFor("X")).isZero()
        assertThat(view.longCountFor("X")).isZero()
        assertThat(view.shortCountFor("X")).isZero()
        assertThat(view.grossFor("X")).isEqualByComparingTo("0")
        assertThat(view.legsFor("X")).isEmpty()
    }

    @Test
    fun `the tracker-backed view answers for its own strategy's legs`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, trackerFill("alpha", "p", "XAUUSD", Side.BUY, "1", "100"))
        intents.independentOpen("alpha", "h", "hedge")
        intents.apply(tracker, trackerFill("alpha", "h", "XAUUSD", Side.SELL, "2", "100"))
        intents.apply(tracker, trackerFill("beta", "b", "XAUUSD", Side.BUY, "5", "100"))
        tracker.onTick("XAUUSD", BigDecimal("105"))
        tracker.onTick("XAUUSD", BigDecimal("98"))
        val view = StrategyPositionViewImpl(tracker, "alpha")

        assertThat(view.positionFor("XAUUSD")?.quantity).isEqualByComparingTo("-1")
        assertThat(view.allPositions()).containsOnlyKeys("XAUUSD")
        assertThat(view.mfeFor("XAUUSD")).isEqualByComparingTo("5")
        assertThat(view.maeFor("XAUUSD")).isEqualByComparingTo("2")
        assertThat(view.openCountFor("XAUUSD")).isEqualTo(2)
        assertThat(view.longCountFor("XAUUSD")).isEqualTo(1)
        assertThat(view.shortCountFor("XAUUSD")).isEqualTo(1)
        assertThat(view.grossFor("XAUUSD")).isEqualByComparingTo("3")
        assertThat(view.legsFor("XAUUSD").map { it.legId }).contains("hedge").hasSize(2)
        assertThat(view.legsFor("EURUSD")).isEmpty()
    }
}
