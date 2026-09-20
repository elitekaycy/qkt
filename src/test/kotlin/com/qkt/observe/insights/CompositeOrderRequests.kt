package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Limit as AstLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.execution.At
import com.qkt.execution.ExpiryAction
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.StackPlan
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.observe.insights.SingleLegOrderRequests.market
import java.math.BigDecimal
import java.time.Instant

/** One sample of every composite [OrderRequest] subtype, built over single-leg markets. */
internal object CompositeOrderRequests {
    fun samples(): List<OrderRequest> =
        listOf(
            OrderRequest.StandaloneOCO(
                "oco",
                "XAUUSD",
                Side.BUY,
                BigDecimal("0.10"),
                market("oco-a"),
                market("oco-b", Side.SELL),
                TimeInForce.GTC,
                1L,
                "latch",
            ),
            OrderRequest.OTO(
                "oto",
                "XAUUSD",
                Side.BUY,
                BigDecimal("0.10"),
                market("oto-parent"),
                listOf(market("oto-child", Side.SELL)),
                TimeInForce.GTC,
                1L,
                "latch",
            ),
            OrderRequest.Bracket(
                id = "br",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry = market("br-entry"),
                takeProfit = BigDecimal("2360"),
                stopLoss = StopLossSpec.ArmedTrail(BigDecimal("8"), BigDecimal("12")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "latch",
                takeProfitAst = ChildRr(NumLit(BigDecimal("2"))),
                stopLossAst = ChildArmedTrail(NumLit(BigDecimal("8")), NumLit(BigDecimal("12"))),
            ),
            OrderRequest.ScaleOut(
                id = "scale",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.10"),
                basis = market("scale-basis"),
                legs = listOf(ScaleOutLeg(BigDecimal("2360"), BigDecimal("0.5"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "latch",
            ),
            OrderRequest.TimeExit(
                id = "time",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.10"),
                target = market("time-target"),
                deadline = Instant.ofEpochMilli(1718000060000L),
                onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "latch",
            ),
            OrderRequest.Stack(
                id = "stack",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.30"),
                plan =
                    StackPlan(
                        layers =
                            listOf(
                                LayerSpec(
                                    0,
                                    SizeQty(NumLit(BigDecimal("0.10"))),
                                    com.qkt.dsl.ast.Market,
                                    Immediate,
                                    BigDecimal("0.10"),
                                ),
                                LayerSpec(
                                    1,
                                    SizeQty(NumLit(BigDecimal("0.20"))),
                                    AstLimit(NumLit(BigDecimal("2345"))),
                                    At(NumLit(BigDecimal("2345")), StackDirection.BELOW),
                                    BigDecimal("0.20"),
                                ),
                            ),
                        outerBracket =
                            com.qkt.dsl.ast.BracketAst(
                                takeProfit = ChildRr(NumLit(BigDecimal("2"))),
                                stopLoss = ChildBy(NumLit(BigDecimal("10"))),
                            ),
                        withinMillis = 60_000L,
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "latch",
            ),
        )
}
