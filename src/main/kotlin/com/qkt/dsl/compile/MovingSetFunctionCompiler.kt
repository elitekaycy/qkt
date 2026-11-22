package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.sets.SetFunction

/**
 * Compiles a cross-member function over a set whose members move, in its moving form
 * ([SetFunction.moving]: the expression, the member's alias, the call's site). The member's own value of the
 * expression is posted to the set's [CrossSections] at every close of its stream, warmup replay included, by riding
 * the aggregate update pass that runs before any rule; the call then reduces the set's current members' latest values
 * when its rule runs, with the set semantics of [SetFunctionCompiler]: what a fixed set's call reads from the hub.
 */
internal object MovingSetFunctionCompiler {
    fun compile(
        fn: SetFunction,
        call: FuncCall,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val sections =
            requireNotNull(exprs.crossSections) {
                "${fn.name}(…) over a set whose members move runs only inside the set's member instances"
            }
        val member = (call.args[1] as StringLit).value
        val site = (call.args[2] as NumLit).value.toInt()
        val own = exprs.compile(call.args[0], member)
        val post = CompiledExpr { ctx -> own.evaluate(ctx).also { sections.post(site, member, it) } }
        exprs.aggregates.add(AggregateBinding(post, SinceTPast(1), AggregateState.sinceT(AggFn.SUM, 1), member))
        return CompiledExpr { ctx ->
            val section = sections.read(site)
            SetFunctionCompiler.reduce(fn, section.posted[member] ?: Value.Undefined, section.values)
        }
    }
}
