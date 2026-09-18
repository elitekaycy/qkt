package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * Price and order arithmetic for [LatchCompiler] entries: the signed direction-unit
 * contribution of a distance, the expiry instant, the inverted-stop check, and re-sizing an
 * entry order to its computed quantity.
 */
internal object LatchOrderMath {
    /**
     * Returns the signed contribution of [rel] in direction units.
     * WITH = +d (moves with the break), AGAINST = -d (moves against it).
     * Distances must be compile-time constants (literals); runtime expressions are rejected.
     */
    fun signedDist(rel: DirRel?): BigDecimal {
        if (rel == null) return BigDecimal.ZERO
        val d =
            (rel.dist as? NumLit)?.value
                ?: error("LATCH distances must be compile-time constants (literal or LET); got ${rel.dist}")
        return if (rel.sense == DirSense.WITH) d else d.negate()
    }

    fun expiresAt(
        now: Long,
        ms: Long?,
    ): Long? = ms?.let { now + it }

    fun invalidStop(
        side: Side,
        entry: BigDecimal,
        sl: BigDecimal,
    ): Boolean = if (side == Side.BUY) sl >= entry else sl <= entry

    fun withQty(
        req: OrderRequest,
        qty: BigDecimal,
    ): OrderRequest =
        when (req) {
            is OrderRequest.Market -> req.copy(quantity = qty)
            is OrderRequest.Limit -> req.copy(quantity = qty)
            is OrderRequest.Stop -> req.copy(quantity = qty)
            else -> req
        }
}
