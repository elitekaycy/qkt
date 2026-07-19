package com.qkt.broker.mt5

import com.qkt.execution.ExitReason
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5ExitReasonTest {
    @Test
    fun `closing deal reasons distinguish stop target and other closes`() {
        assertThat(closingDealExitReason(listOf(deal(reason = 4)))).isEqualTo(ExitReason.STOP)
        assertThat(closingDealExitReason(listOf(deal(reason = 5)))).isEqualTo(ExitReason.TAKE_PROFIT)
        assertThat(closingDealExitReason(listOf(deal(reason = 3)))).isEqualTo(ExitReason.CLOSE)
    }

    @Test
    fun `mixed closing reasons fail safely to CLOSE`() {
        assertThat(closingDealExitReason(listOf(deal(reason = 4), deal(reason = 5))))
            .isEqualTo(ExitReason.CLOSE)
    }

    private fun deal(reason: Int): MT5Deal =
        MT5Deal(
            ticket = reason.toLong(),
            orderTicket = 1L,
            positionTicket = 2L,
            symbol = "XAUUSDm",
            type = 1,
            entry = 1,
            volume = BigDecimal.ONE,
            price = BigDecimal("100"),
            profit = BigDecimal.ZERO,
            commission = BigDecimal.ZERO,
            swap = BigDecimal.ZERO,
            fee = BigDecimal.ZERO,
            magic = 10001,
            comment = null,
            timeMs = 1L,
            reason = reason,
        )
}
