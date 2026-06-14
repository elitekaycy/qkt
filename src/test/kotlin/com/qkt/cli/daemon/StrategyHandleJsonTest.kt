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

    @Test
    fun `buildSnapshot reports open positions instead of a hardcoded empty list`() {
        val snap =
            buildSnapshot(
                strategyName = "s",
                strategyVersion = 1,
                startMs = 0L,
                startedAt = "t",
                trades = emptyList(),
                openPositions =
                    listOf(
                        com.qkt.positions.Position("EXNESS:XAUUSD", BigDecimal("0.13"), BigDecimal("4140")),
                        com.qkt.positions.Position("BYBIT_LINEAR:BTCUSDT", BigDecimal("-0.5"), BigDecimal("65000")),
                    ),
            )
        assertThat(snap.positions.map { it.symbol })
            .containsExactly("EXNESS:XAUUSD", "BYBIT_LINEAR:BTCUSDT")
        val gold = snap.positions.first { it.symbol == "EXNESS:XAUUSD" }
        assertThat(gold.qty).isEqualByComparingTo("0.13")
        assertThat(gold.avgPrice).isEqualByComparingTo("4140")
        // A short position keeps its negative quantity so the operator sees the side.
        assertThat(snap.positions.first { it.symbol == "BYBIT_LINEAR:BTCUSDT" }.qty)
            .isEqualByComparingTo("-0.5")
    }
}
