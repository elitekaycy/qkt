package com.qkt.execution

import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.ExitRelativeLimit
import com.qkt.dsl.ast.ExitRelativeStop
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Limit as AstLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.Stop as AstStop
import com.qkt.dsl.ast.StopLimit as AstStopLimit
import com.qkt.dsl.ast.StopStepAst
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.execution.OrderRequestEvidenceFixtures.STRATEGY
import com.qkt.execution.OrderRequestEvidenceFixtures.SYMBOL
import com.qkt.execution.OrderRequestEvidenceFixtures.TS
import java.math.BigDecimal

/** A stack request whose layers cover every sizing and DSL order-type variant. */
object OrderRequestEvidenceStackRequest {
    fun stackRequest(): OrderRequest.Stack =
        OrderRequest.Stack(
            id = "stack",
            symbol = SYMBOL,
            side = Side.BUY,
            quantity = BigDecimal("0.30"),
            plan =
                StackPlan(
                    layers =
                        listOf(
                            LayerSpec(
                                index = 0,
                                sizing = SizeQty(FuncCall("size\"fn", listOf(StringLit("a\nb")))),
                                orderType = com.qkt.dsl.ast.Market,
                                trigger = Immediate,
                                resolvedQuantity = BigDecimal("0.10"),
                            ),
                            LayerSpec(
                                index = 1,
                                sizing = SizePctBalance(NumLit(BigDecimal("0.01"))),
                                orderType =
                                    AstLimit(
                                        BinaryOp(
                                            BinOp.SUB,
                                            Ref("close", SnapshotTPast(2)),
                                            NumLit(BigDecimal("1.00")),
                                        ),
                                    ),
                                trigger = At(NumLit(BigDecimal("99.00")), StackDirection.BELOW),
                                resolvedQuantity = BigDecimal("0.20"),
                            ),
                            LayerSpec(
                                index = 2,
                                sizing = SizeNotional(NumLit(BigDecimal("100.00"))),
                                orderType =
                                    ExitRelativeLimit(
                                        DirRel(DirSense.WITH, NumLit(BigDecimal("2.00"))),
                                    ),
                                trigger = Immediate,
                                resolvedQuantity = null,
                            ),
                            LayerSpec(
                                index = 3,
                                sizing = SizePctEquity(NumLit(BigDecimal("0.01"))),
                                orderType = AstStop(NumLit(BigDecimal("98.00"))),
                                trigger = At(NumLit(BigDecimal("98.00")), StackDirection.BELOW),
                                resolvedQuantity = BigDecimal("0.10"),
                            ),
                            LayerSpec(
                                index = 4,
                                sizing = SizePositionFull("primary"),
                                orderType =
                                    ExitRelativeStop(
                                        DirRel(DirSense.AGAINST, NumLit(BigDecimal("2.00"))),
                                    ),
                                trigger = Immediate,
                                resolvedQuantity = BigDecimal("0.10"),
                            ),
                            LayerSpec(
                                index = 5,
                                sizing = SizeRiskAbs(NumLit(BigDecimal("25.00"))),
                                orderType =
                                    AstStopLimit(
                                        NumLit(BigDecimal("97.00")),
                                        NumLit(BigDecimal("96.75")),
                                    ),
                                trigger = At(NumLit(BigDecimal("97.00")), StackDirection.BELOW),
                                resolvedQuantity = BigDecimal("0.10"),
                            ),
                            LayerSpec(
                                index = 6,
                                sizing = SizeRiskFrac(NumLit(BigDecimal("0.01"))),
                                orderType = TrailingBy(NumLit(BigDecimal("2.00"))),
                                trigger = Immediate,
                                resolvedQuantity = BigDecimal("0.10"),
                            ),
                            LayerSpec(
                                index = 7,
                                sizing = SizeRiskFracOfBook(NumLit(BigDecimal("0.01"))),
                                orderType = TrailingPct(NumLit(BigDecimal("1.00"))),
                                trigger = At(NumLit(BigDecimal("96.00")), StackDirection.BELOW),
                                resolvedQuantity = BigDecimal("0.10"),
                            ),
                        ),
                    outerBracket =
                        BracketAst(
                            takeProfit = ChildRr(NumLit(BigDecimal("2.00"))),
                            stopLoss =
                                ChildBy(
                                    distance = NumLit(BigDecimal("4.00")),
                                    ratchet =
                                        SteppedStopAst(
                                            listOf(
                                                StopStepAst(
                                                    NumLit(BigDecimal("2.00")),
                                                    NumLit(BigDecimal.ZERO),
                                                ),
                                            ),
                                        ),
                                ),
                        ),
                    withinMillis = null,
                ),
            timeInForce = TimeInForce.GTC,
            timestamp = TS,
            strategyId = STRATEGY,
        )
}
