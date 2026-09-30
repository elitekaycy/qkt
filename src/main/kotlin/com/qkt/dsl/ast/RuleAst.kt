package com.qkt.dsl.ast

sealed interface RuleAst {
    /**
     * 1-based source line of the rule's `WHEN` (a `FOR EACH` expansion keeps the macro's line);
     * 0 for a rule built by hand. Provenance only: it points diagnostics at the rule and is not
     * part of the rule's identity.
     */
    val line: Int
}

/**
 * `WHEN <cond> THEN <action>`. Equality, hashing and `toString` cover [cond] and [action] only,
 * so moving a rule down the file changes neither the strategy fingerprint nor rule comparison.
 */
class WhenThen(
    val cond: ExprAst,
    val action: ActionAst,
    override val line: Int = 0,
) : RuleAst {
    fun copy(
        cond: ExprAst = this.cond,
        action: ActionAst = this.action,
        line: Int = this.line,
    ): WhenThen = WhenThen(cond, action, line)

    override fun equals(other: Any?): Boolean = other is WhenThen && other.cond == cond && other.action == action

    override fun hashCode(): Int = 31 * cond.hashCode() + action.hashCode()

    override fun toString(): String = "WhenThen(cond=$cond, action=$action)"
}

sealed interface ActionAst

data class Buy(
    val stream: String,
    val opts: ActionOpts = ActionOpts(),
) : ActionAst

data class Sell(
    val stream: String,
    val opts: ActionOpts = ActionOpts(),
) : ActionAst

data class Close(
    val stream: String,
) : ActionAst

/**
 * Set an open position's size to a per-bar target, trimming or adding to reach it without
 * close+reopen. [target] reuses the `SizingAst` grammar and evaluates to a target magnitude
 * for the symbol's PRIMARY leg (`TO 0` flattens; no open primary is a no-op). [minStep] is the
 * anti-churn deadband — the smallest `|target - current|` worth acting on.
 * e.g. `RESIZE aud TO 0.01 / atr(aud.candle, 14)` scales exposure inversely to volatility.
 */
data class Resize(
    val stream: String,
    val target: SizingAst,
    val minStep: ExprAst? = null,
) : ActionAst

data object CloseAll : ActionAst

data class Cancel(
    val stream: String,
) : ActionAst

data object CancelAll : ActionAst

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class Log(
    val level: LogLevel,
    val messageFormat: String,
    val fields: Map<String, ExprAst>,
) : ActionAst

data class Block(
    val actions: List<ActionAst>,
) : ActionAst

data class OcoEntry(
    val leg1: ActionAst, // Buy or Sell
    val leg2: ActionAst, // Buy or Sell
) : ActionAst
