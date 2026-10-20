package com.qkt.trade.session

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayDiffTest {
    private fun fill(price: String) =
        ReplayFill("agent", "EXNESS:XAUUSD", "BUY", BigDecimal("0.01"), BigDecimal(price), 1L)

    @Test
    fun `a record made before the gateway's float noise was rounded still has the same prices`() {
        val diff = ReplayDiff.of(listOf(fill("4127.5830000000005")), listOf(fill("4127.583")))

        assertThat(diff.samePrices).isTrue()
        assertThat(diff.identical).isTrue()
    }

    @Test
    fun `a price off by a venue point is still a different price`() {
        val diff = ReplayDiff.of(listOf(fill("4127.583")), listOf(fill("4127.584")))

        assertThat(diff.samePrices).isFalse()
    }
}
