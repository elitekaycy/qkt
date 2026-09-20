package com.qkt.dsl.compile

import com.qkt.dsl.ast.SequenceAccessor
import java.math.BigDecimal

/**
 * Compiles `SEQUENCE.<name>.<field>` and `SEQUENCE.<name>.<stage>.<field>` reads against the
 * strategy's sequence runtime: the current stage, completion, and each stage's price and time.
 */
internal object SequenceAccessorCompiler {
    fun compile(ref: SequenceAccessor): CompiledExpr =
        when {
            ref.stage == null && ref.field == "stage" ->
                CompiledExpr { ctx ->
                    Value.Num(BigDecimal.valueOf(ctx.sequences.stage(ref.sequence).toLong()))
                }
            ref.stage == null && ref.field == "complete" ->
                CompiledExpr { ctx ->
                    Value.of(ctx.sequences.complete(ref.sequence))
                }
            ref.stage != null && ref.field == "price" ->
                CompiledExpr { ctx ->
                    Value.Num(ctx.sequences.stagePrice(ref.sequence, ref.stage) ?: BigDecimal.ZERO)
                }
            ref.stage != null && ref.field == "time" ->
                CompiledExpr { ctx ->
                    Value.Num(BigDecimal.valueOf(ctx.sequences.stageTime(ref.sequence, ref.stage) ?: 0L))
                }
            ref.stage == null ->
                error("Unknown SEQUENCE accessor: SEQUENCE.${ref.sequence}.${ref.field}")
            else ->
                error("Unknown SEQUENCE stage accessor: SEQUENCE.${ref.sequence}.${ref.stage}.${ref.field}")
        }
}
