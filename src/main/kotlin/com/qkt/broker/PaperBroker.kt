package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TriggerType
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
import com.qkt.marketdata.buyExecPrice
import com.qkt.marketdata.sellExecPrice
import java.math.BigDecimal
import java.math.RoundingMode
import org.slf4j.LoggerFactory

/**
 * In-process simulator that books fills against the latest tracker price.
 *
 * The default broker for backtests, `qkt run`, and paper-trading deployments. Market
 * orders fill at the tracker's latest price; limits, stops, and if-touched orders are
 * parked in a working list and resolved as ticks print through.
 *
 * No slippage model, no rejection model, no latency — by design. Strategies that want
 * those should test against [com.qkt.broker.composite.CompositeBroker] with a simulated
 * latency layer or run against an actual venue.
 */
class PaperBroker(
    private val bus: EventBus,
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider,
    private val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    /**
     * Bar-research mode: fill a triggered Stop/Limit (or if-touched-market) at its own
     * price level instead of the price of the tick that triggered it.
     *
     * On real ticks the triggering print sits right at the level (dense ticks), so the
     * two agree and this stays off. But when a backtest replays bars, the only intrabar
     * prints are the synthetic O->L->H->C extremes: a long stop triggers on the bar's
     * low and a long take-profit on the bar's high, so filling "at the tick" books the
     * bar extreme — a phantom slippage that grows with bar range and contradicts this
     * broker's no-slippage design. e.g. stop 1.09, a bar dips to low 1.085: off -> fills
     * 1.085 (inflated loss); on -> fills 1.09. Wired on only for the `--bars` tier.
     */
    private val fillAtTriggerPrice: Boolean = false,
) : Broker {
    override fun positionAccountingMode(symbol: String): PositionAccountingMode = PositionAccountingMode.NETTING

    private val log = LoggerFactory.getLogger(PaperBroker::class.java)

    private val working: MutableList<OrderRequest> = mutableListOf()
    private val lastTickTimestampBySymbol: MutableMap<String, Long> = mutableMapOf()

    // Reused per-tick snapshot of the orders this tick triggers; cleared and refilled every call.
    // Shareable because the broker is single-threaded and fill callbacks never re-enter onTick.
    private val toFillScratch: MutableList<OrderRequest> = mutableListOf()
    private val gtdExpiredScratch: MutableList<OrderRequest> = mutableListOf()

    init {
        bus.subscribe<TickEvent> { e -> onTick(e.tick) }
    }

    override val name: String = "Paper"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            // PaperBroker trivially supports multi-leg — each working order has its own id.
            OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
        )

    override fun submit(request: OrderRequest): SubmitAck {
        val sized = sizeForVenue(request) ?: return rejectBelowMinimum(request)
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = sized.id,
                brokerOrderId = sized.id,
                strategyId = sized.strategyId,
                timestamp = clock.now(),
            ),
        )
        when (sized) {
            is OrderRequest.Market -> fillMarket(sized)
            is OrderRequest.Limit, is OrderRequest.Stop,
            is OrderRequest.StopLimit, is OrderRequest.IfTouched,
            ->
                working.add(sized)
            else -> error("PaperBroker received unexpected order type: ${sized::class.simpleName}")
        }
        return SubmitAck(
            clientOrderId = sized.id,
            brokerOrderId = sized.id,
            accepted = true,
        )
    }

    private fun sizeForVenue(request: OrderRequest): OrderRequest? {
        val meta = instruments.lookup(request.symbol) ?: return request
        val quantity =
            request.quantity
                .divide(meta.volumeStep, 0, RoundingMode.DOWN)
                .multiply(meta.volumeStep)
        return if (quantity.signum() == 0 || quantity < meta.volumeMin) null else request.withQuantity(quantity)
    }

    private fun rejectBelowMinimum(request: OrderRequest): SubmitAck {
        val meta = requireNotNull(instruments.lookup(request.symbol))
        val quantized =
            request.quantity
                .divide(meta.volumeStep, 0, RoundingMode.DOWN)
                .multiply(meta.volumeStep)
        val reason =
            "quantized volume $quantized below venue volumeMin ${meta.volumeMin} for ${request.symbol}"
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = false,
            rejectReason = reason,
        )
    }

    override fun cancel(orderId: String) {
        val match = working.firstOrNull { it.id == orderId }
        val removed = working.removeAll { it.id == orderId }
        if (removed) {
            bus.publish(
                BrokerEvent.OrderCancelled(
                    clientOrderId = orderId,
                    brokerOrderId = orderId,
                    reason = "user cancel",
                    strategyId = match?.strategyId ?: "",
                    timestamp = clock.now(),
                ),
            )
        }
    }

    fun onTick(tick: Tick) {
        val previousTimestamp = lastTickTimestampBySymbol.put(tick.symbol, tick.timestamp)
        if (working.isEmpty()) return
        expireGtd(tick)
        if (working.isEmpty()) return
        // BarTickFeed closes a bar at endTime-1 and opens the next contiguous bar at endTime.
        // A larger timestamp jump therefore identifies a session/data gap. Stops that gap
        // through their level fill at the adverse opening print, not optimistically at the level.
        val isGapOpen =
            fillAtTriggerPrice &&
                previousTimestamp != null &&
                tick.timestamp > previousTimestamp + 1
        toFillScratch.clear()
        for (i in working.indices) {
            val req = working[i]
            if (req.symbol == tick.symbol && checkTrigger(req, tick)) toFillScratch.add(req)
        }
        for (i in toFillScratch.indices) {
            val wo = toFillScratch[i]
            // A synchronous fill callback may cancel an OCO sibling that is also present in
            // this tick's trigger snapshot. Never fill an order that is no longer working.
            if (!working.remove(wo)) continue
            fillFromTrigger(wo, tick, isGapOpen)
        }
    }

    private fun expireGtd(tick: Tick) {
        gtdExpiredScratch.clear()
        for (i in working.indices) {
            val request = working[i]
            if (request.symbol != tick.symbol) continue
            val expiresAt = request.expiresAt ?: continue
            if (tick.timestamp >= expiresAt) gtdExpiredScratch.add(request)
        }
        for (i in gtdExpiredScratch.indices) {
            val request = gtdExpiredScratch[i]
            if (!working.remove(request)) continue
            bus.publish(
                BrokerEvent.OrderCancelled(
                    clientOrderId = request.id,
                    brokerOrderId = request.id,
                    reason = "GTD expired",
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
    }

    private fun fillMarket(req: OrderRequest.Market) {
        val px = priceProvider.lastPrice(req.symbol)
        if (px == null) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = req.id,
                    brokerOrderId = req.id,
                    reason = "no price",
                    strategyId = req.strategyId,
                    timestamp = clock.now(),
                ),
            )
            return
        }
        publishFill(req.id, req.symbol, req.side, px, req.quantity, req.strategyId)
    }

    private fun fillFromTrigger(
        req: OrderRequest,
        tick: Tick,
        isGapOpen: Boolean,
    ) {
        if (req is OrderRequest.StopLimit) {
            activateLimit(
                OrderRequest.Limit(
                    id = req.id,
                    symbol = req.symbol,
                    side = req.side,
                    quantity = req.quantity,
                    limitPrice = req.limitPrice,
                    timeInForce = req.timeInForce,
                    timestamp = req.timestamp,
                    strategyId = req.strategyId,
                    expiresAt = req.expiresAt,
                ),
                tick,
            )
            return
        }
        if (req is OrderRequest.IfTouched && req.onTrigger == TriggerType.LIMIT) {
            activateLimit(
                OrderRequest.Limit(
                    id = req.id,
                    symbol = req.symbol,
                    side = req.side,
                    quantity = req.quantity,
                    limitPrice = requireNotNull(req.limitPrice),
                    timeInForce = req.timeInForce,
                    timestamp = req.timestamp,
                    strategyId = req.strategyId,
                    expiresAt = req.expiresAt,
                ),
                tick,
            )
            return
        }
        val tickPrice = tick.price
        val (fillPrice, side, qty) =
            when (req) {
                is OrderRequest.Limit ->
                    Triple(if (fillAtTriggerPrice) req.limitPrice else tickPrice, req.side, req.quantity)
                is OrderRequest.Stop ->
                    Triple(
                        if (fillAtTriggerPrice && !isGapOpen) req.stopPrice else tickPrice,
                        req.side,
                        req.quantity,
                    )
                is OrderRequest.IfTouched ->
                    Triple(if (fillAtTriggerPrice) req.triggerPrice else tickPrice, req.side, req.quantity)
                is OrderRequest.Market -> error("Market should not reach fillFromTrigger")
                is OrderRequest.StopLimit -> error("StopLimit should activate a Limit")
                else -> error("PaperBroker fillFromTrigger received unexpected type: ${req::class.simpleName}")
            }
        publishFill(req.id, req.symbol, side, fillPrice, qty, req.strategyId)
    }

    private fun activateLimit(
        limit: OrderRequest.Limit,
        tick: Tick,
    ) {
        if (checkTrigger(limit, tick)) {
            val execution = if (limit.side == Side.BUY) tick.buyExecPrice() else tick.sellExecPrice()
            val fillPrice =
                if (fillAtTriggerPrice) {
                    limit.limitPrice
                } else if (limit.side == Side.BUY) {
                    execution.min(limit.limitPrice)
                } else {
                    execution.max(limit.limitPrice)
                }
            publishFill(
                limit.id,
                limit.symbol,
                limit.side,
                fillPrice,
                limit.quantity,
                limit.strategyId,
            )
        } else {
            working.add(limit)
        }
    }

    private fun publishFill(
        clientOrderId: String,
        symbol: String,
        side: Side,
        price: BigDecimal,
        qty: BigDecimal,
        strategyId: String,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = clientOrderId,
                brokerOrderId = clientOrderId,
                symbol = symbol,
                side = side,
                price = price.setScale(Money.SCALE, Money.ROUNDING),
                quantity = qty,
                strategyId = strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    // Side-aware like the venue: a BUY executes at the ask, a SELL at the bid, so each
    // side's trigger compares against ITS execution price (mid only when the feed has
    // no quote depth). See com.qkt.marketdata.buyExecPrice.
    private fun checkTrigger(
        req: OrderRequest,
        tick: Tick,
    ): Boolean {
        val exec =
            if (req.side == Side.BUY) tick.buyExecPrice() else tick.sellExecPrice()
        return when (req) {
            is OrderRequest.Limit ->
                if (req.side == Side.BUY) exec <= req.limitPrice else exec >= req.limitPrice
            is OrderRequest.Stop ->
                if (req.side == Side.BUY) exec >= req.stopPrice else exec <= req.stopPrice
            is OrderRequest.StopLimit ->
                if (req.side == Side.BUY) exec >= req.stopPrice else exec <= req.stopPrice
            is OrderRequest.IfTouched ->
                if (req.side == Side.BUY) exec <= req.triggerPrice else exec >= req.triggerPrice
            is OrderRequest.Market -> false
            else -> false
        }
    }

    private fun OrderRequest.withQuantity(quantity: BigDecimal): OrderRequest =
        when (this) {
            is OrderRequest.Market -> copy(quantity = quantity)
            is OrderRequest.Limit -> copy(quantity = quantity)
            is OrderRequest.Stop -> copy(quantity = quantity)
            is OrderRequest.StopLimit -> copy(quantity = quantity)
            is OrderRequest.IfTouched -> copy(quantity = quantity)
            else -> error("PaperBroker received unexpected order type: ${this::class.simpleName}")
        }
}
