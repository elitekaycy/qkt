package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.Window

val sinceOpen: Window = SinceOpen

fun sinceT(n: Int): Window = SinceTPast(n)

fun runMin(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.MIN, series, window)

fun runMax(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.MAX, series, window)

fun runMean(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.MEAN, series, window)

fun runSum(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.SUM, series, window)
