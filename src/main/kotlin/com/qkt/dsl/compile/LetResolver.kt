package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StreamFieldRef

/** Resolves bare identifiers to LET values or declared stream candle series. */
class LetResolver(
    lets: List<LetDecl>,
    private val streamAliases: Set<String> = emptySet(),
) {
    private val table: Map<String, ExprAst> = lets.associate { it.name to it.expr }

    init {
        require(table.size == lets.size) {
            "Duplicate LET name in: ${lets.map { it.name }}"
        }
    }

    private val transform = ExprTransform(onRef = ::onRef)

    /** LET names whose right-hand side is being inlined right now; a re-entry is a cycle. */
    private val resolving = ArrayDeque<String>()

    fun resolve(expr: ExprAst): ExprAst = transform.expr(expr)

    /** Inlines a LET's own right-hand side; a LET that (transitively) refers to itself is rejected here. */
    fun resolveDeclaration(let: LetDecl): ExprAst = inline(let.name, let.expr)

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
            table[ref.name]?.let { rhs -> inline(ref.name, rhs) }
                ?: if (ref.name in streamAliases) {
                    StreamFieldRef(ref.name, "candle")
                } else {
                    error("Unknown reference: ${ref.name}")
                }
        }
    }

    private fun inline(
        name: String,
        rhs: ExprAst,
    ): ExprAst {
        if (name in resolving) {
            val chain = (resolving.dropWhile { it != name } + name).joinToString(" -> ")
            error("LET '$name' refers to itself ($chain); a LET cannot depend on its own value")
        }
        resolving.addLast(name)
        try {
            return resolve(rhs)
        } finally {
            resolving.removeLast()
        }
    }
}
