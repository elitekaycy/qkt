package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * Read-only window into the risk subsystem from a single strategy's perspective.
 * Exposed to the DSL as `ACCOUNT.*` accessors and to the daemon's `qkt status` surface.
 *
 * All values are sampled live — no caching — so callers read consistent snapshots
 * via single-property accesses, not multiple correlated reads.
 */
interface RiskView {
    /** True if THIS strategy is currently halted (per-strategy or via global halt). */
    val halted: Boolean

    /** Operator-facing halt reason, or null when not halted. */
    val haltReason: String?

    /** Strategy's current equity (cash + open-position M2M), respecting the strategy's starting balance. */
    val currentEquity: BigDecimal

    /** Strategy's peak equity since session start — the high water mark used for drawdown calcs. */
    val equityPeak: BigDecimal

    /** Strategy's current drawdown as a positive `BigDecimal` (0 means at peak). */
    val drawdown: BigDecimal

    /** Strategy's realized P&L today (UTC day). Resets at the daily boundary. */
    val realizedToday: BigDecimal

    /** True if the global (account-level) halt is engaged — affects every strategy. */
    val globalHalted: Boolean

    /** Account-aggregate drawdown across all strategies. */
    val globalDrawdown: BigDecimal
}

/** Default [RiskView] backed by a live [RiskState] — used by the daemon and DSL bindings. */
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

    override val equityPeak: BigDecimal
        get() = riskState.equityTracker.peakEquityFor(strategyId)

    override val drawdown: BigDecimal
        get() = riskState.drawdownTracker.strategyDrawdown(strategyId)

    override val realizedToday: BigDecimal
        get() = riskState.dailyPnLTracker.realizedToday(strategyId)

    override val globalHalted: Boolean
        get() = riskState.halted

    override val globalDrawdown: BigDecimal
        get() = riskState.drawdownTracker.globalDrawdown()
}

/**
 * Inert [RiskView] returning zeros and `false`. Used by paper-only backtest paths and
 * tests where no risk state is wired — DSL `ACCOUNT.*` references still resolve.
 */
class NoOpRiskView : RiskView {
    override val halted: Boolean = false
    override val haltReason: String? = null
    override val currentEquity: BigDecimal = Money.ZERO
    override val equityPeak: BigDecimal = Money.ZERO
    override val drawdown: BigDecimal = Money.ZERO
    override val realizedToday: BigDecimal = Money.ZERO
    override val globalHalted: Boolean = false
    override val globalDrawdown: BigDecimal = Money.ZERO
}
