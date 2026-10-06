package com.qkt.observe

import com.qkt.events.CostIncurred
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditEventKeysCostTest {
    @Test
    fun `a venue cost is indexed by its strategy and symbol`() {
        val cost = CostIncurred("s", "BINANCE_UM:BTCUSDT@front", BigDecimal("2.5"), "roll A->B", null)

        assertThat(auditStrategyId(cost)).isEqualTo("s")
        assertThat(auditSymbol(cost)).isEqualTo("BINANCE_UM:BTCUSDT@front")
        assertThat(auditOrderId(cost)).isNull()
    }
}
