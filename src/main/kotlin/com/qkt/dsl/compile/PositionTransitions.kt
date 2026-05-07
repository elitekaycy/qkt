package com.qkt.dsl.compile

import java.math.BigDecimal

enum class PositionTransition { Stay, OpenedFromZero, ClosedToZero, Flipped }

class PositionTransitions {
    private val prev: MutableMap<String, BigDecimal> = HashMap()

    fun observe(
        symbol: String,
        currentQty: BigDecimal,
    ): PositionTransition {
        val previousQty = prev[symbol] ?: BigDecimal.ZERO
        prev[symbol] = currentQty
        val wasZero = previousQty.signum() == 0
        val isZero = currentQty.signum() == 0
        return when {
            wasZero && isZero -> PositionTransition.Stay
            wasZero && !isZero -> PositionTransition.OpenedFromZero
            !wasZero && isZero -> PositionTransition.ClosedToZero
            previousQty.signum() != currentQty.signum() -> PositionTransition.Flipped
            else -> PositionTransition.Stay
        }
    }
}
