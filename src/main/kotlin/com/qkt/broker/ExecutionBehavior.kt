package com.qkt.broker

import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentMeta
import java.math.BigDecimal
import java.math.RoundingMode

fun interface RejectionModel {
    fun rejectionReason(
        request: OrderRequest,
        ordinal: Int,
    ): String?
}

object NoBrokerRejections : RejectionModel {
    override fun rejectionReason(
        request: OrderRequest,
        ordinal: Int,
    ): String? = null
}

class RejectEveryNthOrder(
    private val every: Int,
) : RejectionModel {
    init {
        require(every > 0) { "RejectEveryNthOrder.every must be > 0: $every" }
    }

    override fun rejectionReason(
        request: OrderRequest,
        ordinal: Int,
    ): String? = if (ordinal % every == 0) "simulated deterministic rejection every $every order(s)" else null
}

interface PartialFillModel {
    fun slices(
        quantity: BigDecimal,
        meta: InstrumentMeta,
    ): List<BigDecimal>
}

object FullFill : PartialFillModel {
    override fun slices(
        quantity: BigDecimal,
        meta: InstrumentMeta,
    ): List<BigDecimal> = listOf(quantity)
}

class FractionalPartialFill(
    private val firstFraction: BigDecimal,
) : PartialFillModel {
    init {
        require(firstFraction > BigDecimal.ZERO && firstFraction < BigDecimal.ONE) {
            "FractionalPartialFill.firstFraction must be in (0, 1): $firstFraction"
        }
    }

    override fun slices(
        quantity: BigDecimal,
        meta: InstrumentMeta,
    ): List<BigDecimal> {
        val first =
            quantity
                .multiply(firstFraction)
                .divide(meta.volumeStep, 0, RoundingMode.DOWN)
                .multiply(meta.volumeStep)
        if (first.signum() <= 0 || first >= quantity) return listOf(quantity)
        val remaining = quantity.subtract(first)
        if (remaining.signum() <= 0) return listOf(quantity)
        return listOf(first, remaining)
    }
}
