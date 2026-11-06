package com.qkt.backtest.report

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionFacts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExecutionFactsRejectionTextTest {
    private fun rejections(every: Int) =
        ExecutionFactsText
            .lines(ExecutionFacts(broker = BrokerKind.MT5_SIM, rejectEvery = every))
            .toMap()
            .getValue("Rejections")

    @Test
    fun `rejecting every order says so plainly, otherwise names the ordinal`() {
        assertThat(rejections(1)).isEqualTo("every order is rejected")
        assertThat(rejections(2)).isEqualTo("every 2nd order is rejected")
        assertThat(rejections(3)).isEqualTo("every 3rd order is rejected")
        assertThat(rejections(11)).isEqualTo("every 11th order is rejected")
        assertThat(rejections(21)).isEqualTo("every 21st order is rejected")
    }
}
