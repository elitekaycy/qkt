package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.NoopStatePersistor
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

object RiskStatePersistenceFixtures {
    data class Fixture(
        val state: RiskState,
        val pnl: PnLCalculator,
    )

    fun fixture(
        clock: TestClock,
        persistor: NoopStatePersistor,
        initialBalance: BigDecimal = BigDecimal("10000"),
    ): Fixture {
        val prices = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        strategyPnL.setStartingBalance("s1", initialBalance)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        return Fixture(
            RiskState(
                pnl,
                strategyPnL,
                clock,
                bus,
                initialBalance = initialBalance,
                persist = { snap -> persistor.saveRiskState("s1", snap) },
            ),
            pnl,
        )
    }

    fun riskState(
        clock: TestClock,
        persistor: NoopStatePersistor,
    ): RiskState = fixture(clock, persistor, BigDecimal.ZERO).state
}
