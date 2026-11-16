package com.qkt.instrument.universe

import java.math.BigDecimal

/**
 * One value a catalog field or a filter literal holds: a number or a word. A filter compares numbers
 * numerically and words case-insensitively; a number against a word is never equal.
 */
sealed interface CatalogValue {
    /** The value as it is spelled in a canonical filter: a plain number, or a double-quoted string. */
    val canonical: String

    data class Num(
        val value: BigDecimal,
    ) : CatalogValue {
        override val canonical: String get() = value.toPlainString()
    }

    data class Str(
        val value: String,
    ) : CatalogValue {
        override val canonical: String get() = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    companion object {
        /**
         * Orders two values for `TOP n BY`: numbers numerically, words alphabetically ignoring case, and a
         * number before a word so a mixed column still sorts the same way every run.
         */
        val ordering: Comparator<CatalogValue> =
            Comparator { a, b ->
                when {
                    a is Num && b is Num -> a.value.compareTo(b.value)
                    a is Str && b is Str -> a.value.compareTo(b.value, ignoreCase = true)
                    a is Num -> -1
                    else -> 1
                }
            }

        /** Equality as a filter sees it: numbers by value (`100` equals `100.0`), words ignoring case. */
        fun same(
            a: CatalogValue,
            b: CatalogValue,
        ): Boolean =
            when {
                a is Num && b is Num -> a.value.compareTo(b.value) == 0
                a is Str && b is Str -> a.value.equals(b.value, ignoreCase = true)
                else -> false
            }
    }
}
