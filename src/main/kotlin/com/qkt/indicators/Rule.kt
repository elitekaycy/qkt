package com.qkt.indicators

import java.math.BigDecimal

sealed class Rule {
    abstract fun evaluate(): Boolean

    infix fun and(other: Rule): Rule = And(this, other)

    infix fun or(other: Rule): Rule = Or(this, other)

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
