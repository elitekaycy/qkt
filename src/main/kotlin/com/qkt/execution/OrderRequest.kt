package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

/** How a trigger order resolves when its trigger price prints — as a market or a limit. */
enum class TriggerType { MARKET, LIMIT }

/**
 * Normalized order shape produced by the strategy/DSL layer and consumed by brokers.
 *
 * Every concrete `OrderRequest` carries enough information to be submitted to any
 * broker that supports the relevant [com.qkt.broker.OrderTypeCapability]. The sealed
 * hierarchy enumerates every order shape qkt currently understands: simple types
 * (Market/Limit/Stop/StopLimit), trigger types (IfTouched, TrailingStop variants),
 * composites (OCO, OTO, Bracket), and engine-managed shapes (ScaleOut, TimeExit, Stack).
 *
 * Engine-managed shapes are split into atomic broker calls by [com.qkt.app.OrderManager]
 * — the broker never sees a Bracket; it sees an entry plus child legs.
 */
sealed interface OrderRequest {
    /** Client-assigned id, used to correlate broker callbacks back to this request. */
    val id: String

    /** Strategy that produced the originating signal — empty for engine-internal orders. */
    val strategyId: String

    /** Venue-specific symbol identifier (e.g. `"BTCUSDT"`, `"XAUUSDm"`). */
    val symbol: String

    /** Buy or sell. */
    val side: Side

    /** Quantity in venue units (lots, contracts, base currency — venue dictates). */
    val quantity: BigDecimal

    /** How long the venue should keep this order working. */
    val timeInForce: TimeInForce

    /** Wall-clock at which the request was created. */
    val timestamp: Long

    /** Fill at the next available market price. */
    data class Market(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }

    /** Resting order that fills only at [limitPrice] or better. */
    data class Limit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val limitPrice: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(limitPrice.signum() > 0) { "limitPrice must be > 0: $limitPrice" }
        }
    }

    /** Triggers a market order when [stopPrice] prints. */
    data class Stop(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val stopPrice: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(stopPrice.signum() > 0) { "stopPrice must be > 0: $stopPrice" }
        }
    }

    /** Triggers a limit order at [limitPrice] when [stopPrice] prints. */
    data class StopLimit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val stopPrice: BigDecimal,
        val limitPrice: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(stopPrice.signum() > 0) { "stopPrice must be > 0: $stopPrice" }
            require(limitPrice.signum() > 0) { "limitPrice must be > 0: $limitPrice" }
        }
    }

    /**
     * Triggers a [Market] or [Limit] order when [triggerPrice] prints.
     *
     * Distinct from [Stop] because IfTouched can trigger in either direction — useful
     * for take-profit-style targets where the trigger is above the current price for
     * a long position.
     */
    data class IfTouched(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val triggerPrice: BigDecimal,
        val onTrigger: TriggerType,
        val limitPrice: BigDecimal? = null,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(triggerPrice.signum() > 0) { "triggerPrice must be > 0: $triggerPrice" }
            if (onTrigger == TriggerType.LIMIT) {
                requireNotNull(limitPrice) { "IfTouched.LIMIT requires limitPrice" }
                require(limitPrice.signum() > 0) { "limitPrice must be > 0: $limitPrice" }
            }
        }
    }

    /** Stop that trails the favorable price direction by [trailAmount] in [trailMode] units. */
    data class TrailingStop(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(trailAmount.signum() > 0) { "trailAmount must be > 0: $trailAmount" }
            if (trailMode == TrailMode.PERCENT) {
                require(trailAmount <= BigDecimal("100")) {
                    "PERCENT trailAmount must be <= 100: $trailAmount"
                }
            }
        }
    }

    /** [TrailingStop] variant that triggers a limit order offset by [limitOffset] from the trail. */
    data class TrailingStopLimit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        val limitOffset: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(trailAmount.signum() > 0) { "trailAmount must be > 0: $trailAmount" }
            require(limitOffset.signum() >= 0) { "limitOffset must be >= 0: $limitOffset" }
        }
    }

    /** Two orders linked One-Cancels-Other; either fills, the other auto-cancels. */
    data class StandaloneOCO(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val leg1: OrderRequest,
        val leg2: OrderRequest,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }

    /** Parent order that activates [children] only after it fills (One-Triggers-Other). */
    data class OTO(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val parent: OrderRequest,
        val children: List<OrderRequest>,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(children.isNotEmpty()) { "OTO must have at least one child" }
        }
    }

    /**
     * Entry order with [takeProfit] + [stopLoss] children attached.
     *
     * The most common shape — DSL `BRACKET` compiles to this. The take-profit/stop-loss
     * values are absolute prices, not offsets.
     */
    data class Bracket(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val entry: OrderRequest,
        val takeProfit: BigDecimal,
        val stopLoss: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(takeProfit.signum() > 0) { "takeProfit must be > 0: $takeProfit" }
            require(stopLoss.signum() > 0) { "stopLoss must be > 0: $stopLoss" }
            require(takeProfit.compareTo(stopLoss) != 0) {
                "takeProfit and stopLoss must differ: tp=$takeProfit sl=$stopLoss"
            }
        }
    }

    /**
     * Engine-managed multi-leg exit — closes [basis] in fractional slices per [legs].
     *
     * The broker only sees the individual leg orders; the manager owns the lifecycle.
     */
    data class ScaleOut(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val basis: OrderRequest,
        val legs: List<ScaleOutLeg>,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(legs.isNotEmpty()) { "ScaleOut requires at least one leg" }
            val totalFraction = legs.fold(BigDecimal.ZERO) { acc, l -> acc + l.fraction }
            require(totalFraction <= BigDecimal.ONE) {
                "ScaleOut total fraction exceeds 1.0: $totalFraction"
            }
        }
    }

    /**
     * Engine-managed wrapper that resolves [target] before [deadline] or invokes [onExpiry].
     *
     * Used to enforce "exit by N hours" without depending on a venue-side timer.
     */
    data class TimeExit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val target: OrderRequest,
        val deadline: java.time.Instant,
        val onExpiry: ExpiryAction,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }

    /**
     * Engine-managed pyramiding plan — fires N layered entries per [plan].
     *
     * See [StackPlan] for layer spacing, time-fence, and per-layer overrides.
     * Compiled from the DSL `STACK` keyword.
     */
    data class Stack(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val plan: StackPlan,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }
}

/** Returns a copy of this request with [strategyId] populated; preserves the concrete subtype. */
fun OrderRequest.withStrategyId(strategyId: String): OrderRequest =
    when (this) {
        is OrderRequest.Market -> copy(strategyId = strategyId)
        is OrderRequest.Limit -> copy(strategyId = strategyId)
        is OrderRequest.Stop -> copy(strategyId = strategyId)
        is OrderRequest.StopLimit -> copy(strategyId = strategyId)
        is OrderRequest.IfTouched -> copy(strategyId = strategyId)
        is OrderRequest.TrailingStop -> copy(strategyId = strategyId)
        is OrderRequest.TrailingStopLimit -> copy(strategyId = strategyId)
        is OrderRequest.StandaloneOCO -> copy(strategyId = strategyId)
        is OrderRequest.OTO -> copy(strategyId = strategyId)
        is OrderRequest.Bracket -> copy(strategyId = strategyId)
        is OrderRequest.ScaleOut -> copy(strategyId = strategyId)
        is OrderRequest.TimeExit -> copy(strategyId = strategyId)
        is OrderRequest.Stack -> copy(strategyId = strategyId)
    }
