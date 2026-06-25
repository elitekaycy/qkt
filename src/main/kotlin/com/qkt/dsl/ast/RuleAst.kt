package com.qkt.dsl.ast

sealed interface RuleAst

data class WhenThen(
    val cond: ExprAst,
    val action: ActionAst,
) : RuleAst

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
