package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * A compiled latch: the trip-wire expressions and the entry builders to fan out when it fires.
 *
 * [streamAlias] is the stream whose ticks the [LatchManager] watches. [reference] and [offset]
 * evaluate to prices; the manager arms wires at `ref + offset` (up) and `ref - offset` (down).
 * [entryBuilders] each receive `(direction, anchor, ec)` and produce an [OrderRequest] (or null
 * to skip on inverted geometry). e.g. direction=+1, anchor=2000.50 from an up-break.
 */
class CompiledLatch(
    val streamAlias: String,
    val offset: CompiledExpr,
    val reference: CompiledExpr,
    val armWindowMs: Long,
    val name: String?,
    val entryBuilders: List<LatchEntryBuilder>,
)

/**
 * Builds one entry order when a latch fires.
 *
 * [direction] is +1 for a long break (up-wire) or -1 for a short break (down-wire).
 * [anchor] is the trip-wire price `O`. Returns null to skip the entry (e.g. inverted
 * geometry where the stop would sit on the wrong side of the fill).
 */
fun interface LatchEntryBuilder {
    fun build(
        direction: Int,
        anchor: BigDecimal,
        ec: EvalContext,
    ): OrderRequest?
}

/**
 * Compiles a [Latch] AST into a [CompiledLatch] whose entry builders, given
 * `(direction, anchor, ec)`, produce concrete [OrderRequest]s relative to the
 * break direction and anchor price `O`.
 *
 * Stop distance for risk sizing is computed statically at compile time as
 * `|slContrib - entryContrib|` (O and direction cancel). Distances must therefore
 * be compile-time constants (literals or LET). A runtime expression is rejected
 * with a clear error.
 */
class LatchCompiler(
    private val exprCompiler: ExprCompiler,
    private val sizingCompiler: SizingCompiler,
    private val ids: IdGenerator,
) {
    private val log = LoggerFactory.getLogger(LatchCompiler::class.java)

    /** Compile [latch] into a [CompiledLatch] stamped with [strategyId]. */
    fun compile(
        latch: Latch,
        strategyId: String,
    ): CompiledLatch {
        val sensor = latch.sensor as BreakOffset
        val referenceExpr = sensor.reference ?: StreamFieldRef(latch.stream, "close")
        val builders = latch.entries.map { compileEntry(latch.stream, it, strategyId) }
        return CompiledLatch(
            streamAlias = latch.stream,
            offset = exprCompiler.compile(sensor.offset),
            reference = exprCompiler.compile(referenceExpr),
            armWindowMs = latch.armWindow.millis,
            name = latch.name,
            entryBuilders = builders,
        )
    }

    private fun compileEntry(
        stream: String,
        entry: LatchEntry,
        strategyId: String,
    ): LatchEntryBuilder {
        // Signed contribution in "direction units": WITH = +d, AGAINST = -d, MARKET = 0.
        val entryRel: DirRel? = (entry.order as? LatchLimit)?.price ?: (entry.order as? LatchStop)?.price
        val entryContrib: BigDecimal = signedDist(entryRel)
        val slRel = entry.bracket?.stopLoss
        val tpRel = entry.bracket?.takeProfit

        // Static stop distance = |entryContrib - slContrib| (O and direction cancel).
        val stopDistance: BigDecimal? =
            if (slRel != null) {
                (entryContrib - signedDist(slRel)).abs()
            } else {
                null
            }
        val compiledSize = entry.sizing?.let { sizingCompiler.compile(it, stopDistance, stream) }
        val expiresInMs = entry.expire?.millis

        return LatchEntryBuilder { direction, anchor, ec ->
            val dir = BigDecimal(direction)
            val side = if (direction > 0) Side.BUY else Side.SELL
            val now = ec.strategyContext.clock.now()
            val id = ids.next()

            fun resolve(rel: DirRel): BigDecimal {
                val d = (exprCompiler.compile(rel.dist).evaluate(ec) as Value.Num).v
                return if (rel.sense == DirSense.WITH) anchor + dir * d else anchor - dir * d
            }

            val entryReq: OrderRequest =
                when (val o = entry.order) {
                    is LatchMarket ->
                        OrderRequest.Market(
                            id,
                            ec.candle.symbol,
                            side,
                            BigDecimal.ONE,
                            TimeInForce.GTC,
                            now,
                            strategyId,
                        )
                    is LatchLimit ->
                        OrderRequest.Limit(
                            id,
                            ec.candle.symbol,
                            side,
                            BigDecimal.ONE,
                            resolve(o.price),
                            TimeInForce.GTC,
                            now,
                            strategyId,
                            expiresAt(now, expiresInMs),
                        )
                    is LatchStop ->
                        OrderRequest.Stop(
                            id,
                            ec.candle.symbol,
                            side,
                            BigDecimal.ONE,
                            resolve(o.price),
                            TimeInForce.GTC,
                            now,
                            strategyId,
                            expiresAt(now, expiresInMs),
                        )
                }

            val entryPrice =
                when (val o = entry.order) {
                    is LatchMarket -> anchor
                    is LatchLimit -> resolve(o.price)
                    is LatchStop -> resolve(o.price)
                }
            val qty = compiledSize?.evaluate(ec, entryPrice) ?: BigDecimal.ONE

            if (slRel == null && tpRel == null) {
                return@LatchEntryBuilder withQty(entryReq, qty)
            }
            val slPrice = slRel?.let { resolve(it) }
            val tpPrice = tpRel?.let { resolve(it) } ?: entryPrice

            if (slPrice != null && invalidStop(side, entryPrice, slPrice)) {
                log.warn("latch entry skipped (inverted geometry): entry=$entryPrice sl=$slPrice side=$side")
                return@LatchEntryBuilder null
            }
            OrderRequest.Bracket(
                id,
                ec.candle.symbol,
                side,
                qty,
                entry = withQty(entryReq, qty),
                takeProfit = tpPrice,
                stopLoss = StopLossSpec.Fixed(slPrice ?: entryPrice),
                timeInForce = TimeInForce.GTC,
                timestamp = now,
                strategyId = strategyId,
                expiresAt = expiresAt(now, expiresInMs),
            )
        }
    }

    /**
     * Returns the signed contribution of [rel] in direction units.
     * WITH = +d (moves with the break), AGAINST = -d (moves against it).
     * Distances must be compile-time constants (literals); runtime expressions are rejected.
     */
    private fun signedDist(rel: DirRel?): BigDecimal {
        if (rel == null) return BigDecimal.ZERO
        val d =
            (rel.dist as? NumLit)?.value
                ?: error("LATCH distances must be compile-time constants (literal or LET); got ${rel.dist}")
        return if (rel.sense == DirSense.WITH) d else d.negate()
    }

    private fun expiresAt(
        now: Long,
        ms: Long?,
    ): Long? = ms?.let { now + it }

    private fun invalidStop(
        side: Side,
        entry: BigDecimal,
        sl: BigDecimal,
    ): Boolean = if (side == Side.BUY) sl >= entry else sl <= entry

    private fun withQty(
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
