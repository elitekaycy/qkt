package com.qkt.risk

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PacerLedgerTest {
    @Test
    fun `tradesToday counts entry fills since UTC midnight`() {
        val ledger = PacerLedger()
        val midnight = 1_705_276_800_000L
        ledger.recordEntryFill("s", midnight - 1)
        ledger.recordEntryFill("s", midnight + 1)
        ledger.recordEntryFill("s", midnight + 2)

        assertThat(ledger.tradesToday("s", midnight + 12 * 3_600_000L)).isEqualTo(2)
    }

    @Test
    fun `loss streak increments on losses and resets on wins`() {
        val ledger = PacerLedger()
        ledger.recordOutcome("s", 100L, BigDecimal("-1"))
        ledger.recordOutcome("s", 200L, BigDecimal("-2"))
        assertThat(ledger.lossStreak("s")).isEqualTo(2)

        ledger.recordOutcome("s", 300L, BigDecimal("5"))
        assertThat(ledger.lossStreak("s")).isZero
    }

    @Test
    fun `cooldown remaining is active after configured consecutive losses`() {
        val ledger = PacerLedger()
        ledger.recordOutcome("s", 1_000L, BigDecimal("-1"))
        ledger.recordOutcome("s", 2_000L, BigDecimal("-1"))

        assertThat(ledger.cooldownRemainingMs("s", 3_000L, durationMs = 10_000L, afterConsecutive = 2))
            .isEqualTo(9_000L)
    }
}
