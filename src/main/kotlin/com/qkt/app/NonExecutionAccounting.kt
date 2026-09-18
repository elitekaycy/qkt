package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.risk.RiskState
import java.math.BigDecimal

/**
 * Publishes realized amounts that no execution produced — a financing accrual (swap) or what the
 * venue realized on a leg that closed while the daemon was down — as a [FillAccountedEvent], so
 * they pass through the same fold, accumulators and audit trail as an execution.
 */
internal class NonExecutionAccounting(
    private val riskState: RiskState,
    private val bus: EventBus,
    private val accounting: AccountingEngine,
    private val clock: Clock,
) {
    /** Publish [amount] for [strategyId] as a non-execution accounted event of [kind]. */
    fun publish(
        strategyId: String,
        amount: BigDecimal,
        kind: FillAccountingKind,
        id: String,
        legId: String? = null,
    ) {
        riskState.beforeFill(strategyId)
        val scaled = amount.setScale(Money.SCALE, Money.ROUNDING)
        bus.publish(
            FillAccountedEvent(
                orderId = id,
                strategyId = strategyId,
                symbol = "",
                fillSliceId = id,
                sourceFillSequenceId = 0L,
                cumulativeFilled = null,
                modeledCommissionAccount = Money.ZERO,
                venueCostsAccount = Money.ZERO,
                totalCostsAccount = Money.ZERO,
                accountNativeRealized = scaled,
                strategyNativeRealized = scaled,
                nativeCurrency = accounting.accountCurrency,
                grossAccountRealized = scaled,
                grossStrategyAccountRealized = scaled,
                accountCurrency = accounting.accountCurrency,
                netAccountRealized = scaled,
                netStrategyAccountRealized = scaled,
                conversionRate = null,
                conversionTimestampMs = null,
                conversionSource = null,
                contractSize = null,
                accountPositionBefore = null,
                accountPositionAfter = null,
                strategyPositionBefore = null,
                strategyPositionAfter = null,
                reducedExposure = false,
                legId = legId,
                legAction = null,
                partial = false,
                kind = kind,
                executedAt = clock.now(),
            ),
        )
    }
}
