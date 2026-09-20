package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.SequenceDecl

/**
 * Compiles the strategy's `SEQUENCE` declarations into a [SequenceRuntime]: each stage's
 * condition compiles against the sequence's stream, and the streams every stage reads become
 * the sequence's warm-up gate.
 */
internal object SequenceCompiler {
    fun compile(
        sequences: List<SequenceDecl>,
        streams: Map<String, HubKey>,
        exprCompiler: ExprCompiler,
        resolver: LetResolver,
    ): SequenceRuntime {
        require(sequences.map { it.name }.toSet().size == sequences.size) {
            "Duplicate SEQUENCE name in: ${sequences.map { it.name }}"
        }
        return SequenceRuntime(
            sequences.map { sequence ->
                val key = streams[sequence.stream] ?: error("Unknown SEQUENCE stream alias: ${sequence.stream}")
                require(
                    sequence.stages
                        .map { it.name }
                        .toSet()
                        .size == sequence.stages.size,
                ) {
                    "Duplicate STAGE name in SEQUENCE '${sequence.name}': ${sequence.stages.map { it.name }}"
                }
                val resolvedStages = sequence.stages.map { it.name to resolver.resolve(it.condition) }
                CompiledSequence(
                    name = sequence.name,
                    streamAlias = sequence.stream,
                    streamSymbol = key.qktSymbol,
                    stages =
                        sequence.stages.zip(resolvedStages).map { (stage, resolved) ->
                            CompiledSequenceStage(
                                name = stage.name,
                                withinMs = stage.within?.millis,
                                condition = exprCompiler.compile(resolved.second, ruleAlias = sequence.stream),
                            )
                        },
                    referencedAliases =
                        (
                            resolvedStages.flatMap { collectExprStreamAliases(it.second) } + sequence.stream
                        ).toSet(),
                )
            },
        )
    }

    private fun collectExprStreamAliases(expr: ExprAst): Set<String> {
        val out = mutableSetOf<String>()

        fun walk(e: ExprAst) {
            when (e) {
                is com.qkt.dsl.ast.StreamFieldRef -> out.add(e.stream)
                is com.qkt.dsl.ast.PositionRef -> out.add(e.stream)
                is com.qkt.dsl.ast.BinaryOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.UnaryOp -> walk(e.arg)
                is com.qkt.dsl.ast.CmpOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.Crosses -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.FuncCall -> e.args.forEach(::walk)
                is com.qkt.dsl.ast.IndicatorCall -> e.args.forEach(::walk)
                is com.qkt.dsl.ast.Aggregate -> walk(e.series)
                is com.qkt.dsl.ast.Between -> {
                    walk(e.v)
                    walk(e.lo)
                    walk(e.hi)
                }
                is com.qkt.dsl.ast.InList -> {
                    walk(e.v)
                    e.members.forEach(::walk)
                }
                is com.qkt.dsl.ast.CaseWhen -> {
                    e.branches.forEach { (c, b) ->
                        walk(c)
                        walk(b)
                    }
                    walk(e.elseExpr)
                }
                is com.qkt.dsl.ast.IsNull -> walk(e.expr)
                is com.qkt.dsl.ast.NumLit,
                is com.qkt.dsl.ast.BoolLit,
                is com.qkt.dsl.ast.StringLit,
                is com.qkt.dsl.ast.Ref,
                is com.qkt.dsl.ast.NowAccessor,
                is com.qkt.dsl.ast.CalendarWindow,
                is com.qkt.dsl.ast.SessionWindow,
                com.qkt.dsl.ast.LastTradingDayOfMonth,
                is com.qkt.dsl.ast.AccountRef,
                is com.qkt.dsl.ast.StreakRef,
                is com.qkt.dsl.ast.TradesRef,
                is com.qkt.dsl.ast.CooldownRef,
                is com.qkt.dsl.ast.StateAccessor,
                com.qkt.dsl.ast.StackEntryRef,
                com.qkt.dsl.ast.EntryQty,
                is com.qkt.dsl.ast.ExitRef,
                is com.qkt.dsl.ast.SequenceAccessor,
                -> Unit
            }
        }
        walk(expr)
        return out
    }
}
