package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
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
 * Fidelity-oriented backtest broker that mirrors MT5 venue behaviour: volume
 * quantization, price rounding, ask/bid fills, and configurable spread + slippage.
 *
 * Closes execution-parity divergences 1, 2, 3, 4-6 in `docs/parity/backtest-vs-live.md`
 * — the gaps that make `PaperBroker` backtest fills not match what live MT5 would
 * have actually filled. Opt-in via `Backtest(brokerKind = BrokerKind.MT5_SIM)` or
 * the CLI `--broker mt5-sim` flag; `PaperBroker` remains the default.
 *
 * **What's modelled here:**
 * - Volume rounded DOWN to [InstrumentMeta.volumeStep] before fill (row 1).
 * - Orders below [InstrumentMeta.volumeMin] rejected pre-fill (row 3).
 * - Fill prices rounded to [InstrumentMeta.digits] decimals, HALF_EVEN (row 2).
 * - Market BUY fills at ask, SELL fills at bid — taken from `Tick.ask`/`Tick.bid`
 *   when present, or synthesised as `price ± (pointSize × spreadPoints / 2)` when
 *   the feed only carries mid (rows 4, 6).
 * - Slippage applied on top via [SlippageModel] (default [ZeroSlippage]).
 *
 * **Not modelled here (deferred to follow-ups):**
 * - `tradeStopsLevel` enforcement (parity row 8).
 * - OCO atomicity edge cases (row 9).
 * - Network latency (row 11; issue #140).
 * - Retcode semantics (row 12).
 *
 * **Threading:** single-threaded by design. `working`, `lastTickBySymbol`, and
 * [UniformRandomSlippage] state are intentionally not synchronised — the simulator
 * is only wired through [Backtest], whose tick loop and order submission run on one
 * thread. Concurrent `submit()` / `onTick()` is unsupported and may corrupt state.
 */
class MT5BrokerSimulator(
    private val bus: EventBus,
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider,
    private val instruments: InstrumentRegistry,
    private val slippage: SlippageModel = ZeroSlippage,
    private val syntheticSpreadPoints: Int = 2,
) : Broker {
    init {
        require(syntheticSpreadPoints >= 0) {
            "syntheticSpreadPoints must be >= 0: $syntheticSpreadPoints"
        }
    }

    private val log = LoggerFactory.getLogger(MT5BrokerSimulator::class.java)

    private val working: MutableList<OrderRequest> = mutableListOf()
    private val lastTickBySymbol: MutableMap<String, Tick> = HashMap()

    init {
        bus.subscribe<TickEvent> { e -> onTick(e.tick) }
    }

    override val name: String = "MT5-Sim"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
        )

    override fun submit(request: OrderRequest): SubmitAck {
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
        val sized = request.withQuantity(quantized)
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
        lastTickBySymbol[tick.symbol] = tick
        if (working.isEmpty()) return
        val toFill = working.filter { req -> req.symbol == tick.symbol && checkTrigger(req, tick.price) }
        for (wo in toFill) {
            working.remove(wo)
            fillFromTrigger(wo, tick)
        }
    }

    private fun fillMarket(
        req: OrderRequest.Market,
        meta: InstrumentMeta,
    ) {
        val tick = lastTickBySymbol[req.symbol]
        val fairFill = sidedFillPrice(req.side, tick, fallback = priceProvider.lastPrice(req.symbol), meta)
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
    ) {
        val meta =
            instruments.lookup(req.symbol)
                ?: run {
                    reject(req, "no InstrumentMeta for symbol ${req.symbol} at fill time")
                    return
                }
        val (rawFair, side, qty) =
            when (req) {
                is OrderRequest.Limit ->
                    Triple(
                        sidedFillPrice(req.side, triggeringTick, fallback = triggeringTick.price, meta),
                        req.side,
                        req.quantity,
                    )
                is OrderRequest.Stop ->
                    Triple(
                        sidedFillPrice(req.side, triggeringTick, fallback = triggeringTick.price, meta),
                        req.side,
                        req.quantity,
                    )
                is OrderRequest.StopLimit ->
                    Triple(req.limitPrice, req.side, req.quantity)
                is OrderRequest.IfTouched ->
                    if (req.onTrigger == TriggerType.MARKET) {
                        Triple(
                            sidedFillPrice(req.side, triggeringTick, fallback = triggeringTick.price, meta),
                            req.side,
                            req.quantity,
                        )
                    } else {
                        Triple(req.limitPrice!!, req.side, req.quantity)
                    }
                is OrderRequest.Market -> error("Market should not reach fillFromTrigger")
                else -> error("MT5BrokerSimulator fillFromTrigger unexpected type: ${req::class.simpleName}")
            }
        val fair =
            rawFair ?: run {
                reject(req, "no fill price for ${req.symbol} on trigger")
                return
            }
        val withSlip = slippage.adjust(fair, side, meta)
        publishFill(req.id, req.symbol, side, withSlip, qty, req.strategyId, meta)
    }

    /**
     * Returns the side-adjusted fair fill price: ask for BUY, bid for SELL. Prefers
     * [tick]`.ask`/`.bid` when present; otherwise synthesises spread around
     * [fallback] (or [tick]`.price`) using `meta.pointSize × syntheticSpreadPoints`.
     * Returns null if there's no usable price at all.
     */
    private fun sidedFillPrice(
        side: Side,
        tick: Tick?,
        fallback: BigDecimal?,
        meta: InstrumentMeta,
    ): BigDecimal? {
        if (tick?.bid != null && tick.ask != null) {
            return if (side == Side.BUY) tick.ask!! else tick.bid!!
        }
        val mid = tick?.price ?: fallback ?: return null
        if (syntheticSpreadPoints == 0) return mid
        val halfSpread =
            meta.pointSize
                .multiply(BigDecimal(syntheticSpreadPoints))
                .divide(BigDecimal(2), meta.digits + 2, RoundingMode.HALF_EVEN)
        return if (side == Side.BUY) mid.add(halfSpread) else mid.subtract(halfSpread)
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

    private fun checkTrigger(
        req: OrderRequest,
        tickPrice: BigDecimal,
    ): Boolean =
        when (req) {
            is OrderRequest.Limit ->
                if (req.side == Side.BUY) tickPrice <= req.limitPrice else tickPrice >= req.limitPrice
            is OrderRequest.Stop ->
                if (req.side == Side.BUY) tickPrice >= req.stopPrice else tickPrice <= req.stopPrice
            is OrderRequest.StopLimit ->
                if (req.side == Side.BUY) tickPrice >= req.stopPrice else tickPrice <= req.stopPrice
            is OrderRequest.IfTouched ->
                if (req.side == Side.BUY) tickPrice <= req.triggerPrice else tickPrice >= req.triggerPrice
            is OrderRequest.Market -> false
            else -> error("MT5BrokerSimulator.checkTrigger: unhandled order type ${req::class.simpleName}")
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
}
