package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall

fun abs(x: ExprAst): ExprAst = FuncCall("ABS", listOf(x))

fun sqrt(x: ExprAst): ExprAst = FuncCall("SQRT", listOf(x))

fun log(x: ExprAst): ExprAst = FuncCall("LOG", listOf(x))

fun exp(x: ExprAst): ExprAst = FuncCall("EXP", listOf(x))

fun pow(
    base: ExprAst,
    exponent: ExprAst,
): ExprAst = FuncCall("POW", listOf(base, exponent))

fun min(
    a: ExprAst,
    b: ExprAst,
    vararg rest: ExprAst,
): ExprAst = FuncCall("MIN", listOf(a, b) + rest.toList())

fun max(
    a: ExprAst,
    b: ExprAst,
    vararg rest: ExprAst,
): ExprAst = FuncCall("MAX", listOf(a, b) + rest.toList())
