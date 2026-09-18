package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.ConvertedMoney
import com.qkt.accounting.VenueCost
import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.instrument.InstrumentRegistry
import com.qkt.pnl.CommissionBook
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskState
import java.math.BigDecimal

/** An execution booked into the ledger and priced, ready to publish and report. */
internal class AccountedExecution(
    val event: FillAccountedEvent,
    val converted: ConvertedMoney,
    val application: StrategyPositionTracker.FillApplication,
    val grossStrategyAccountRealized: BigDecimal,
)

/**
 * Books one execution slice into the strategy position ledger and prices it into a
 * [FillAccountedEvent]: contract size, account-currency conversion, modeled commission and
 * venue costs. It computes the accounted amounts; [AccountedFillFold] is what applies them.
 */
internal class ExecutionBooker(
    private val riskState: RiskState,
    private val positions: PositionProvider,
    private val strategyPositions: StrategyPositionTracker,
    private val instruments: InstrumentRegistry,
    private val commissionBook: CommissionBook,
    private val accounting: AccountingEngine,
    orderManager: OrderManager,
    positionMode: (symbol: String) -> PositionAccountingMode,
) {
    /**
     * Recovers each execution's leg intent — from the order it names, else from the venue
     * ticket an owned leg already carries, else the venue's accounting default — and hands it
     * to the ledger. The order is the only routing authority; nothing is registered ahead of
     * a fill and nothing is forgotten on cancel.
     */
    private val legIntentResolver =
        LegIntentResolver(
            orderFor = { clientOrderId -> orderManager.getOrder(clientOrderId)?.request },
            legByTicket = { strategyId, symbol, ticket ->
                strategyPositions.legBookFor(strategyId, symbol)?.legByTicket(ticket)
            },
            positionMode = positionMode,
        )

    /**
     * Book one execution slice into the ledger and price it. Returns null when the ledger booked
     * nothing (a replayed or unattributable slice) — then nothing is accounted or published.
     */
    fun book(
        e: BrokerEvent.OrderFilled,
        cumulativeFilled: BigDecimal?,
        partial: Boolean,
    ): AccountedExecution? {
        riskState.beforeFill(e.strategyId)
        val accountBefore = positions.positionFor(e.symbol)
        val strategyBefore = strategyPositions.positionFor(e.strategyId, e.symbol)
        val application =
            strategyPositions.applyFillDetailed(e, legIntentResolver.resolve(e).intent, cumulativeFilled)
        if (application.unbooked) return null
        val contractSize = instruments.lookup(e.symbol)?.contractSize
        val cs = contractSize ?: BigDecimal.ONE
        // Commission is a per-fill cash charge (#335); venue-reported costs (MT5 deal
        // commission/swap, Bybit execFee) net out the same way — equity and halt inputs must be
        // cost-true, or a strategy bleeding costs looks healthier than it is.
        val commission = commissionBook.charge(e.strategyId, e.symbol, e.quantity)
        val venueCosts =
            if (e.typedVenueCosts.isNotEmpty()) {
                typedVenueCostAmount(e.typedVenueCosts, e.symbol, e.timestamp, e.price)
            } else {
                e.venueCosts
            }
        val costs = commission.add(venueCosts)
        // One ledger, one realized figure: the account amount is the strategy amount.
        val native = application.realized.multiply(cs)
        val converted =
            accounting.convertPnl(
                symbol = e.symbol,
                nativeAmount = native,
                timestamp = e.timestamp,
                referencePrice = e.price,
            )
        val gross = converted.account.amount
        val net = gross.subtract(costs)
        val strategyAfter = strategyPositions.positionFor(e.strategyId, e.symbol)
        val reducedExposure = closesExposure(strategyBefore, strategyAfter)
        val event =
            FillAccountedEvent(
                orderId = e.clientOrderId,
                strategyId = e.strategyId,
                symbol = e.symbol,
                fillSliceId = "${e.clientOrderId}:${e.sequenceId}",
                sourceFillSequenceId = e.sequenceId,
                cumulativeFilled = if (partial) cumulativeFilled else null,
                modeledCommissionAccount = commission,
                venueCostsAccount = venueCosts,
                totalCostsAccount = costs,
                accountNativeRealized = native,
                strategyNativeRealized = native,
                nativeCurrency = converted.native.normalizedCurrency,
                grossAccountRealized = gross,
                grossStrategyAccountRealized = gross,
                accountCurrency = converted.account.normalizedCurrency,
                netAccountRealized = net,
                netStrategyAccountRealized = net,
                conversionRate = converted.conversion?.rate,
                conversionTimestampMs = converted.conversion?.timestamp,
                conversionSource = converted.conversion?.source,
                contractSize = contractSize,
                accountPositionBefore = accountBefore,
                accountPositionAfter = positions.positionFor(e.symbol),
                strategyPositionBefore = strategyBefore,
                strategyPositionAfter = strategyAfter,
                reducedExposure = reducedExposure,
                legId = application.legId,
                legAction = application.legAction,
                partial = partial,
                kind = FillAccountingKind.EXECUTION,
                executedAt = e.timestamp,
            )
        return AccountedExecution(event, converted, application, gross)
    }

    private fun typedVenueCostAmount(
        costs: List<VenueCost>,
        symbol: String,
        timestamp: Long,
        referencePrice: BigDecimal,
    ): BigDecimal {
        if (costs.isEmpty()) return Money.ZERO
        return costs
            .fold(Money.ZERO) { acc, cost ->
                val stamped = if (cost.timestamp == 0L) cost.copy(timestamp = timestamp) else cost
                acc.add(
                    accounting
                        .convertCost(stamped, contextSymbol = symbol, referencePrice = referencePrice)
                        .account.amount,
                )
            }.setScale(Money.SCALE, Money.ROUNDING)
    }
}

private fun closesExposure(
    before: Position?,
    after: Position?,
): Boolean {
    val beforeQty = before?.quantity ?: BigDecimal.ZERO
    val afterQty = after?.quantity ?: BigDecimal.ZERO
    val flipped = beforeQty.signum() != 0 && afterQty.signum() != 0 && beforeQty.signum() != afterQty.signum()
    return beforeQty.abs() > BigDecimal.ZERO && (afterQty.abs() < beforeQty.abs() || flipped)
}
