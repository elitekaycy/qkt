package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.sets.SetFunction
import com.qkt.dsl.sets.StrikeNeighbour

/**
 * Compiles a cross-member function over a set whose members move, in its moving form
 * ([SetFunction.moving]: the expression, the member's alias, the call's site). The member's own value of the
 * expression is posted to the set's [CrossSections] at every close of its stream, warmup replay included, by riding
 * the aggregate update pass that runs before any rule; the call then reduces the set's current members' latest values
 * when its rule runs, with the set semantics of [SetFunctionCompiler]: what a fixed set's call reads from the hub.
 * `NEXT_STRIKE` / `PREV_STRIKE` post alike and read the neighbouring strike's member instead.
 */
internal object MovingSetFunctionCompiler {
    fun compile(
        fn: SetFunction,
        call: FuncCall,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val (sections, member, site) = posted(fn.name, call, exprs)
        return CompiledExpr { ctx ->
            val section = sections.read(site)
            SetFunctionCompiler.reduce(fn, section.posted[member] ?: Value.Undefined, section.values)
        }
    }

    /** `NEXT_STRIKE` / `PREV_STRIKE` in moving form: the neighbouring strike's member's latest value. */
    fun compile(
        strike: StrikeNeighbour,
        call: FuncCall,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val (sections, member, site) = posted(strike.name, call, exprs)
        return CompiledExpr { ctx -> sections.neighbour(site, member, strike.step, ctx.nowMs()) }
    }

    /** Registers the posting of the member's own value and returns the set's sections, the member and the site. */
    private fun posted(
        name: String,
        call: FuncCall,
        exprs: ExprCompiler,
    ): Triple<CrossSections, String, Int> {
        val sections =
            requireNotNull(exprs.crossSections) {
                "$name(…) over a set whose members move runs only inside the set's member instances"
            }
        val member = (call.args[1] as StringLit).value
        val site = (call.args[2] as NumLit).value.toInt()
        val own = exprs.compile(call.args[0], member)
        val post = CompiledExpr { ctx -> own.evaluate(ctx).also { sections.post(site, member, it) } }
        exprs.aggregates.add(AggregateBinding(post, SinceTPast(1), AggregateState.sinceT(AggFn.SUM, 1), member))
        return Triple(sections, member, site)
    }
}
