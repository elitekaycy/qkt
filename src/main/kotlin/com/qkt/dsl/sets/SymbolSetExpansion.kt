package com.qkt.dsl.sets

import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.parse.substituteIterVar

/** Why a set could not be expanded, at the rule's source [line] (0 when it is the declaration's fault). */
class SymbolSetError(
    val line: Int,
    message: String,
) : RuntimeException(message)

/**
 * Replaces symbol-set declarations by one stream per member and `FOR EACH ... IN <set>` rules by one rule per
 * member, so everything after the parser sees plain streams and plain rules (design §3.8). A [Resolver] names a
 * venue set's members, or answers null to leave that set, its unions and its loops in place for a later pass with a
 * catalog: the parser resolves picked lists this way, a backtest resolves filtered sets at start.
 */
object SymbolSetExpansion {
    /** The bare symbols of a picked or filtered set, or null when this resolver cannot name them yet. */
    fun interface Resolver {
        fun members(
            alias: String,
            spec: SymbolSetSpec,
        ): List<String>?
    }

    /** Resolves picked lists, which need no catalog, and leaves filtered sets alone. */
    val PICKED: Resolver = Resolver { _, spec -> (spec as? SymbolSetSpec.Picked)?.names }

    data class Expanded(
        val streams: List<StreamDecl>,
        val rules: List<RuleAst>,
    )

    fun expand(
        streams: List<StreamDecl>,
        rules: List<RuleAst>,
        resolver: Resolver,
    ): Expanded {
        val sets = streams.filter { it.broker == SET_BROKER }.associateBy { it.alias }
        val members = mutableMapOf<String, List<StreamDecl>>()
        for (decl in sets.values) {
            val spec = SymbolSetSpec.parse(decl.symbol)
            val resolved =
                when (spec) {
                    is SymbolSetSpec.Union -> {
                        // An operand expanded by an earlier pass is already present as member streams.
                        val parts = spec.operands.map { members[it] ?: expandedMembers(streams, it) }
                        if (parts.all { it != null }) {
                            parts.flatMap { it.orEmpty() }.distinctBy { it.qktSymbol }
                        } else {
                            null
                        }
                    }
                    is SymbolSetSpec.Picked ->
                        resolver.members(decl.alias, spec)?.map { memberStream(decl, spec.venue, it) }
                    is SymbolSetSpec.Filtered ->
                        resolver.members(decl.alias, spec)?.map { memberStream(decl, spec.venue, it) }
                }
            if (resolved != null) members[decl.alias] = resolved
        }
        val outStreams =
            streams.flatMap { decl ->
                val resolved = members[decl.alias]
                when {
                    decl.broker != SET_BROKER -> listOf(decl)
                    resolved == null -> listOf(decl)
                    SymbolSetSpec.of(decl) is SymbolSetSpec.Union -> emptyList()
                    else -> resolved
                }
            }
        val outRules =
            rules.flatMap { rule ->
                if (rule !is ForEachSet) return@flatMap listOf(rule)
                val resolved = members[rule.set]
                when {
                    rule.set !in sets ->
                        throw SymbolSetError(
                            rule.line,
                            "FOR EACH ${rule.iterVar} IN ${rule.set}: '${rule.set}' is not a symbol set declared " +
                                "in SYMBOLS (a list of streams needs brackets: [${rule.set}])",
                        )
                    else -> instantiate(rule, resolved, sets.keys, members)
                }
            }
        return Expanded(outStreams, outRules)
    }

    /**
     * One rule per member of [loop]'s set ([resolved], null while unknown), its set functions lowered over the members
     * of the sets they name; [loop] itself while its set is unresolved. A set function's set resolves in the same
     * pass as the loop's (both picked, or both filtered), since the loop's members are fixed once it expands.
     */
    private fun instantiate(
        loop: ForEachSet,
        resolved: List<StreamDecl>?,
        sets: Set<String>,
        members: Map<String, List<StreamDecl>>,
    ): List<RuleAst> {
        val named = SetFunctionLowering.setsNamed(loop.template, sets)
        val apart = named.firstOrNull { (it in members) != (resolved != null) }
        if (apart != null) {
            throw SymbolSetError(
                loop.line,
                "FOR EACH ${loop.iterVar} IN ${loop.set}: '$apart' and '${loop.set}' resolve at different times (a " +
                    "picked list at parse, a filtered set at start), so a cross-member function over '$apart' " +
                    "needs a loop over a set of the same kind",
            )
        }
        if (resolved == null) return listOf(loop)
        val aliases = members.filterKeys { it in named }.mapValues { (_, decls) -> decls.map { it.alias } }
        return resolved.map { member ->
            val template =
                if (named.isEmpty()) {
                    loop.template
                } else {
                    SetFunctionLowering.lower(
                        loop,
                        member.alias,
                        aliases,
                    )
                }
            substituteIterVar(template, loop.iterVar, member.alias)
        }
    }

    private fun expandedMembers(
        streams: List<StreamDecl>,
        set: String,
    ): List<StreamDecl>? = streams.filter { SetMemberAlias.setOf(it.alias) == set }.takeIf { it.isNotEmpty() }

    private fun memberStream(
        set: StreamDecl,
        venue: String,
        symbol: String,
    ): StreamDecl =
        StreamDecl(
            alias = SetMemberAlias.of(set.alias, symbol),
            broker = venue,
            symbol = symbol,
            timeframe = set.timeframe,
            warmupBars = set.warmupBars,
        )
}
