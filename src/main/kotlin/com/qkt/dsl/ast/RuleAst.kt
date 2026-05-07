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

data object CloseAll : ActionAst

data class Cancel(
    val stream: String,
) : ActionAst

data object CancelAll : ActionAst

data class Log(
    val message: String,
) : ActionAst
