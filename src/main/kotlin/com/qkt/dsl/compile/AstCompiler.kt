package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext

class AstCompiler {
    fun compile(ast: StrategyAst): Strategy {
        val streamSymbols: Map<String, String> = ast.streams.associate { it.alias to it.symbol }
        val resolver = LetResolver(ast.lets)
        val bindings = IndicatorBinding.Bag()
        val exprCompiler = ExprCompiler(bindings)
        val actionCompiler = ActionCompiler(exprCompiler)
        val rules: List<CompiledRule> =
            ast.rules.map { rule ->
                require(rule is WhenThen) { "Only WHEN-THEN rules are supported in 11b" }
                val cond = exprCompiler.compile(resolver.resolve(rule.cond))
                val action = actionCompiler.compile(rule.action)
                CompiledRule(cond, action)
            }
        return CompiledStrategy(streamSymbols, bindings, rules)
    }
}

private class CompiledStrategy(
    private val streamSymbols: Map<String, String>,
    private val bindings: IndicatorBinding.Bag,
    private val rules: List<CompiledRule>,
) : Strategy {
    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (candle.symbol !in streamSymbols.values) return
        val ec = EvalContext(candle = candle, streamSymbols = streamSymbols, lets = emptyMap())
        bindings.updateAll(ec)
        for (rule in rules) {
            val sig = rule.fire(ec)
            if (sig != null) emit(sig)
        }
    }
}
