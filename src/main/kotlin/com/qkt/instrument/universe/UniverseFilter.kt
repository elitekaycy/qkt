package com.qkt.instrument.universe

import com.qkt.common.SymbolCalendars
import com.qkt.instrument.InstrumentMeta

/** The comparison operators a filter clause may use, with their canonical spelling. */
enum class CompareOp(
    val token: String,
) {
    EQ("="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
}

/**
 * A boolean expression over [CatalogField]s: the `WHERE` part of a filtered symbol set, evaluated against one
 * instrument at a time. [canonical] is the one spelling the strategy's AST carries, so two filters that mean the
 * same thing read the same and parse back to equal filters.
 */
sealed interface UniverseFilter {
    val canonical: String

    /** True when [meta], as of [nowMs], passes this filter. */
    fun matches(
        meta: InstrumentMeta,
        nowMs: Long,
    ): Boolean

    /** `field <op> literal`; a field the instrument lacks compares false, so `!=` on it is false too. */
    data class Compare(
        val field: CatalogField,
        val op: CompareOp,
        val value: CatalogValue,
    ) : UniverseFilter {
        override val canonical: String get() = "${this.field.token} ${op.token} ${value.canonical}"

        override fun matches(
            meta: InstrumentMeta,
            nowMs: Long,
        ): Boolean {
            val actual = field.read(meta, nowMs) ?: return false
            val sameKind = (actual is CatalogValue.Num) == (value is CatalogValue.Num)
            if (!sameKind) return op == CompareOp.NE
            val cmp = CatalogValue.ordering.compare(actual, value)
            return when (op) {
                CompareOp.EQ -> cmp == 0
                CompareOp.NE -> cmp != 0
                CompareOp.LT -> cmp < 0
                CompareOp.LE -> cmp <= 0
                CompareOp.GT -> cmp > 0
                CompareOp.GE -> cmp >= 0
            }
        }
    }

    /** `field IN (a, b, ...)`: equal to any one of [values]. */
    data class OneOf(
        val field: CatalogField,
        val values: List<CatalogValue>,
    ) : UniverseFilter {
        override val canonical: String get() = "${this.field.token} IN (${values.joinToString(", ") { it.canonical }})"

        override fun matches(
            meta: InstrumentMeta,
            nowMs: Long,
        ): Boolean {
            val actual = field.read(meta, nowMs) ?: return false
            return values.any { CatalogValue.same(actual, it) }
        }
    }

    /** `field GLOB "pattern"`: the field's text matches a `*` glob, the matcher calendar rules use. */
    data class Glob(
        val field: CatalogField,
        val pattern: String,
    ) : UniverseFilter {
        private val regex = SymbolCalendars.globToRegex(pattern)

        override val canonical: String get() = "${this.field.token} GLOB ${CatalogValue.Str(pattern).canonical}"

        override fun matches(
            meta: InstrumentMeta,
            nowMs: Long,
        ): Boolean {
            val actual = field.read(meta, nowMs) ?: return false
            val text = if (actual is CatalogValue.Str) actual.value else (actual as CatalogValue.Num).canonical
            return regex.matches(text)
        }
    }

    data class And(
        val left: UniverseFilter,
        val right: UniverseFilter,
    ) : UniverseFilter {
        override val canonical: String get() = "(${left.canonical} AND ${right.canonical})"

        override fun matches(
            meta: InstrumentMeta,
            nowMs: Long,
        ): Boolean = left.matches(meta, nowMs) && right.matches(meta, nowMs)
    }

    data class Or(
        val left: UniverseFilter,
        val right: UniverseFilter,
    ) : UniverseFilter {
        override val canonical: String get() = "(${left.canonical} OR ${right.canonical})"

        override fun matches(
            meta: InstrumentMeta,
            nowMs: Long,
        ): Boolean = left.matches(meta, nowMs) || right.matches(meta, nowMs)
    }

    data class Not(
        val inner: UniverseFilter,
    ) : UniverseFilter {
        override val canonical: String get() = "NOT ${inner.canonical}"

        override fun matches(
            meta: InstrumentMeta,
            nowMs: Long,
        ): Boolean = !inner.matches(meta, nowMs)
    }
}
