package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

enum class TriggerType { MARKET, LIMIT }

sealed interface OrderRequest {
    val id: String
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
}
