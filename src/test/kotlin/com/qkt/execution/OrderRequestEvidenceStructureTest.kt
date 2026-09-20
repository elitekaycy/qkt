package com.qkt.execution

import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.execution.OrderRequestEvidenceFixtures.bracketRequest
import com.qkt.execution.OrderRequestEvidenceFixtures.permittedNames
import com.qkt.execution.OrderRequestEvidenceRequests.allRequests
import com.qkt.execution.OrderRequestEvidenceStackRequest.stackRequest
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestEvidenceStructureTest {
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
}
