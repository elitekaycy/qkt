package com.qkt.dsl.compile

import com.qkt.dsl.ast.LatchConfirm
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

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
    val entries: List<CompiledLatchEntry>,
    val confirm: LatchConfirm,
) {
    val entryBuilders: List<LatchEntryBuilder> = entries.map { it.builder }
}

/** One compiled latch entry and the stream it submits orders on when the latch confirms. */
data class CompiledLatchEntry(
    val streamAlias: String,
    val builder: LatchEntryBuilder,
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
