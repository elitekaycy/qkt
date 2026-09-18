package com.qkt.dsl.compile

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.ExitRelativeLimit
import com.qkt.dsl.ast.ExitRelativeStop
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct

/**
 * The [ExprTransform] rewrite of an order's terms: its sizing, its order type (including the
 * exit-relative limit and stop prices) and a GTD time in force.
 */
internal class OrderSpecTransform(
    private val exprs: ExprTransform,
) {
    fun sizing(s: SizingAst): SizingAst =
        when (s) {
            is SizeQty -> SizeQty(exprs.expr(s.expr))
            is SizeNotional -> SizeNotional(exprs.expr(s.usd))
            is SizePctEquity -> SizePctEquity(exprs.expr(s.frac))
            is SizePctBalance -> SizePctBalance(exprs.expr(s.frac))
            is SizeRiskFrac -> SizeRiskFrac(exprs.expr(s.frac))
            is SizeRiskFracOfBook -> SizeRiskFracOfBook(exprs.expr(s.frac))
            is SizeRiskAbs -> SizeRiskAbs(exprs.expr(s.usd))
            is SizePositionFull -> s
        }

    fun orderType(o: OrderTypeAst): OrderTypeAst =
        when (o) {
            Market -> o
            is Limit -> Limit(exprs.expr(o.price))
            is ExitRelativeLimit ->
                ExitRelativeLimit(
                    DirRel(o.price.sense, exprs.expr(o.price.dist)),
                )
            is Stop -> Stop(exprs.expr(o.price))
            is ExitRelativeStop ->
                ExitRelativeStop(
                    DirRel(o.price.sense, exprs.expr(o.price.dist)),
                )
            is StopLimit -> StopLimit(exprs.expr(o.stopPrice), exprs.expr(o.limitPrice))
            is TrailingBy -> TrailingBy(exprs.expr(o.distance))
            is TrailingPct -> TrailingPct(exprs.expr(o.percent))
        }

    fun tif(t: TifAst): TifAst =
        when (t) {
            is Gtd -> Gtd(exprs.expr(t.until))
            Gtc, Ioc, Fok, Day -> t
        }
}
