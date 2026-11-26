package com.qkt.dsl.sets

import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.compile.CrossSections
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/**
 * The `FOR EACH m IN <set>` rules of a strategy over a symbol set whose members change during the run, kept as a
 * template and instantiated per member when it joins (runtime membership plan, step 3): one small strategy holding
 * the member's stream (`<set>.<symbol>`, the alias a fixed set's member gets), the strategy's other plain streams,
 * and the loop rules with the loop variable bound to the member. Its rules, indicators and state are the member's
 * alone; schedules, sequences and the strategy's other rules stay with the strategy itself. Each instance compiles
 * with the run's `PARAM` [overrides] and under its own name (`<strategy>.<set>.<symbol>`), so the order ids it mints
 * never collide with another member's or the strategy's. Its cross-member functions over the set read the
 * cross-section the set's running members share ([SetCrossSections]).
 */
internal class SetMemberTemplate(
    private val ast: StrategyAst,
    val set: String,
    private val overrides: Map<String, String> = emptyMap(),
) {
    private val decl = ast.streams.firstOrNull { it.broker == SET_BROKER && it.alias == set }
    private val loops = ast.rules.filterIsInstance<ForEachSet>().filter { it.set == set }

    init {
        requireNotNull(decl) { "'$set' is not a symbol set declared in SYMBOLS" }
        require(SymbolSetSpec.of(decl) !is SymbolSetSpec.Union) {
            "symbol set '$set' is a union; a union's members cannot change during a run"
        }
        require(loops.isNotEmpty()) { "symbol set '$set' has no FOR EACH rule to run per member" }
        val others = ast.streams.filter { it.broker == SET_BROKER && it.alias != set }.mapTo(HashSet()) { it.alias }
        val across = loops.firstNotNullOfOrNull { SetFunctionLowering.setsNamed(it.template, others).firstOrNull() }
        require(across == null) {
            "FOR EACH ${loops.first().iterVar} IN $set: a cross-member function in a loop over a set whose members " +
                "move ranges over that set only, not '$across'"
        }
    }

    /** Whether a rule over the set arms a `LATCH`, whose candles the session wires per stream at its start. */
    val usesLatch: Boolean get() = loops.any { it.template.action is Latch }

    /** The alias the member [symbol] (the venue's bare symbol) trades under. */
    fun alias(symbol: String): String = SetMemberAlias.of(set, symbol)

    /** The strategy that runs [symbol]'s instance: its stream, the plain streams, and the loop rules bound to it. */
    fun ast(symbol: String): StrategyAst {
        val streams = ast.streams.filter { it.broker != SET_BROKER || it.alias == set }
        var site = 0
        val lowered =
            loops.map { loop ->
                val template = SetFunctionLowering.lowerMoving(loop, alias(symbol)) { site++ }
                ForEachSet(loop.iterVar, loop.set, template, loop.line)
            }
        val expanded = SymbolSetExpansion.expand(streams, lowered) { _, _ -> listOf(symbol) }
        return ast.copy(
            streams = expanded.streams,
            rules = expanded.rules,
            schedules = emptyList(),
            sequences = emptyList(),
        )
    }

    /** [symbol]'s instance, compiled, its cross-member functions reading the set's shared [sections]. */
    fun compile(
        symbol: String,
        sections: CrossSections = SetCrossSections { emptyList() },
    ): CompiledStrategy =
        AstCompiler(sections).compile(ast(symbol).copy(name = "${ast.name}.$set.$symbol"), overrides)
            as CompiledStrategy

    /**
     * What [symbol]'s instance asks of its own stream: the closed bars it keeps (its indicators' retention, or its
     * warmup when larger) and its warmup, null when it declares none. Every member runs the same rules, so a run asks
     * this once and holds that much history for every candidate member.
     */
    fun streamNeeds(symbol: String): StreamNeeds {
        val instance = compile(symbol)
        val key = instance.declaredStreams.getValue(alias(symbol))
        val warmup = instance.perStreamWarmup[WarmupStream(key.qktSymbol, TimeWindow.parse(key.timeframe))]
        val bars = (warmup as? WarmupSpec.Bars)?.count ?: 0
        return StreamNeeds(maxOf(instance.retentionByKey[key] ?: 1, bars), warmup)
    }

    /** A member stream's [retention] (closed bars kept) and [warmup]. */
    data class StreamNeeds(
        val retention: Int,
        val warmup: WarmupSpec?,
    )
}
