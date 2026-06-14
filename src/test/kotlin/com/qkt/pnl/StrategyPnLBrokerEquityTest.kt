package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #352 — live sizing and drawdown should track the broker's real account equity when the gateway
 * exposes it, falling back to the derived `startingBalance + realized + unrealized` for backtest
 * and paper so those stay deterministic.
 */
class StrategyPnLBrokerEquityTest {
    @Test
    fun `equityFor uses broker equity when the supplier provides it`() {
        val pnl =
            StrategyPnL(
                StrategyPositionTracker(),
                MarketPriceTracker(),
                brokerEquity = { Money.of("12345.67") },
            )
        pnl.setStartingBalance("alpha", Money.of("10000"))
        // Derived would be 10000 (no fills); the broker's real equity wins.
        assertThat(pnl.equityFor("alpha")).isEqualByComparingTo("12345.67")
    }

    @Test
    fun `equityFor falls back to derived equity when the supplier returns null`() {
        val pnl =
            StrategyPnL(
                StrategyPositionTracker(),
                MarketPriceTracker(),
                brokerEquity = { null },
            )
        pnl.setStartingBalance("alpha", Money.of("10000"))
        assertThat(pnl.equityFor("alpha")).isEqualByComparingTo("10000")
    }

    @Test
    fun `default StrategyPnL derives equity with no broker supplier`() {
        val pnl = StrategyPnL(StrategyPositionTracker(), MarketPriceTracker())
        pnl.setStartingBalance("alpha", Money.of("10000"))
        assertThat(pnl.equityFor("alpha")).isEqualByComparingTo("10000")
    }
}
