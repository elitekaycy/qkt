package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal

interface RiskView {
    val halted: Boolean

    val haltReason: String?

    val currentEquity: BigDecimal

    val drawdown: BigDecimal

    val realizedToday: BigDecimal

    val globalHalted: Boolean

    val globalDrawdown: BigDecimal
}

class RiskViewImpl(
    private val riskState: RiskState,
    private val strategyId: String,
) : RiskView {
    override val halted: Boolean
        get() = riskState.isStrategyHalted(strategyId)

    override val haltReason: String?
        get() = riskState.haltReasonFor(strategyId)

    override val currentEquity: BigDecimal
        get() = riskState.equityTracker.currentEquityFor(strategyId)

    override val drawdown: BigDecimal
        get() = riskState.drawdownTracker.strategyDrawdown(strategyId)

    override val realizedToday: BigDecimal
        get() = riskState.dailyPnLTracker.realizedToday(strategyId)

    override val globalHalted: Boolean
        get() = riskState.halted

    override val globalDrawdown: BigDecimal
        get() = riskState.drawdownTracker.globalDrawdown()
}

class NoOpRiskView : RiskView {
    override val halted: Boolean = false
    override val haltReason: String? = null
    override val currentEquity: BigDecimal = Money.ZERO
    override val drawdown: BigDecimal = Money.ZERO
    override val realizedToday: BigDecimal = Money.ZERO
    override val globalHalted: Boolean = false
    override val globalDrawdown: BigDecimal = Money.ZERO
}
