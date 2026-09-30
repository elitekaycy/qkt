package com.qkt.broker.exchange

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PaperBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SlippageModel
import com.qkt.broker.SubmitAck
import com.qkt.broker.ZeroSlippage
import com.qkt.broker.continuous.toContract
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.PriceAdjustment
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
import com.qkt.pnl.CommissionModel
import com.qkt.pnl.NoCommission
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * A paper exchange for dated futures contracts. Matching is [PaperBroker]'s, run on a private bus
 * whose events are relayed to [bus]; on top of it the simulator enforces the exchange's rules:
 *
 * - netting only;
 * - quantities are floored to `volumeStep` and refused below `volumeMin` (by the matching) or
 *   above `volumeMax`;
 * - limit and stop levels are snapped to the contract's tick grid in the direction that never fills
 *   early (buy limits down, sell limits up, buy stops up, sell stops down), as for continuous streams;
 * - a market order fills at the side's executable price and, like a triggered stop, then moves by
 *   [slippage] against the order; limit fills are not slipped;
 * - each fill carries its fee from [fees], on the contract's own price and in the contract's
 *   currency, as an [CostKind.EXCHANGE_FEE] venue cost — the way a live venue reports it;
 * - nothing is accepted on a contract at or after its expiry, and on the first tick at or after it
 *   the contract's working orders are cancelled and every net position is settled
 *   ([ExpirySettlement]).
 *
 * The simulator does not subscribe to ticks: its owner calls [onTick] after marking [prices].
 *
 * ```kotlin
 * val exchange = ExchangeSimulator(bus, clock, prices, instruments, InstrumentSlippage)
 * bus.subscribe<TickEvent> { exchange.onTick(it.tick) }
 * ```
 */
class ExchangeSimulator(
    private val bus: EventBus,
    private val clock: Clock,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry,
    private val slippage: SlippageModel = ZeroSlippage,
    private val fees: CommissionModel = NoCommission,
    fillAtTriggerPrice: Boolean = false,
    calendar: TradingCalendar = TradingCalendar.crypto(),
    settlements: SettlementLog = SettlementLog(),
) : Broker {
    private val venueBus = EventBus(clock, MonotonicSequenceGenerator())
    private val matching =
        PaperBroker(venueBus, clock, prices, instruments, fillAtTriggerPrice, calendar, PositionAccountingMode.NETTING)
    private val settlement = ExpirySettlement(bus, clock, prices, instruments, settlements)
    private val working = LinkedHashMap<String, OrderRequest>()
    private val expiringOrders = HashMap<String, String>()

    override val name: String = "ExchangeSim"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
        )

    override fun positionAccountingMode(symbol: String): PositionAccountingMode = PositionAccountingMode.NETTING

    init {
        venueBus.subscribe<BrokerEvent.OrderAccepted> { bus.publish(it) }
        venueBus.subscribe<BrokerEvent.OrderRejected> { e ->
            working.remove(e.clientOrderId)
            bus.publish(e)
        }
        venueBus.subscribe<BrokerEvent.OrderCancelled> { e ->
            working.remove(e.clientOrderId)
            bus.publish(expiringOrders.remove(e.clientOrderId)?.let { e.copy(reason = it) } ?: e)
        }
        venueBus.subscribe<BrokerEvent.OrderFilled> { e ->
            val priced = e.copy(price = executed(working.remove(e.clientOrderId), e))
            val fill = priced.copy(typedVenueCosts = exchangeFee(fees, instruments, priced))
            settlement.onFill(fill)
            bus.publish(fill)
        }
    }

    override fun submit(request: OrderRequest): SubmitAck {
        val meta =
            instruments.lookup(request.symbol) ?: return reject(request, "no instrument metadata for ${request.symbol}")
        val expiry =
            (meta.derivative as? FutureTerms)?.expiryMs
                ?: return reject(request, "${request.symbol} is not a dated futures contract")
        refusal(request, meta, expiry)?.let { return reject(request, it) }
        val grid = PriceSpace(PriceAdjustment.NONE, BigDecimal.ZERO, meta.pointSize)
        val onGrid = requireNotNull(toContract(request, request.id, request.symbol, grid))
        working[request.id] = onGrid
        settlement.track(request.symbol, expiry)
        return matching.submit(onGrid)
    }

    override fun cancel(orderId: String) = matching.cancel(orderId)

    /** Settle contracts that expired by [tick]'s time, then match [tick] unless its contract has expired. */
    fun onTick(tick: Tick) {
        settlement.settleDue(tick.timestamp, ::cancelWorkingOn)
        if (!settlement.isExpired(tick.symbol)) matching.onTick(tick)
    }

    private fun refusal(
        request: OrderRequest,
        meta: InstrumentMeta,
        expiryMs: Long,
    ): String? {
        if (clock.now() >= expiryMs) return "${request.symbol} expired at ${Instant.ofEpochMilli(expiryMs)}"
        if (request !is OrderRequest.Market &&
            request !is OrderRequest.Limit &&
            request !is OrderRequest.Stop &&
            request !is OrderRequest.StopLimit
        ) {
            return "$name does not accept ${request::class.simpleName} orders"
        }
        val max = meta.volumeMax ?: return null
        val floored = request.quantity.divide(meta.volumeStep, 0, RoundingMode.DOWN).multiply(meta.volumeStep)
        if (floored > max) {
            return "quantity ${request.quantity.toPlainString()} is above venue volumeMax ${max.toPlainString()} " +
                "for ${request.symbol}"
        }
        return null
    }

    private fun executed(
        request: OrderRequest?,
        fill: BrokerEvent.OrderFilled,
    ): BigDecimal {
        val meta = instruments.lookup(fill.symbol) ?: return fill.price
        val price =
            when (request) {
                is OrderRequest.Market ->
                    slippage.adjust(prices.executionPrice(fill.symbol, fill.side) ?: fill.price, fill.side, meta)
                is OrderRequest.Stop -> slippage.adjust(fill.price, fill.side, meta)
                else -> fill.price
            }
        return price.setScale(Money.SCALE, Money.ROUNDING)
    }

    private fun cancelWorkingOn(symbol: String) {
        val ids = working.values.filter { it.symbol == symbol }.map { it.id }
        for (id in ids) {
            expiringOrders[id] = "$symbol expired at ${Instant.ofEpochMilli(clock.now())}"
            matching.cancel(id)
        }
    }

    private fun reject(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }
}
