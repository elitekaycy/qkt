package com.qkt.dsl.sets

import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CompiledStrategy

/**
 * The `FOR EACH m IN <set>` rules of a strategy over a symbol set whose members change during the run, kept as a
 * template and instantiated per member when it joins (runtime membership plan, step 3): one small strategy holding
 * the member's stream (`<set>.<symbol>`, the alias a fixed set's member gets), the strategy's other plain streams,
 * and the loop rules with the loop variable bound to the member. Its rules, indicators and state are the member's
 * alone; schedules, sequences and the strategy's other rules stay with the strategy itself.
 */
internal class SetMemberTemplate(
    private val ast: StrategyAst,
    val set: String,
) {
    private val decl = ast.streams.firstOrNull { it.broker == SET_BROKER && it.alias == set }
    private val loops = ast.rules.filterIsInstance<ForEachSet>().filter { it.set == set }

    init {
        requireNotNull(decl) { "'$set' is not a symbol set declared in SYMBOLS" }
        require(SymbolSetSpec.of(decl) !is SymbolSetSpec.Union) {
            "symbol set '$set' is a union; a union's members cannot change during a run"
        }
        require(loops.isNotEmpty()) { "symbol set '$set' has no FOR EACH rule to run per member" }
        val aggregated = loops.firstOrNull { SetFunctionLowering.setsNamed(it.template, setOf(set)).isNotEmpty() }
        require(aggregated == null) {
            "FOR EACH ${aggregated?.iterVar} IN $set: cross-member functions over a set whose members change are " +
                "not supported yet"
        }
    }

    /** The alias the member [symbol] (the venue's bare symbol) trades under. */
    fun alias(symbol: String): String = SetMemberAlias.of(set, symbol)

    /** The strategy that runs [symbol]'s instance: its stream, the plain streams, and the loop rules bound to it. */
    fun ast(symbol: String): StrategyAst {
        val streams = ast.streams.filter { it.broker != SET_BROKER || it.alias == set }
        val expanded = SymbolSetExpansion.expand(streams, loops) { _, _ -> listOf(symbol) }
        return ast.copy(
            streams = expanded.streams,
            rules = expanded.rules,
            schedules = emptyList(),
            sequences = emptyList(),
        )
    }

    /** [symbol]'s instance, compiled. */
    fun compile(symbol: String): CompiledStrategy = AstCompiler().compile(ast(symbol)) as CompiledStrategy
}
