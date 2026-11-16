package com.qkt.dsl.compile

import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.sets.SetFunction
import com.qkt.dsl.stdlib.FuncRegistry

/**
 * Compiles a stdlib function call (`abs(x)`, `max(a, b)`, ...) looked up in [FuncRegistry]. Any
 * non-numeric argument makes the call [Value.Undefined]. A cross-member function already lowered over a
 * set's members compiles with set semantics ([SetFunctionCompiler]).
 */
internal object FuncCallCompiler {
    fun compile(
        call: FuncCall,
        ruleAlias: String?,
        exprs: ExprCompiler,
    ): CompiledExpr {
        val setFunction = SetFunction.lowered(call.name)
        if (setFunction != null) return SetFunctionCompiler.compile(setFunction, call, ruleAlias, exprs)
        require(FuncRegistry.has(call.name)) {
            if (SetFunction.named(call.name) == null) {
                "Unknown function: ${call.name}"
            } else {
                "${call.name}(expr, set) is a cross-member function: use it inside FOR EACH <m> IN <set>, naming a " +
                    "symbol set declared in SYMBOLS as its last argument and the loop variable in expr"
            }
        }
        val args = call.args.map { exprs.compile(it, ruleAlias) }
        return CompiledExpr { ctx ->
            // One pre-sized list instead of two intermediate .map lists per evaluation.
            val nums = ArrayList<java.math.BigDecimal>(args.size)
            var undefined = false
            for (i in args.indices) {
                val v = args[i].evaluate(ctx)
                if (v is Value.Num) nums.add(v.v) else undefined = true
            }
            if (undefined) {
                Value.Undefined
            } else {
                val result = FuncRegistry.invoke(call.name, nums)
                if (result == null) Value.Undefined else Value.Num(result)
            }
        }
    }
}
