package com.qkt.trade.session

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayDiffPairsTest {
    private val base = ReplayFill("agent", "EXNESS:XAUUSD", "BUY", BigDecimal("0.01"), BigDecimal("2400"), 1L)

    @Test
    fun `a fill only one side has is a mismatch rendered against null`() {
        val diff = ReplayDiff.of(listOf(base), emptyList())

        assertThat(diff.identical).isFalse()
        assertThat(diff.sameTrades).isFalse()
        assertThat(diff.mismatches).containsExactly(base to null)
        assertThat(diff.json())
            .startsWith("""{"identical":false,"sameTrades":false,"samePrices":false,"recordedFills":1,""")
            .contains(""""replayed":null""")
            .contains(""""qty":0.01,"price":2400,"timeMs":1""")
    }

    @Test
    fun `a replay with an extra fill pairs it against a missing record`() {
        val diff = ReplayDiff.of(emptyList(), listOf(base))

        assertThat(diff.mismatches).containsExactly(null to base)
        assertThat(diff.json()).contains(""""recorded":null""").contains(""""replayedFills":1""")
    }

    @Test
    fun `a different identity or symbol is a different trade`() {
        val otherIdentity = ReplayDiff.of(listOf(base), listOf(base.copy(identity = "hedge")))
        val otherSymbol = ReplayDiff.of(listOf(base), listOf(base.copy(symbol = "EXNESS:XAGUSD")))

        assertThat(otherIdentity.sameTrades).isFalse()
        assertThat(otherIdentity.mismatches).hasSize(1)
        assertThat(otherSymbol.sameTrades).isFalse()
        assertThat(otherSymbol.mismatches).hasSize(1)
    }

    @Test
    fun `a different side or quantity is a different trade`() {
        val otherSide = ReplayDiff.of(listOf(base), listOf(base.copy(side = "SELL")))
        val otherQty = ReplayDiff.of(listOf(base), listOf(base.copy(quantity = BigDecimal("0.02"))))

        assertThat(otherSide.sameTrades).isFalse()
        assertThat(otherSide.identical).isFalse()
        assertThat(otherQty.sameTrades).isFalse()
        assertThat(otherQty.identical).isFalse()
    }

    @Test
    fun `the same trade at another time keeps its trades and prices but is not identical`() {
        val diff = ReplayDiff.of(listOf(base), listOf(base.copy(timeMs = 2L, quantity = BigDecimal("0.010"))))

        assertThat(diff.sameTrades).isTrue()
        assertThat(diff.samePrices).isTrue()
        assertThat(diff.identical).isFalse()
    }

    @Test
    fun `an identical replay has no mismatches`() {
        val diff = ReplayDiff.of(listOf(base, base.copy(side = "SELL")), listOf(base, base.copy(side = "SELL")))

        assertThat(diff.identical).isTrue()
        assertThat(diff.json()).endsWith(""""mismatches":[]}""")
    }
}
