package com.qkt.dsl.compile

import com.qkt.dsl.ast.DirRel

/**
 * The [ExprTransform] rewrite of a LATCH action: the break-offset sensor, each entry's order,
 * bracket and sizing, and the retest-hold confirm distance.
 */
internal class LatchTransform(
    private val exprs: ExprTransform,
) {
    // Walk a latch's expressions so LET (and other expr transforms) reach the offset,
    // each entry's direction-relative distance, and the bracket distances. Without this
    // the latch is a passthrough and `RETRACE near` (a LET ref) never resolves to a literal.
    fun latch(a: com.qkt.dsl.ast.Latch): com.qkt.dsl.ast.Latch =
        a.copy(
            sensor =
                when (val s = a.sensor) {
                    is com.qkt.dsl.ast.BreakOffset ->
                        com.qkt.dsl.ast
                            .BreakOffset(s.reference?.let(exprs::expr), exprs.expr(s.offset))
                },
            entries = a.entries.map(::latchEntry),
            confirm =
                when (val c = a.confirm) {
                    is com.qkt.dsl.ast.LatchRetestHold -> c.copy(distance = exprs.expr(c.distance))
                    else -> c
                },
        )

    private fun latchEntry(e: com.qkt.dsl.ast.LatchEntry): com.qkt.dsl.ast.LatchEntry =
        e.copy(
            order =
                when (val o = e.order) {
                    com.qkt.dsl.ast.LatchMarket -> o
                    is com.qkt.dsl.ast.LatchLimit ->
                        com.qkt.dsl.ast
                            .LatchLimit(dirRel(o.price))
                    is com.qkt.dsl.ast.LatchStop ->
                        com.qkt.dsl.ast
                            .LatchStop(dirRel(o.price))
                },
            bracket =
                e.bracket?.let { b ->
                    com.qkt.dsl.ast
                        .LatchBracket(b.stopLoss?.let(::dirRel), b.takeProfit?.let(::dirRel))
                },
            sizing = e.sizing?.let(exprs::sizing),
        )

    private fun dirRel(r: com.qkt.dsl.ast.DirRel): com.qkt.dsl.ast.DirRel =
        com.qkt.dsl.ast
            .DirRel(r.sense, exprs.expr(r.dist))
}
