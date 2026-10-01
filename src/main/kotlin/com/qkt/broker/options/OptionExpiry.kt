package com.qkt.broker.options

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.broker.exchange.Settlement
import com.qkt.broker.exchange.SettlementLog
import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionRight
import com.qkt.instrument.OptionTerms
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Cash settlement of expired option positions: every net position of a contract whose expiry has
 * passed is closed at its intrinsic value from the catalog's delivery price (`max(S − K, 0)` for a
 * call, `max(K − S, 0)` for a put), less the capped [OptionFee.delivery], with exit reason `EXPIRY`,
 * and recorded in the run's [SettlementLog]. A contract that expired without a recorded delivery
 * price fails the run: settling at a guessed price would book a fictitious P&L.
 */
internal class OptionExpiry(
    private val bus: EventBus,
    private val instruments: InstrumentRegistry,
    private val positions: OptionPositions,
    private val settlements: SettlementLog,
) {
    /** The contracts with positions that have expired by [atMs]. */
    fun expired(atMs: Long): List<String> = positions.symbols().filter { atMs >= expiryOf(it) }

    /** Settles every position of [symbol] at [atMs]. */
    fun settle(
        symbol: String,
        atMs: Long,
    ) {
        val terms =
            requireNotNull(instruments.lookup(symbol)?.derivative as? OptionTerms) { "$symbol has no option terms" }
        val options = requireNotNull(instruments.options())
        val root = requireNotNull(options.optionRoot(symbol)) { "$symbol is not a catalogued option" }
        val day = Instant.ofEpochMilli(terms.expiryMs).atZone(ZoneOffset.UTC).toLocalDate()
        val delivery =
            options.deliveryPrice(symbol)
                ?: error(
                    "$symbol expired with no delivery price for $day in the catalog; refresh it with qkt fetch ${root.root} --catalog",
                )
        val intrinsic =
            when (terms.right) {
                OptionRight.CALL -> delivery.subtract(terms.strike)
                OptionRight.PUT -> terms.strike.subtract(delivery)
            }.max(BigDecimal.ZERO).setScale(Money.SCALE, Money.ROUNDING)
        for ((strategyId, quantity) in positions.holdersOf(symbol)) {
            val side = if (quantity.signum() > 0) Side.SELL else Side.BUY
            val fee = OptionFee.delivery(root, quantity, intrinsic, delivery)
            val costs =
                if (fee.signum() ==
                    0
                ) {
                    emptyList()
                } else {
                    listOf(VenueCost(CostKind.EXCHANGE_FEE, MoneyAmount(fee, root.currency), atMs))
                }
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = "expiry:$symbol:$strategyId",
                    brokerOrderId = null,
                    symbol = symbol,
                    side = side,
                    price = intrinsic,
                    quantity = quantity.abs(),
                    strategyId = strategyId,
                    timestamp = atMs,
                    updatesOrderExecution = false,
                    typedVenueCosts = costs,
                    exitReason = ExitReason.EXPIRY,
                ),
            )
            settlements.record(
                Settlement(atMs, strategyId, symbol, side, quantity.abs(), intrinsic, deliveryPriceKnown = true),
            )
        }
        positions.clear(symbol)
    }

    private fun expiryOf(symbol: String): Long =
        requireNotNull(
            instruments.lookup(symbol)?.derivative as? OptionTerms,
        ) { "$symbol has no option terms" }.expiryMs
}
