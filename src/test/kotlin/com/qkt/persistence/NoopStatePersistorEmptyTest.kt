package com.qkt.persistence

import com.qkt.positions.LegBook
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NoopStatePersistorEmptyTest {
    @Test
    fun `a strategy that never saved reads back as empty everywhere`() {
        val p = NoopStatePersistor()

        assertThat(p.legBookSymbols("ghost")).isEmpty()
        assertThat(p.loadStructures("ghost")).isEmpty()
        assertThat(p.loadExcursion("ghost", "XAUUSD")).isNull()
        assertThat(p.loadBracketPairs("ghost")).isEmpty()
        assertThat(p.loadPendingOrders("ghost")).isEmpty()
        assertThat(p.loadPendingStacks("ghost")).isEmpty()
        assertThat(p.loadTrailingStops("ghost")).isEmpty()
        assertThat(p.loadTimedExits("ghost")).isEmpty()
        assertThat(p.loadStreamLane("ghost", "gold")).isNull()
        assertThat(p.loadRiskState("ghost")).isNull()
        assertThat(p.loadPnl("ghost")).isNull()
        assertThat(p.loadTradeHistory("ghost")).isNull()
        assertThat(p.loadSequences("ghost")).isEmpty()
        assertThat(p.loadExitHooks("ghost")).isEmpty()
    }

    @Test
    fun `a strategy that saved other state still reads empty for what it did not save`() {
        val p = NoopStatePersistor()
        p.saveLegBook("alpha", "XAUUSD", LegBook("XAUUSD"))
        p.savePnl("alpha", PersistedPnl(BigDecimal.TEN))

        assertThat(p.legBookSymbols("alpha")).containsExactly("XAUUSD")
        assertThat(p.loadPnl("alpha")).isEqualTo(PersistedPnl(BigDecimal.TEN))
        assertThat(p.loadExcursion("alpha", "EURUSD")).isNull()
        assertThat(p.loadStreamLane("alpha", "gold")).isNull()
        assertThat(p.loadRiskState("alpha")).isNull()
        assertThat(p.loadTradeHistory("alpha")).isNull()
        assertThat(p.loadPendingOrders("alpha")).isEmpty()
        assertThat(p.loadTrailingStops("alpha")).isEmpty()
        assertThat(p.loadSequences("alpha")).isEmpty()
    }
}
