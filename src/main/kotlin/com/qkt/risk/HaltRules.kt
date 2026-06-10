package com.qkt.risk

import com.qkt.risk.rules.MaxDailyDrawdown
import com.qkt.risk.rules.MaxDailyLoss
import com.qkt.risk.rules.MaxDrawdown
import java.math.BigDecimal

/**
 * Builds the standard account-protection halt set from configuration. Live deploys
 * and backtests both construct through this one function so the same configured
 * limits stop trading at the same point in both modes — a strategy that would halt
 * live must halt in its backtest too, or the backtest overstates what live can earn.
 */
object HaltRules {
    fun standard(
        /** Daily realized-loss cap in account currency; zero disables. */
        maxDailyLoss: BigDecimal,
        /** Total drawdown halt as a fraction (0.10 = 10%); null disables. */
        maxDrawdownPct: BigDecimal? = null,
        /** Daily drawdown halt as a fraction; null disables. */
        maxDailyDrawdownPct: BigDecimal? = null,
        totalDdBasis: DrawdownBasis = DrawdownBasis.STATIC,
        /** Account basis for static total drawdown and the daily reference. */
        startingBalance: BigDecimal = BigDecimal.ZERO,
    ): List<HaltRule> =
        buildList {
            if (maxDailyLoss.signum() > 0) add(MaxDailyLoss(maxDailyLoss))
            maxDrawdownPct?.let { add(MaxDrawdown(it, totalDdBasis, startingBalance)) }
            maxDailyDrawdownPct?.let { add(MaxDailyDrawdown(it)) }
        }
}
