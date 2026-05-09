package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

enum class TriggerType { MARKET, LIMIT }

sealed interface OrderRequest {
    val id: String
    val strategyId: String
    val symbol: String
    val side: Side
    val quantity: BigDecimal
    val timeInForce: TimeInForce
    val timestamp: Long

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
