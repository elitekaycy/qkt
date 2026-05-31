package com.qkt.indicators

import java.math.BigDecimal

/**
 * Composable boolean condition over [IndicatorOutput] values. Used by the Kotlin
 * DSL builders to assemble strategy conditions (`ema(9) gt ema(21) and rsi(14) gt 30`).
 *
 * Every operand is sampled fresh on [evaluate]; the rule is stateless and safe to
 * keep at the strategy level. Indicators that haven't warmed up yet return null and
 * the rule evaluates to `false` — the strategy stays silent until they fill in.
 */
sealed class Rule {
    /** Evaluate this rule against the current indicator values. */
    abstract fun evaluate(): Boolean

    /** Logical AND. */
    infix fun and(other: Rule): Rule = And(this, other)

    /** Logical OR. */
    infix fun or(other: Rule): Rule = Or(this, other)

    /** Logical NOT. */
    operator fun not(): Rule = Not(this)

    data class Over(
        val left: IndicatorOutput,
        val right: IndicatorOutput,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            val r = right.value() ?: return false
            return l > r
        }
    }

    data class Under(
        val left: IndicatorOutput,
        val right: IndicatorOutput,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            val r = right.value() ?: return false
            return l < r
        }
    }

    data class Eq(
        val left: IndicatorOutput,
        val right: IndicatorOutput,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            val r = right.value() ?: return false
            return l.compareTo(r) == 0
        }
    }

    data class OverThreshold(
        val left: IndicatorOutput,
        val threshold: BigDecimal,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            return l > threshold
        }
    }

    data class UnderThreshold(
        val left: IndicatorOutput,
        val threshold: BigDecimal,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            return l < threshold
        }
    }

    data class And(
        val a: Rule,
        val b: Rule,
    ) : Rule() {
        override fun evaluate(): Boolean = a.evaluate() && b.evaluate()
    }

    data class Or(
        val a: Rule,
        val b: Rule,
    ) : Rule() {
        override fun evaluate(): Boolean = a.evaluate() || b.evaluate()
    }

    data class Not(
        val r: Rule,
    ) : Rule() {
        override fun evaluate(): Boolean = !r.evaluate()
    }
}

infix fun IndicatorOutput.gt(other: IndicatorOutput): Rule = Rule.Over(this, other)

infix fun IndicatorOutput.lt(other: IndicatorOutput): Rule = Rule.Under(this, other)

infix fun IndicatorOutput.eq(other: IndicatorOutput): Rule = Rule.Eq(this, other)

infix fun IndicatorOutput.gt(threshold: BigDecimal): Rule = Rule.OverThreshold(this, threshold)

infix fun IndicatorOutput.lt(threshold: BigDecimal): Rule = Rule.UnderThreshold(this, threshold)
