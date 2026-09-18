package com.qkt.dsl.compile

import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotTPast

/**
 * Compiles a snapshot reference `<name>@<slot>` against the rule's symbol: a rolling
 * `T-n` read or a named slot read from the [SnapshotStore].
 */
internal object SnapshotRefCompiler {
    fun compile(
        ref: Ref,
        ruleAlias: String?,
    ): CompiledExpr {
        val kind =
            ref.snapshot
                ?: error("Bare Ref ${ref.name} should have been substituted by LetResolver")
        val sym = ruleAlias ?: error("Snapshot ref ${ref.name}@$kind requires rule symbol context")
        return when (kind) {
            is SnapshotTPast ->
                CompiledExpr { ctx ->
                    val v = ctx.snapshotStore.readRolling(sym, ref.name, kind.n)
                    if (v == null) Value.Undefined else Value.Num(v)
                }
            else ->
                CompiledExpr { ctx ->
                    val v = ctx.snapshotStore.readSlot(sym, ref.name, kind)
                    if (v == null) Value.Undefined else Value.Num(v)
                }
        }
    }
}
