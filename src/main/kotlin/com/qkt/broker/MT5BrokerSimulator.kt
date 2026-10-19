package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TriggerType
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.RoundingMode
import org.slf4j.LoggerFactory

/**
 * Fidelity-oriented backtest broker that mirrors MT5 venue behaviour (`--broker mt5-sim`; [PaperBroker] is the
 * default): volume rounded down to [InstrumentMeta.volumeStep] and refused below [InstrumentMeta.volumeMin],
 * prices rounded to [InstrumentMeta.digits], market BUY at the ask and SELL at the bid (synthesised as
 * `price ± pointSize × spreadPoints / 2` when the feed carries only mid), [SlippageModel] slippage,
 * `tradeStopsLevel` distances, and the venue's refusal of a limit on the wrong side of the market
 * ([mt5LimitSideRefusal]). See `docs/parity/backtest-vs-live.md`.
 *
 * Single-threaded by design: `working`, `lastTickBySymbol` and [UniformRandomSlippage] state are not synchronised,
 * because the simulator is only wired through [Backtest], whose tick loop and order submission run on one thread.
 */
class MT5BrokerSimulator(
    private val bus: EventBus,
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider,
    private val instruments: InstrumentRegistry,
    private val slippage: SlippageModel = ZeroSlippage,
    private val syntheticSpreadPoints: Int = 2,
    latencyMs: Long = 0L,
    /**
     * Delay between a protective stop's trigger and its execution (#1135). The venue
     * runs a crossed stop as a market order a beat later, so it fills at the first quote
     * at or after `trigger + stopLatencyMs`, not the crossing print. Measured on the
     * Exness demo: median ~260 ms; 0 keeps the on-trigger fill.
     */
    private val stopLatencyMs: Long = 0L,
    /** Pricing of a gap-crossed protective take-profit; see [TakeProfitFill]. */
    private val takeProfitFill: TakeProfitFill = TakeProfitFill.PRINT,
    private val enforceStopsLevel: Boolean = false,
    private val rejectionModel: RejectionModel = NoBrokerRejections,
    private val partialFillModel: PartialFillModel = FullFill,
    /**
     * Position model this venue simulates (#1071). Default HEDGING matches real retail
     * MT5 (and this simulator's MULTI_POSITION_PER_SYMBOL capability); NETTING remains
     * for netted MT5 accounts.
     */
    private val positionMode: PositionAccountingMode = PositionAccountingMode.HEDGING,
    /** Minimum gap between consecutive order releases on the venue's send lane; see [SendLane]. */
    orderSpacingMs: Long = 0L,
    /** Symbols whose current tick is a bar's synthetic extreme: a level it trades through fills there (#1439). */
    private val fillAtTriggerPrice: (String) -> Boolean = { false },
) : Broker {
    override fun positionAccountingMode(symbol: String): PositionAccountingMode = positionMode

    init {
        require(syntheticSpreadPoints >= 0) {
            "syntheticSpreadPoints must be >= 0: $syntheticSpreadPoints"
        }
        require(stopLatencyMs >= 0L) { "stopLatencyMs must be >= 0: $stopLatencyMs" }
    }

    private val sendLane = SendLane(latencyMs, orderSpacingMs)

    internal val fillPrice = SimulatedFillPrice(syntheticSpreadPoints)

    private val log = LoggerFactory.getLogger(MT5BrokerSimulator::class.java)

    private val working: MutableList<OrderRequest> = mutableListOf()

    // Reused per-tick snapshot of the orders this tick triggers; cleared and refilled every call.
    // Shareable because the simulator is single-threaded and fill callbacks never re-enter onTick.
    private val toFillScratch: MutableList<OrderRequest> = mutableListOf()
    private val gtdExpiredScratch: MutableList<OrderRequest> = mutableListOf()
    private val delayedSubmissions: MutableList<DelayedSubmission> = mutableListOf()
    private val pendingStopFills: MutableList<PendingStopFill> = mutableListOf()
    private val lastTickBySymbol: MutableMap<String, Tick> = HashMap()
    private var submittedOrdinal: Int = 0

    init {
        bus.subscribe<TickEvent> { e -> onTick(e.tick) }
    }

    override val name: String = "MT5-Sim"

    override val capabilities: Set<OrderTypeCapability> = MT5_SIM_CAPABILITIES

    override fun submit(request: OrderRequest): SubmitAck {
        submittedOrdinal += 1
        val ordinal = submittedOrdinal
        val releaseAt = sendLane.releaseAt(clock.now())
        if (releaseAt > clock.now()) {
            delayedSubmissions.add(DelayedSubmission(request = request, ordinal = ordinal, releaseAt = releaseAt))
            return SubmitAck(request.id, request.id, accepted = true)
        }
        return receive(request, ordinal)
    }

    private fun receive(
        request: OrderRequest,
        ordinal: Int,
    ): SubmitAck {
        val meta = instruments.lookup(request.symbol)
        if (meta == null) {
            reject(request, "no InstrumentMeta for symbol ${request.symbol}")
            return SubmitAck(request.id, request.id, accepted = false)
        }
        val quantized = quantizeVolume(request.quantity, meta)
        if (quantized.signum() == 0 || quantized < meta.volumeMin) {
            reject(
                request,
                "quantized volume $quantized below venue volumeMin ${meta.volumeMin} for ${request.symbol}",
            )
            return SubmitAck(request.id, request.id, accepted = false)
        }
        if (meta.volumeMax != null && quantized > meta.volumeMax) {
            val reason =
                "quantized volume $quantized above venue volumeMax ${meta.volumeMax} for ${request.symbol}"
            reject(request, reason)
            return SubmitAck(request.id, request.id, accepted = false, rejectReason = reason)
        }
        val sized = request.withQuantity(quantized)
        (validateStopsLevel(sized, meta) ?: limitSideRefusal(sized, meta))?.let {
            reject(sized, it)
            return SubmitAck(sized.id, sized.id, accepted = false, rejectReason = it)
        }
        rejectionModel.rejectionReason(sized, ordinal)?.let {
            reject(sized, it)
            return SubmitAck(sized.id, sized.id, accepted = false, rejectReason = it)
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = sized.id,
                brokerOrderId = sized.id,
                strategyId = sized.strategyId,
                timestamp = clock.now(),
            ),
        )
        when (sized) {
            is OrderRequest.Market -> fillMarket(sized, meta)
            is OrderRequest.Limit, is OrderRequest.Stop,
            is OrderRequest.StopLimit, is OrderRequest.IfTouched,
            -> working.add(sized)
            else -> error("MT5BrokerSimulator received unexpected order type: ${sized::class.simpleName}")
        }
        return SubmitAck(sized.id, sized.id, accepted = true)
    }

    override fun cancel(orderId: String) {
        val match =
            working.firstOrNull { it.id == orderId }
                ?: pendingStopFills.firstOrNull { it.request.id == orderId }?.request
        val removedWorking = working.removeAll { it.id == orderId }
        val removedPending = pendingStopFills.removeAll { it.request.id == orderId }
        val removed = removedWorking || removedPending
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
        // Releases before this tick fill at the quote prevailing then, still the previous one.
        drainDelayedSubmissions(clock.now() - 1)
        val previous = lastTickBySymbol.put(tick.symbol, tick)
        drainDelayedSubmissions(clock.now())
        drainPendingStopFills(tick)
        if (working.isEmpty()) return
        expireGtd(tick)
        if (working.isEmpty()) return
        // As PaperBroker: a synthetic bar Close (the only synthetic tick with volume) to the next Open is a gap.
        val from = previous?.takeIf { fillAtTriggerPrice(tick.symbol) && it.volume == null }?.price
        toFillScratch.clear()
        for (i in working.indices) {
            val req = working[i]
            if (req.symbol == tick.symbol && mt5Triggered(req, tick)) toFillScratch.add(req)
        }
        for (i in toFillScratch.indices) {
            val wo = toFillScratch[i]
            // A synchronous fill callback may cancel an OCO sibling that is also present in
            // this tick's trigger snapshot. Never fill an order that is no longer working.
            if (!working.remove(wo)) continue
            if (stopLatencyMs > 0L && isProtectiveStop(wo)) {
                pendingStopFills.add(PendingStopFill(wo, fillAt = clock.now() + stopLatencyMs))
            } else {
                fillFromTrigger(wo, tick, from)
            }
        }
    }

    /** A crossed protective stop executes at the first quote at or after its delay (#1135). */
    private fun drainPendingStopFills(tick: Tick) {
        if (pendingStopFills.isEmpty()) return
        val now = clock.now()
        val due = pendingStopFills.filter { it.request.symbol == tick.symbol && it.fillAt <= now }
        if (due.isEmpty()) return
        pendingStopFills.removeAll(due.toSet())
        for (pending in due) fillFromTrigger(pending.request, tick)
    }

    /** A stop whose job is to close a leg: bracket and stop-loss legs, not stop entries. */
    private fun isProtectiveStop(req: OrderRequest): Boolean =
        req.legIntent is LegIntent.Close &&
            (req is OrderRequest.Stop || (req is OrderRequest.IfTouched && req.onTrigger == TriggerType.MARKET))

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

    private fun fillMarket(
        req: OrderRequest.Market,
        meta: InstrumentMeta,
    ) {
        val tick = lastTickBySymbol[req.symbol]
        val fairFill = fillPrice.of(req.side, tick, fallback = priceProvider.lastPrice(req.symbol), meta)
        if (fairFill == null) {
            reject(req, "no price for ${req.symbol}")
            return
        }
        val withSlip = slippage.adjust(fairFill, req.side, meta)
        publishFill(req.id, req.symbol, req.side, withSlip, req.quantity, req.strategyId, meta)
    }

    private fun fillFromTrigger(
        req: OrderRequest,
        triggeringTick: Tick,
        from: BigDecimal? = null,
    ) {
        val meta =
            instruments.lookup(req.symbol)
                ?: run {
                    reject(req, "no InstrumentMeta for symbol ${req.symbol} at fill time")
                    return
                }
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
                    legIntent = req.legIntent,
                ),
                triggeringTick,
                meta,
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
                    legIntent = req.legIntent,
                ),
                triggeringTick,
                meta,
            )
            return
        }
        // MT5 triggers on the side's own quote, so a level the move [from] -> tick traded through is that price.
        val level = entryPrice(req)?.takeIf { from != null && tradedThrough(from, triggeringTick.price, it) }
        val rawFair =
            level ?: when (req) {
                is OrderRequest.Limit ->
                    fillPrice.of(req.side, triggeringTick, fallback = triggeringTick.price, meta)
                is OrderRequest.Stop ->
                    fillPrice.of(req.side, triggeringTick, fallback = triggeringTick.price, meta)
                is OrderRequest.IfTouched ->
                    fillPrice.of(req.side, triggeringTick, fallback = triggeringTick.price, meta)
                is OrderRequest.Market -> error("Market should not reach fillFromTrigger")
                is OrderRequest.StopLimit -> error("StopLimit should activate a Limit")
                else -> error("MT5BrokerSimulator fillFromTrigger unexpected type: ${req::class.simpleName}")
            }
        val fair =
            rawFair ?: run {
                reject(req, "no fill price for ${req.symbol} on trigger")
                return
            }
        val limit = (req as? OrderRequest.Limit)?.limitPrice
        val fillPrice =
            if (limit == null) {
                slippage.adjust(fair, req.side, meta)
            } else {
                limitFillPrice(req, fair, limit)
            }
        publishFill(req.id, req.symbol, req.side, fillPrice, req.quantity, req.strategyId, meta)
    }

    /**
     * A limit fills at the crossing print or its level, whichever is better — except a
     * protective take-profit under [TakeProfitFill.LEVEL], which fills at the level (#1135).
     */
    private fun limitFillPrice(
        req: OrderRequest,
        execution: BigDecimal,
        limit: BigDecimal,
    ): BigDecimal =
        when {
            takeProfitFill == TakeProfitFill.LEVEL && req.legIntent is LegIntent.Close -> limit
            req.side == Side.BUY -> execution.min(limit)
            else -> execution.max(limit)
        }

    private fun activateLimit(
        limit: OrderRequest.Limit,
        triggeringTick: Tick,
        meta: InstrumentMeta,
    ) {
        if (mt5Triggered(limit, triggeringTick)) {
            val execution =
                requireNotNull(
                    fillPrice.of(limit.side, triggeringTick, fallback = triggeringTick.price, meta),
                )
            val fillPrice = limitFillPrice(limit, execution, limit.limitPrice)
            publishFill(
                limit.id,
                limit.symbol,
                limit.side,
                fillPrice,
                limit.quantity,
                limit.strategyId,
                meta,
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
        meta: InstrumentMeta,
    ) {
        val rounded = price.setScale(meta.digits, RoundingMode.HALF_EVEN).setScale(Money.SCALE, Money.ROUNDING)
        val slices = partialFillModel.slices(qty, meta).filter { it.signum() > 0 }
        if (slices.size > 1) {
            var cumulative = BigDecimal.ZERO
            for (slice in slices.dropLast(1)) {
                cumulative = cumulative.add(slice)
                bus.publish(
                    BrokerEvent.OrderPartiallyFilled(
                        clientOrderId = clientOrderId,
                        brokerOrderId = clientOrderId,
                        symbol = symbol,
                        side = side,
                        price = rounded,
                        quantity = slice,
                        cumulativeFilled = cumulative,
                        strategyId = strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
            val finalSlice = slices.last()
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = clientOrderId,
                    brokerOrderId = clientOrderId,
                    symbol = symbol,
                    side = side,
                    price = rounded,
                    quantity = finalSlice,
                    strategyId = strategyId,
                    timestamp = clock.now(),
                ),
            )
            return
        }
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = clientOrderId,
                brokerOrderId = clientOrderId,
                symbol = symbol,
                side = side,
                price = rounded,
                quantity = qty,
                strategyId = strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    private fun drainDelayedSubmissions(releasedBy: Long) {
        if (delayedSubmissions.isEmpty()) return
        val due = delayedSubmissions.filter { it.releaseAt <= releasedBy }
        if (due.isEmpty()) return
        delayedSubmissions.removeAll(due.toSet())
        due.sortedBy { it.ordinal }.forEach { receive(it.request, it.ordinal) }
    }

    private fun reject(
        req: OrderRequest,
        reason: String,
    ) {
        log.info("MT5-Sim reject order_id={} symbol={} reason={}", req.id, req.symbol, reason)
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = req.id,
                brokerOrderId = req.id,
                reason = reason,
                strategyId = req.strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    private fun quantizeVolume(
        qty: BigDecimal,
        meta: InstrumentMeta,
    ): BigDecimal = qty.divide(meta.volumeStep, 0, RoundingMode.DOWN).multiply(meta.volumeStep)

    private fun limitSideRefusal(
        request: OrderRequest,
        meta: InstrumentMeta,
    ): String? {
        val tick = lastTickBySymbol[request.symbol]
        val current = fillPrice.of(request.side, tick, fallback = priceProvider.lastPrice(request.symbol), meta = meta)
        return current?.let { mt5LimitSideRefusal(request, it) }
    }

    private fun validateStopsLevel(
        request: OrderRequest,
        meta: InstrumentMeta,
    ): String? {
        if (!enforceStopsLevel || meta.tradeStopsLevelPoints == 0) return null
        val ref =
            fillPrice.of(
                request.side,
                lastTickBySymbol[request.symbol],
                fallback = priceProvider.lastPrice(request.symbol),
                meta = meta,
            ) ?: return null
        val minDistance = meta.pointSize.multiply(BigDecimal(meta.tradeStopsLevelPoints))
        for ((label, price) in pendingPrices(request)) {
            val distance = price.subtract(ref).abs()
            if (distance < minDistance) {
                return "$label ${price.toPlainString()} is inside tradeStopsLevel " +
                    "${minDistance.toPlainString()} from current ${ref.toPlainString()} " +
                    "(tradeStopsLevel=${meta.tradeStopsLevelPoints}, pointSize=${meta.pointSize.toPlainString()})"
            }
        }
        if (request is OrderRequest.Bracket) {
            val entry = entryPrice(request.entry) ?: ref
            val exits =
                buildList {
                    add("takeProfit" to request.takeProfit)
                    (request.stopLoss as? com.qkt.execution.StopLossSpec.Fixed)
                        ?.let { add("stopLoss" to it.price) }
                }
            for ((label, price) in exits) {
                val distance = price.subtract(entry).abs()
                if (distance < minDistance) {
                    return "$label ${price.toPlainString()} is inside tradeStopsLevel " +
                        "${minDistance.toPlainString()} from entry ${entry.toPlainString()}"
                }
            }
        }
        return null
    }

    private fun pendingPrices(request: OrderRequest): List<Pair<String, BigDecimal>> =
        when (request) {
            is OrderRequest.Limit -> listOf("limitPrice" to request.limitPrice)
            is OrderRequest.Stop -> listOf("stopPrice" to request.stopPrice)
            is OrderRequest.StopLimit -> listOf("stopPrice" to request.stopPrice, "limitPrice" to request.limitPrice)
            is OrderRequest.IfTouched ->
                listOfNotNull(
                    "triggerPrice" to request.triggerPrice,
                    request.limitPrice?.let { "limitPrice" to it },
                )
            is OrderRequest.Market -> emptyList()
            is OrderRequest.Bracket -> pendingPrices(request.entry)
            else -> emptyList()
        }

    private fun entryPrice(request: OrderRequest): BigDecimal? =
        when (request) {
            is OrderRequest.Limit -> request.limitPrice
            is OrderRequest.Stop -> request.stopPrice
            is OrderRequest.StopLimit -> request.stopPrice
            is OrderRequest.IfTouched -> request.triggerPrice
            else -> null
        }

    /**
     * Returns a copy of [request] with [newQuantity] in place of the original. Used
     * after volume quantization so downstream fill events carry the correct size.
     */
    private fun OrderRequest.withQuantity(newQuantity: BigDecimal): OrderRequest =
        when (this) {
            is OrderRequest.Market -> copy(quantity = newQuantity)
            is OrderRequest.Limit -> copy(quantity = newQuantity)
            is OrderRequest.Stop -> copy(quantity = newQuantity)
            is OrderRequest.StopLimit -> copy(quantity = newQuantity)
            is OrderRequest.IfTouched -> copy(quantity = newQuantity)
            else -> error("MT5BrokerSimulator.withQuantity: unhandled order type ${this::class.simpleName}")
        }

    private data class DelayedSubmission(
        val request: OrderRequest,
        val ordinal: Int,
        val releaseAt: Long,
    )

    private data class PendingStopFill(
        val request: OrderRequest,
        val fillAt: Long,
    )
}
