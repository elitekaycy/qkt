package com.qkt.execution

import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.TimeTightenAst
import com.qkt.execution.OrderRequestEvidenceFixtures.QTY
import com.qkt.execution.OrderRequestEvidenceFixtures.STRATEGY
import com.qkt.execution.OrderRequestEvidenceFixtures.SYMBOL
import com.qkt.execution.OrderRequestEvidenceFixtures.TS
import com.qkt.execution.OrderRequestEvidenceFixtures.market
import com.qkt.execution.OrderRequestEvidenceStackRequest.stackRequest
import java.math.BigDecimal
import java.time.Instant

/** One request of every sealed [OrderRequest] type, for evidence-payload coverage tests. */
object OrderRequestEvidenceRequests {
    fun allRequests(): List<OrderRequest> {
        val market = market("market").copy(closesTicket = "ticket-1", closesLegId = "leg-1", partialClose = true)
        val limit =
            OrderRequest.Limit(
                id = "limit",
                symbol = SYMBOL,
                side = Side.BUY,
                quantity = QTY,
                limitPrice = BigDecimal("99.50"),
                timeInForce = TimeInForce.GTD,
                timestamp = TS,
                strategyId = STRATEGY,
                expiresAt = TS + 60_000L,
            )
        val stop =
            OrderRequest.Stop(
                id = "stop",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                stopPrice = BigDecimal("95.00"),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            )
        return listOf(
            market,
            limit,
            stop,
            OrderRequest.StopLimit(
                id = "stop-limit",
                symbol = SYMBOL,
                side = Side.BUY,
                quantity = QTY,
                stopPrice = BigDecimal("101.00"),
                limitPrice = BigDecimal("101.25"),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.IfTouched(
                id = "if-touched",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                triggerPrice = BigDecimal("110.00"),
                onTrigger = TriggerType.LIMIT,
                limitPrice = BigDecimal("109.75"),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.TrailingStop(
                id = "trailing-stop",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                trailAmount = BigDecimal("2.00"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.ArmedTrailingStop(
                id = "armed-trailing-stop",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                entryPrice = BigDecimal("100.00"),
                trailDistance = BigDecimal("2.00"),
                mfeThreshold = BigDecimal("3.00"),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.SteppedStop(
                id = "stepped-stop",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                entryPrice = BigDecimal("100.00"),
                initialDistance = BigDecimal("4.00"),
                steps =
                    listOf(
                        StopLossSpec.Step(BigDecimal("2.00"), BigDecimal.ZERO),
                        StopLossSpec.Step(BigDecimal("4.00"), BigDecimal("1.00")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.TimeTighteningStop(
                id = "time-tightening-stop",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                entryPrice = BigDecimal("100.00"),
                initialDistance = BigDecimal("4.00"),
                tightenBy = BigDecimal("0.50"),
                intervalMs = 5_000L,
                floorDistance = BigDecimal("1.00"),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.TrailingStopLimit(
                id = "trailing-stop-limit",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                trailAmount = BigDecimal("1.00"),
                trailMode = TrailMode.PERCENT,
                limitOffset = BigDecimal("0.25"),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.StandaloneOCO(
                id = "oco",
                symbol = SYMBOL,
                side = Side.BUY,
                quantity = QTY,
                leg1 = limit.copy(id = "oco-limit"),
                leg2 = stop.copy(id = "oco-stop"),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.OTO(
                id = "oto",
                symbol = SYMBOL,
                side = Side.BUY,
                quantity = QTY,
                parent = market("oto-parent"),
                children = listOf(limit.copy(id = "oto-child")),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.Bracket(
                id = "bracket",
                symbol = SYMBOL,
                side = Side.BUY,
                quantity = QTY,
                entry = market("bracket-entry"),
                takeProfit = BigDecimal("108.00"),
                stopLoss =
                    StopLossSpec.TimeTighten(
                        initialDistance = BigDecimal("4.00"),
                        tightenBy = BigDecimal("0.50"),
                        intervalMs = 5_000L,
                        floorDistance = BigDecimal("1.00"),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
                takeProfitAst = ChildRr(NumLit(BigDecimal("2.00"))),
                stopLossAst =
                    ChildBy(
                        NumLit(BigDecimal("4.00")),
                        TimeTightenAst(
                            NumLit(BigDecimal("0.50")),
                            DurationAst(5_000L),
                            NumLit(BigDecimal("1.00")),
                        ),
                    ),
            ),
            OrderRequest.ScaleOut(
                id = "scale-out",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                basis = market("scale-basis"),
                legs =
                    listOf(
                        ScaleOutLeg(BigDecimal("104.00"), BigDecimal("0.50")),
                        ScaleOutLeg(BigDecimal("108.00"), BigDecimal("0.50")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            OrderRequest.TimeExit(
                id = "time-exit",
                symbol = SYMBOL,
                side = Side.SELL,
                quantity = QTY,
                target = market("time-target"),
                deadline = Instant.ofEpochMilli(TS + 120_000L),
                onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
                strategyId = STRATEGY,
            ),
            stackRequest(),
        )
    }
}
