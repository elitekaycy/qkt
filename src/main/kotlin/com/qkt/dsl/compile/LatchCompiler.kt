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
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.slf4j.LoggerFactory

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
        if (latch.confirm is LatchRetestHold) {
            require(latch.confirm.distance is NumLit) {
                "LATCH RETEST_HOLD distance must be compile-time constant (literal or LET); got ${latch.confirm.distance}"
            }
        }
        val referenceExpr = sensor.reference ?: StreamFieldRef(latch.stream, "close")
        val entries = latch.entries.map { compileEntry(latch.stream, it, strategyId) }
        return CompiledLatch(
            streamAlias = latch.stream,
            offset = exprCompiler.compile(sensor.offset),
            reference = exprCompiler.compile(referenceExpr),
            armWindowMs = latch.armWindow.millis,
            name = latch.name,
            entries = entries,
            confirm = latch.confirm,
        )
    }

    private fun compileEntry(
        latchStream: String,
        entry: LatchEntry,
        strategyId: String,
    ): CompiledLatchEntry {
        val entryStream = entry.stream ?: latchStream
        // Signed contribution in "direction units": WITH = +d, AGAINST = -d, MARKET = 0.
        val entryRel: DirRel? = (entry.order as? LatchLimit)?.price ?: (entry.order as? LatchStop)?.price
        val entryContrib: BigDecimal = LatchOrderMath.signedDist(entryRel)
        val slRel = entry.bracket?.stopLoss
        val tpRel = entry.bracket?.takeProfit

        // Static stop distance = |entryContrib - slContrib| (O and direction cancel).
        val stopDistance: BigDecimal? =
            if (slRel != null) {
                (entryContrib - LatchOrderMath.signedDist(slRel)).abs()
            } else {
                null
            }
        val compiledSize = entry.sizing?.let { sizingCompiler.compile(it, stopDistance, entryStream) }
        val expiresInMs = entry.expire?.millis

        val builder =
            LatchEntryBuilder { direction, anchor, ec ->
                val symbol = ec.streams[entryStream]?.qktSymbol ?: error("Unknown stream alias: $entryStream")
                val dir = BigDecimal(direction)
                val side = if (direction > 0) Side.BUY else Side.SELL
                val now = ec.nowMs()
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
                                symbol,
                                side,
                                BigDecimal.ONE,
                                TimeInForce.GTC,
                                now,
                                strategyId,
                            )
                        is LatchLimit ->
                            OrderRequest.Limit(
                                id,
                                symbol,
                                side,
                                BigDecimal.ONE,
                                resolve(o.price),
                                TimeInForce.GTC,
                                now,
                                strategyId,
                                LatchOrderMath.expiresAt(now, expiresInMs),
                            )
                        is LatchStop ->
                            OrderRequest.Stop(
                                id,
                                symbol,
                                side,
                                BigDecimal.ONE,
                                resolve(o.price),
                                TimeInForce.GTC,
                                now,
                                strategyId,
                                LatchOrderMath.expiresAt(now, expiresInMs),
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
                    return@LatchEntryBuilder LatchOrderMath.withQty(entryReq, qty)
                }
                val slPrice = slRel?.let { resolve(it) }
                val tpPrice = tpRel?.let { resolve(it) } ?: entryPrice

                if (slPrice != null && LatchOrderMath.invalidStop(side, entryPrice, slPrice)) {
                    log.warn("latch entry skipped (inverted geometry): entry=$entryPrice sl=$slPrice side=$side")
                    return@LatchEntryBuilder null
                }
                OrderRequest.Bracket(
                    id,
                    symbol,
                    side,
                    qty,
                    entry = LatchOrderMath.withQty(entryReq, qty),
                    takeProfit = tpPrice,
                    stopLoss = StopLossSpec.Fixed(slPrice ?: entryPrice),
                    timeInForce = TimeInForce.GTC,
                    timestamp = now,
                    strategyId = strategyId,
                    expiresAt = LatchOrderMath.expiresAt(now, expiresInMs),
                )
            }
        return CompiledLatchEntry(entryStream, builder)
    }
}
