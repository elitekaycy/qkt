package com.qkt.dsl.sets

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.compile.ExprTransform
import com.qkt.dsl.compile.rejectNonBooleanCondition
import com.qkt.dsl.parse.subst

/**
 * Lowers the cross-member functions ([SetFunction]) of a `FOR EACH m IN <set>` template over sets whose members are
 * fixed for the run: `SUM_OF(m.close, fx)` in the instance for member `fx.EURUSD` becomes
 * `SUM_OF.SET(fx.EURUSD.close, fx.XAUUSD.close, ...)`, the expression once per member with the loop variable bound
 * to it, like a lambda parameter. `RANK_OF` also takes this instance's own value first.
 */
internal object SetFunctionLowering {
    /** The sets among [sets] that a set function in [template] ranges over. */
    fun setsNamed(
        template: WhenThen,
        sets: Set<String>,
    ): Set<String> {
        val named = mutableSetOf<String>()
        walk(template) { call ->
            setCall(call)?.second?.takeIf { it in sets }?.let(named::add)
            call
        }
        return named
    }

    /**
     * [loop]'s template for its member [self], every set function lowered over [members] (set alias to member
     * aliases, in declaration order). Fails naming the rule when a call cannot mean anything per member.
     */
    fun lower(
        loop: ForEachSet,
        self: String,
        members: Map<String, List<String>>,
    ): WhenThen =
        walk(loop.template) { call ->
            val target = setCall(call)?.takeIf { it.second in members }
            if (target == null) call else lowerCall(loop, call, target.first, target.second, self, members)
        }

    private fun lowerCall(
        loop: ForEachSet,
        call: FuncCall,
        fn: SetFunction,
        set: String,
        self: String,
        members: Map<String, List<String>>,
    ): FuncCall {
        val v = loop.iterVar

        fun refuse(why: String): Nothing = throw SymbolSetError(loop.line, "${fn.name}(…, $set): $why")
        if (call.args.size != 2) refuse("takes one expression and the set, got ${call.args.size} arguments")
        val expr = call.args[0]
        if (containsLowered(expr)) refuse("a cross-member function cannot be nested inside another")
        if (subst(expr, v, PROBE) == expr) refuse("the expression must use the loop variable '$v', once per member")
        if (fn == SetFunction.COUNT_OF) {
            runCatching { rejectNonBooleanCondition(expr) }
                .onFailure { refuse("COUNT_OF counts the members where a test holds: compare the value") }
        }
        val terms = members.getValue(set).map { subst(expr, v, it) }
        return FuncCall(fn.lowered, if (fn.selfFirst) listOf(subst(expr, v, self)) + terms else terms)
    }

    /** A call to a set function and the name its last argument gives, or null for any other call. */
    private fun setCall(call: FuncCall): Pair<SetFunction, String>? {
        val fn = SetFunction.named(call.name) ?: return null
        val set = (call.args.lastOrNull() as? Ref)?.name ?: return null
        return fn to set
    }

    private fun containsLowered(expr: ExprAst): Boolean {
        var found = false
        transform { call -> call.also { found = found || SetFunction.lowered(it.name) != null } }.expr(expr)
        return found
    }

    private fun walk(
        template: WhenThen,
        onCall: (FuncCall) -> ExprAst,
    ): WhenThen {
        val transform = transform(onCall)
        return WhenThen(transform.expr(template.cond), transform.action(template.action), template.line)
    }

    private fun transform(onCall: (FuncCall) -> ExprAst): ExprTransform = ExprTransform(onFuncCall = onCall) { it }

    /** An alias no strategy can write, to test whether an expression mentions the loop variable. */
    private const val PROBE: String = "\u0000"
}
