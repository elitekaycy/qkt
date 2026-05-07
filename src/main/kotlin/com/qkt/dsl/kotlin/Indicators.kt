package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal

fun ema(
    series: ExprAst,
    period: Int,
): ExprAst = IndicatorCall("EMA", listOf(series, NumLit(BigDecimal(period))))

fun rsi(
    series: ExprAst,
    period: Int,
): ExprAst = IndicatorCall("RSI", listOf(series, NumLit(BigDecimal(period))))

fun atr(
    stream: StreamRef,
    period: Int,
): ExprAst = IndicatorCall("ATR", listOf(StreamFieldRef(stream.alias, "candle"), NumLit(BigDecimal(period))))
