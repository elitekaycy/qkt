package com.qkt.broker.options

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.broker.exchange.SettlementLog
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.ChainQuoteLookup
import com.qkt.derivatives.options.chain.OptionQuotes
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant

/**
 * A paper venue for option contracts that trades on the stored chain, never on the snapshot an order
 * was decided on:
 *
 * - a market order fills at the first quote of its contract strictly after it was submitted, a buy at
 *   the ask and a sell at the bid ([OptionQuotes]); a quote without that side cancels it, and so does
 *   no quote of the contract within the root's `maxQuoteAgeMinutes`;
 * - a limit order ([OptionOrderEntry] snaps it so it never fills early) fills at its limit on a later
 *   quote whose side reaches it; IOC and FOK get one look, GTD and DAY orders lapse at their time;
 * - each fill carries its [OptionFee] as an [CostKind.EXCHANGE_FEE] cost in the root's currency;
 * - positions are netted and long only: a sell beyond the held quantity is refused;
 * - at a contract's expiry its working orders lapse and its positions are cash-settled ([OptionExpiry]).
 *
 * Like the futures exchange it does not subscribe to ticks: its owner calls [onTick].
 */
class OptionExchange(
    private val bus: EventBus,
    private val clock: Clock,
    private val instruments: InstrumentRegistry,
    private val quotes: ChainQuoteLookup,
    calendar: TradingCalendar = TradingCalendar.crypto(),
    settlements: SettlementLog = SettlementLog(),
) : Broker {
    override val name: String = "OptionSim"
    override val capabilities: Set<OrderTypeCapability> = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT)
    private val positions = OptionPositions()
    private val entry = OptionOrderEntry(instruments, clock, calendar, positions)
    private val working = LinkedHashMap<String, WorkingOption>()
    private val expiry = OptionExpiry(bus, instruments, positions, settlements)

    override fun positionAccountingMode(symbol: String): PositionAccountingMode = PositionAccountingMode.NETTING

    override fun supports(symbol: String): Boolean = instruments.options()?.optionRoot(symbol) != null

    override fun submit(request: OrderRequest): SubmitAck {
        val accepted =
            when (val checked = entry.check(request, working.values)) {
                is OptionOrderEntry.Checked.Refused -> return reject(request, checked.reason)
                is OptionOrderEntry.Checked.Accepted -> checked.order
            }
        working[request.id] = accepted
        bus.publish(BrokerEvent.OrderAccepted(request.id, request.id, request.strategyId, clock.now()))
        return SubmitAck(clientOrderId = request.id, brokerOrderId = request.id, accepted = true)
    }

    override fun cancel(orderId: String) {
        working[orderId]?.let { cancelWorking(it, "cancelled") }
    }

    /**
     * Against [tick]'s instant: lapses orders on contracts that have expired, settles expired positions
     * ([OptionExpiry]), then fills, cancels or lapses the remaining working orders.
     */
    fun onTick(tick: Tick) {
        val at = tick.timestamp
        for (order in working.values.filter { at >= it.contractExpiryMs }) {
            cancelWorking(order, "${order.request.symbol} expired at ${Instant.ofEpochMilli(order.contractExpiryMs)}")
        }
        for (symbol in expiry.expired(at)) expiry.settle(symbol, at)
        for (order in working.values.toList()) {
            if (order.request.id !in working) continue
            val symbol = order.request.symbol
            val lapse = order.expiresAt
            when {
                lapse != null && at >= lapse -> cancelWorking(order, "expired at ${Instant.ofEpochMilli(lapse)}")
                tick.symbol == symbol && at > order.submittedAt -> match(order, at)
                order.request is OrderRequest.Market &&
                    at >= order.submittedAt + order.root.maxQuoteAgeMinutes * MS_PER_MINUTE ->
                    cancelWorking(order, "no quote of $symbol within ${order.root.maxQuoteAgeMinutes} minutes")
            }
        }
    }

    private fun match(
        order: WorkingOption,
        at: Long,
    ) {
        val request = order.request
        val quote = quotes.quoteAt(request.symbol, at)
        val sides = quote?.let { OptionQuotes.sides(it, order.root) }
        val price = if (request.side == Side.BUY) sides?.ask else sides?.bid
        val sideName = if (request.side == Side.BUY) "ask" else "bid"
        when (request) {
            is OrderRequest.Market ->
                if (quote == null || price == null) {
                    cancelWorking(order, "no $sideName in the ${request.symbol} quote at ${Instant.ofEpochMilli(at)}")
                } else {
                    fill(order, price, quote, at)
                }
            is OrderRequest.Limit -> {
                val reached =
                    price != null &&
                        if (request.side == Side.BUY) price <= request.limitPrice else price >= request.limitPrice
                if (quote != null && reached) {
                    fill(order, request.limitPrice, quote, at)
                } else if (request.timeInForce == TimeInForce.IOC || request.timeInForce == TimeInForce.FOK) {
                    cancelWorking(order, "not filled at the next ${request.symbol} quote")
                }
            }
            else -> cancelWorking(order, "the option venue takes market and limit orders")
        }
    }

    private fun fill(
        order: WorkingOption,
        price: BigDecimal,
        quote: ChainQuote,
        at: Long,
    ) {
        val request = order.request
        working.remove(request.id)
        val fee = OptionFee.trade(order.root, request.quantity, price, quote.underlying)
        val costs =
            if (fee.signum() ==
                0
            ) {
                emptyList()
            } else {
                listOf(VenueCost(CostKind.EXCHANGE_FEE, MoneyAmount(fee, order.root.currency), at))
            }
        positions.apply(request.strategyId, request.symbol, request.side, request.quantity)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                symbol = request.symbol,
                side = request.side,
                price = price.setScale(Money.SCALE, Money.ROUNDING),
                quantity = request.quantity,
                strategyId = request.strategyId,
                timestamp = at,
                typedVenueCosts = costs,
            ),
        )
    }

    private fun cancelWorking(
        order: WorkingOption,
        reason: String,
    ) {
        working.remove(order.request.id)
        bus.publish(
            BrokerEvent.OrderCancelled(
                order.request.id,
                order.request.id,
                reason,
                order.request.strategyId,
                clock.now(),
            ),
        )
    }

    private fun reject(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        bus.publish(BrokerEvent.OrderRejected(request.id, null, reason, request.strategyId, clock.now()))
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
