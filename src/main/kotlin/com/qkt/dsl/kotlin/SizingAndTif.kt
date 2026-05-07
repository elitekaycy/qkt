package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.TifAst

fun usdNotional(usd: ExprAst): SizingAst = SizeNotional(usd)

fun riskAbs(amount: ExprAst): SizingAst = SizeRiskAbs(amount)

fun positionFull(stream: StreamRef): SizingAst = SizePositionFull(stream.alias)

val gtc: TifAst = Gtc
val ioc: TifAst = Ioc
val fok: TifAst = Fok
val day: TifAst = Day
