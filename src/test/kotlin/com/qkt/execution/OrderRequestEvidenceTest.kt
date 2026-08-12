package com.qkt.execution

import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExitRelativeLimit
import com.qkt.dsl.ast.ExitRelativeStop
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Limit as AstLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.Stop as AstStop
import com.qkt.dsl.ast.StopLimit as AstStopLimit
import com.qkt.dsl.ast.StopStepAst
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TimeTightenAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestEvidenceTest {
    @Test
    fun `payload covers every sealed order request with a fixed type label`() {
        val requests = allRequests()
        val runtimeTypes =
            OrderRequest::class.java.permittedSubclasses
                .map { it.simpleName }
                .toSet()
        val payloadTypes = requests.map { OrderRequestEvidence.payload(it).getValue("orderType") }.toSet()

        assertThat(requests.map { it::class.java.simpleName }.toSet()).isEqualTo(runtimeTypes)
        assertThat(payloadTypes).isEqualTo(runtimeTypes)
        assertThat(requests).hasSize(16)

        val byType = requests.associateBy { OrderRequestEvidence.payload(it).getValue("orderType") }
        assertThat(OrderRequestEvidence.payload(byType.getValue("SteppedStop"))["steps"])
            .isInstanceOf(List::class.java)
        assertThat(OrderRequestEvidence.payload(byType.getValue("TimeTighteningStop")))
            .containsEntry("intervalMs", 5_000L)
            .containsEntry("floorDistance", BigDecimal("1.00"))
        assertThat(OrderRequestEvidence.payload(byType.getValue("Market")))
            .containsEntry("closesTicket", "ticket-1")
            .containsEntry("closesLegId", "leg-1")
            .containsEntry("partialClose", true)
    }

    @Test
    fun `json is deterministic retains nulls escapes strings and renders plain decimals`() {
        val request =
            OrderRequest.Market(
                id = "order\"\\\n\r\t\b\u000c\u0001",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("1E+3"),
                timeInForce = TimeInForce.GTC,
                timestamp = 123L,
            )

        val first = OrderRequestEvidence.toJson(request)
        val second = OrderRequestEvidence.toJson(request)
        val parsed = Json.parseToJsonElement(first).jsonObject

        assertThat(first).isEqualTo(second)
        assertThat(first).startsWith("{\"orderId\":")
        assertThat(first).contains("\"qty\":1000")
        assertThat(first).doesNotContain("1E+3")
        assertThat(first).contains("\"strategyId\":null")
        assertThat(first).contains("\"expiresAt\":null")
        assertThat(first).contains("\"closesTicket\":null")
        assertThat(first).contains("\"closesLegId\":null")
        assertThat(first).contains("\\\"", "\\\\", "\\n", "\\r", "\\t", "\\b", "\\f", "\\u0001")
        assertThat(parsed.getValue("orderId").jsonPrimitive.content).isEqualTo(request.id)
    }

    @Test
    fun `composites and retained stack DSL are structural and recursive`() {
        val requests = allRequests().associateBy { it::class.java.simpleName }

        val oco = OrderRequestEvidence.payload(requests.getValue("StandaloneOCO"))
        assertThat((oco.getValue("leg1") as Map<*, *>)["orderType"]).isEqualTo("Limit")
        assertThat((oco.getValue("leg2") as Map<*, *>)["orderType"]).isEqualTo("Stop")

        val bracket = OrderRequestEvidence.payload(requests.getValue("Bracket"))
        assertThat((bracket.getValue("entry") as Map<*, *>)["orderType"]).isEqualTo("Market")
        assertThat((bracket.getValue("stopLoss") as Map<*, *>)["type"]).isEqualTo("TimeTighten")
        assertThat((bracket.getValue("takeProfitAst") as Map<*, *>)["type"]).isEqualTo("Rr")

        val stackJson = Json.parseToJsonElement(OrderRequestEvidence.toJson(requests.getValue("Stack"))).jsonObject
        val stackText = stackJson.toString()
        assertThat(stackText)
            .contains("\"type\":\"FuncCall\"")
            .contains("\"type\":\"BinaryOp\"")
            .contains("\"snapshot\":{\"type\":\"TPast\",\"n\":2}")
            .contains("\"type\":\"Stepped\"")
        assertThat(stackText).doesNotContain("FuncCall(name=")
    }

    @Test
    fun `nested sealed DSL and stop surfaces have complete structural evidence`() {
        val stack = stackRequest()
        val sizingTypes =
            stack.plan.layers
                .map { it.sizing::class.java.simpleName }
                .toSet()
        val dslOrderTypes =
            stack.plan.layers
                .map { it.orderType::class.java.simpleName }
                .toSet()

        assertThat(sizingTypes).isEqualTo(permittedNames(SizingAst::class.java))
        assertThat(dslOrderTypes).isEqualTo(permittedNames(OrderTypeAst::class.java))

        val stackPayload = OrderRequestEvidence.payload(stack)

        @Suppress("UNCHECKED_CAST")
        val layers = stackPayload.getValue("stackLayers") as List<Map<String, Any?>>
        val emittedSizingTypes = layers.map { (it.getValue("sizing") as Map<*, *>)["type"] }.toSet()
        val emittedOrderTypes = layers.map { (it.getValue("orderType") as Map<*, *>)["type"] }.toSet()
        assertThat(emittedSizingTypes).isEqualTo(sizingTypes)
        assertThat(emittedOrderTypes).isEqualTo(dslOrderTypes)

        val childFixtures =
            listOf<ChildPriceAst>(
                ChildAt(NumLit(BigDecimal("101.00"))),
                ChildBy(NumLit(BigDecimal("2.00"))),
                ChildPct(NumLit(BigDecimal("1.50"))),
                ChildRr(NumLit(BigDecimal("2.00"))),
                ChildArmedTrail(NumLit(BigDecimal("2.00")), NumLit(BigDecimal("3.00"))),
            )
        assertThat(childFixtures.map { it::class.java.simpleName }.toSet())
            .isEqualTo(permittedNames(ChildPriceAst::class.java))
        val childLabels =
            childFixtures.mapIndexed { index, child ->
                val request = bracketRequest("child-$index", StopLossSpec.Fixed(BigDecimal("96.00")), child, null)
                val childPayload = OrderRequestEvidence.payload(request).getValue("takeProfitAst") as Map<*, *>
                childPayload["type"]
            }
        assertThat(childLabels).containsExactly("At", "By", "Pct", "Rr", "ArmedTrail")

        val stopLossFixtures =
            listOf<StopLossSpec>(
                StopLossSpec.Fixed(BigDecimal("96.00")),
                StopLossSpec.ArmedTrail(BigDecimal("2.00"), BigDecimal("3.00")),
                StopLossSpec.SteppedStop(
                    BigDecimal("4.00"),
                    listOf(StopLossSpec.Step(BigDecimal("2.00"), BigDecimal.ZERO)),
                ),
                StopLossSpec.TimeTighten(
                    BigDecimal("4.00"),
                    BigDecimal("0.50"),
                    5_000L,
                    BigDecimal("1.00"),
                ),
            )
        assertThat(stopLossFixtures.map { it::class.java.simpleName }.toSet())
            .isEqualTo(permittedNames(StopLossSpec::class.java))
        val stopLossLabels =
            stopLossFixtures.mapIndexed { index, stopLoss ->
                val request = bracketRequest("stop-loss-$index", stopLoss, null, null)
                val stopLossPayload = OrderRequestEvidence.payload(request).getValue("stopLoss") as Map<*, *>
                stopLossPayload["type"]
            }
        assertThat(stopLossLabels).containsExactly("Fixed", "ArmedTrail", "SteppedStop", "TimeTighten")
        assertThat(OrderRequestEvidence.SCHEMA_VERSION).isEqualTo(1)
    }

    private fun allRequests(): List<OrderRequest> {
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

    private fun stackRequest(): OrderRequest.Stack =
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

    private fun bracketRequest(
        id: String,
        stopLoss: StopLossSpec,
        takeProfitAst: ChildPriceAst?,
        stopLossAst: ChildPriceAst?,
    ): OrderRequest.Bracket =
        OrderRequest.Bracket(
            id = id,
            symbol = SYMBOL,
            side = Side.BUY,
            quantity = QTY,
            entry = market("$id-entry"),
            takeProfit = BigDecimal("108.00"),
            stopLoss = stopLoss,
            timeInForce = TimeInForce.GTC,
            timestamp = TS,
            strategyId = STRATEGY,
            takeProfitAst = takeProfitAst,
            stopLossAst = stopLossAst,
        )

    private fun market(id: String): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = SYMBOL,
            side = Side.BUY,
            quantity = QTY,
            timeInForce = TimeInForce.GTC,
            timestamp = TS,
            strategyId = STRATEGY,
        )

    private fun permittedNames(type: Class<*>): Set<String> = type.permittedSubclasses.map { it.simpleName }.toSet()

    private companion object {
        const val SYMBOL = "XAUUSD"
        const val STRATEGY = "evidence"
        const val TS = 1_718_000_000_000L
        val QTY: BigDecimal = BigDecimal("0.10")
    }
}
