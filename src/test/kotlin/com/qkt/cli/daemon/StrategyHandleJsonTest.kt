package com.qkt.cli.daemon

import com.qkt.app.SessionPnl
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyHandleJsonTest {
    @Test
    fun `buildSnapshot reflects strategy PnL instead of hardcoded zero`() {
        val snap =
            buildSnapshot(
                strategyName = "s",
                strategyVersion = 1,
                startMs = 0L,
                startedAt = "t",
                trades = emptyList(),
                pnl =
                    SessionPnl(
                        equity = BigDecimal("10100"),
                        balance = BigDecimal("10050"),
                        realized = BigDecimal("50"),
                        unrealized = BigDecimal("50"),
                    ),
            )
        assertThat(snap.equity).isEqualByComparingTo("10100")
        assertThat(snap.balance).isEqualByComparingTo("10050")
        assertThat(snap.realized).isEqualByComparingTo("50")
        assertThat(snap.unrealized).isEqualByComparingTo("50")
    }
}
