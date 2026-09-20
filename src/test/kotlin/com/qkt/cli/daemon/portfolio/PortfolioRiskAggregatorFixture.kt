package com.qkt.cli.daemon.portfolio

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.PersistedRiskState
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal

abstract class PortfolioRiskAggregatorFixture {
    protected companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    protected class FakeChild : ChildRiskTarget {
        var flattened = 0
        var halted: String? = null
        var scope: HaltScope? = null
        var resumed = 0

        override fun flatten() {
            flattened++
        }

        override fun halt(
            reason: String,
            scope: HaltScope,
        ) {
            if (halted == null || (this.scope == HaltScope.DAILY && scope == HaltScope.PERSISTENT)) {
                halted = reason
                this.scope = scope
            }
        }

        override fun resume() {
            resumed++
            halted = null
            scope = null
        }

        override fun isHalted(): Boolean = halted != null

        override fun haltReason(): String? = halted

        override fun haltScope(): HaltScope? = scope
    }

    protected fun bookRiskState(
        pnl: PnLProvider,
        clock: Clock,
        persist: ((PersistedRiskState) -> Unit)? = null,
    ): RiskState =
        RiskState(
            pnl,
            StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
            clock,
            EventBus(clock, MonotonicSequenceGenerator()),
            BigDecimal("100000"),
            DailyDrawdownBasis.BALANCE,
            persist,
        )
}
