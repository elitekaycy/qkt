package com.qkt.dsl.portfolio

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The portfolio gate evaluates its rules against a context with no market, no positions and no P&L. */
class EmptyGateViewsTest {
    @Test
    fun `the gate's market source supports no symbol and declares no capability`() {
        assertThat(EmptySource.name).isEqualTo("PortfolioGate")
        assertThat(EmptySource.capabilities).isEmpty()
        assertThat(EmptySource.supports("BACKTEST:BTCUSDT")).isFalse
    }

    @Test
    fun `the gate's position view holds nothing`() {
        assertThat(EmptyPositions.positionFor("BACKTEST:BTCUSDT")).isNull()
        assertThat(EmptyPositions.allPositions()).isEmpty()
        assertThat(EmptyPositions.maeFor("BACKTEST:BTCUSDT")).isNull()
    }

    @Test
    fun `the gate's P&L view is zero everywhere`() {
        val reads =
            listOf(
                EmptyPnL.realized(),
                EmptyPnL.unrealizedFor("BACKTEST:BTCUSDT"),
                EmptyPnL.unrealizedTotal(),
                EmptyPnL.total(),
                EmptyPnL.equity(),
                EmptyPnL.balance(),
            )

        assertThat(reads).allSatisfy { assertThat(it).isEqualByComparingTo(BigDecimal.ZERO) }
    }
}
