package com.qkt.dsl.sets

import com.qkt.dsl.ast.StreamDecl

/**
 * The alias a set member's stream gets: `<set>.<symbol>` (`fx.EURUSD`). `.` is not an identifier character, so a
 * member alias can never collide with one a strategy writes, and the set a stream belongs to is read back from its
 * alias alone ([setOf]), which is how set caps find their members without the AST carrying a membership table.
 */
object SetMemberAlias {
    const val SEPARATOR: Char = '.'

    /** The member alias for [symbol] (the venue's bare symbol) of the set [set]. */
    fun of(
        set: String,
        symbol: String,
    ): String = "$set$SEPARATOR$symbol"

    /** The set [alias] belongs to, or null when it is not a member alias. */
    fun setOf(alias: String): String? = alias.substringBefore(SEPARATOR, "").takeIf { it.isNotEmpty() }

    /** Each set's member qkt symbols among [streams], by set alias; a strategy without sets maps to nothing. */
    fun membersOf(streams: List<StreamDecl>): Map<String, Set<String>> =
        streams
            .mapNotNull { decl -> setOf(decl.alias)?.let { set -> set to decl.qktSymbol } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, symbols) -> symbols.toSet() }
}
