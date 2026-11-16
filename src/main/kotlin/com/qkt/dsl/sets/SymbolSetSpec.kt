package com.qkt.dsl.sets

import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseException
import com.qkt.dsl.parse.TokenCursor
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.UniverseFilter

/**
 * What a symbol-set declaration says, carried in a [StreamDecl.symbol] under the [SET_BROKER] prefix as its
 * [canonical] text so a set adds no property to the AST (design §3.7). [parse] reads that text, and the DSL's own
 * `SYMBOLS` syntax, with the same grammar, so `qkt bot universe` and a strategy agree on what a spec means.
 */
sealed interface SymbolSetSpec {
    /** The one spelling of this spec; `parse(canonical)` is this spec again. */
    val canonical: String

    /** `VENUE:{A, B, ...}`: a hand-picked list of the venue's symbols. */
    data class Picked(
        val venue: String,
        val names: List<String>,
    ) : SymbolSetSpec {
        init {
            require(names.isNotEmpty()) { "a picked set needs at least one symbol" }
            require(names.toSet().size == names.size) { "a picked set lists each symbol once: $names" }
        }

        override val canonical: String get() = "$venue:{${names.joinToString(", ")}}"
    }

    /** `VENUE:* WHERE <filter> TOP <n> BY <field> [ASC|DESC]`: the venue's catalog, filtered and ranked. */
    data class Filtered(
        val venue: String,
        val filter: UniverseFilter,
        val top: Int,
        val by: CatalogField,
        val descending: Boolean = true,
    ) : SymbolSetSpec {
        init {
            require(top > 0) { "TOP must be a positive count: $top" }
        }

        override val canonical: String
            get() =
                "$venue:* WHERE ${filter.canonical} TOP $top BY ${by.token} ${if (descending) "DESC" else "ASC"}"
    }

    /** `a + b`: the members of sets declared above, each keeping its own venue. */
    data class Union(
        val operands: List<String>,
    ) : SymbolSetSpec {
        init {
            require(operands.size >= 2) { "a union needs at least two sets: $operands" }
        }

        override val canonical: String get() = operands.joinToString(" + ")
    }

    companion object {
        /** The spec [text] spells, in the `SYMBOLS` grammar; throws [IllegalArgumentException] naming what is wrong. */
        fun parse(text: String): SymbolSetSpec {
            val tokens = runCatching { Lexer(text).tokenize() }.getOrElse { throw IllegalArgumentException(it.message) }
            val cursor = TokenCursor(tokens)
            return try {
                SetSpecParser(cursor).parseStandalone()
            } catch (e: ParseException) {
                throw IllegalArgumentException("symbol set '$text': ${e.error.message}")
            }
        }

        /** The spec [decl] declares, or null when it is an ordinary stream. */
        fun of(decl: StreamDecl): SymbolSetSpec? = if (decl.broker == SET_BROKER) parse(decl.symbol) else null
    }
}
