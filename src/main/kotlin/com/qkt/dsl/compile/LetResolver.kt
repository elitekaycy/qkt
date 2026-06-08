package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.Ref

class LetResolver(
    lets: List<LetDecl>,
) {
    private val table: Map<String, ExprAst> = lets.associate { it.name to it.expr }

    init {
        require(table.size == lets.size) {
            "Duplicate LET name in: ${lets.map { it.name }}"
        }
    }

    private val transform = ExprTransform(::onRef)

    fun resolve(expr: ExprAst): ExprAst = transform.expr(expr)

    /**
     * Inline LET references inside an action's expressions — the action analogue of [resolve] for
     * conditions. Needed because actions carry LET-bound distances (e.g. `LATCH gold OFFSET wire …
     * RETRACE near`, `STOP LOSS AGAINST sl`), and those refs must become literals before the action
     * is compiled. Snapshot/rolling LET refs pass through untouched (resolved later against the
     * snapshot store).
     */
    fun resolve(action: ActionAst): ActionAst = transform.action(action)

    private fun onRef(ref: Ref): ExprAst {
        if (ref.name == com.qkt.dsl.kotlin.SYMBOL_PLACEHOLDER_NAME) {
            error("SYMBOL placeholder is only valid inside DEFAULTS block")
        }
        return if (ref.snapshot != null) {
            if (!table.containsKey(ref.name)) error("Unknown LET reference: ${ref.name}")
            ref
        } else {
            resolve(table[ref.name] ?: error("Unknown reference: ${ref.name}"))
        }
    }
}
