package com.qkt.execution

import com.qkt.common.Side
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExitField
import com.qkt.dsl.ast.ExitRef
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.execution.OrderRequestEvidenceFixtures.TS
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestEvidenceExprTest {
    private val one = NumLit(BigDecimal.ONE)
    private val two = NumLit(BigDecimal("2"))

    @Suppress("UNCHECKED_CAST")
    private fun triggerPrice(expr: ExprAst): Map<String, Any?> {
        val stack =
            OrderRequest.Stack(
                id = "s",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                plan =
                    StackPlan(
                        layers =
                            listOf(
                                LayerSpec(0, SizeQty(one), com.qkt.dsl.ast.Market, At(expr, StackDirection.ABOVE), null),
                            ),
                        outerBracket = null,
                        withinMillis = 500L,
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = TS,
            )
        val payload = OrderRequestEvidence.payload(stack)
        assertThat(payload).containsEntry("hasOuterBracket", false).containsEntry("withinMillis", 500L)
        val layer = (payload.getValue("stackLayers") as List<*>).single() as Map<*, *>
        val trigger = layer["trigger"] as Map<*, *>
        assertThat(trigger["direction"]).isEqualTo("ABOVE")
        return trigger["price"] as Map<String, Any?>
    }

    @Test
    fun `literals and references record their labels and fields`() {
        assertThat(triggerPrice(BoolLit(true))).isEqualTo(mapOf("type" to "BoolLit", "value" to true))
        assertThat(triggerPrice(Ref("close", SnapshotBuy))["snapshot"]).isEqualTo(mapOf("type" to "Buy"))
        assertThat(triggerPrice(Ref("close", SnapshotSell))["snapshot"]).isEqualTo(mapOf("type" to "Sell"))
        assertThat(triggerPrice(Ref("close", SnapshotOpen))["snapshot"]).isEqualTo(mapOf("type" to "Open"))
        assertThat(triggerPrice(Ref("close"))).containsEntry("snapshot", null).containsEntry("name", "close")
        assertThat(triggerPrice(StreamFieldRef("gold", "high")))
            .isEqualTo(mapOf("type" to "StreamFieldRef", "stream" to "gold", "field" to "high"))
        assertThat(triggerPrice(NowAccessor(NowField.HOUR_UTC)))
            .isEqualTo(mapOf("type" to "NowAccessor", "field" to "HOUR_UTC"))
        assertThat(triggerPrice(AccountRef("equity"))).isEqualTo(mapOf("type" to "AccountRef", "field" to "equity"))
        assertThat(triggerPrice(StreakRef("losses"))).isEqualTo(mapOf("type" to "StreakRef", "field" to "losses"))
        assertThat(triggerPrice(TradesRef("today"))).isEqualTo(mapOf("type" to "TradesRef", "field" to "today"))
        assertThat(triggerPrice(CooldownRef("bars"))).isEqualTo(mapOf("type" to "CooldownRef", "field" to "bars"))
        assertThat(triggerPrice(PositionRef("gold"))).isEqualTo(mapOf("type" to "PositionRef", "stream" to "gold"))
        assertThat(triggerPrice(StateAccessor(StateSource.ACCOUNT, "k")))
            .isEqualTo(mapOf("type" to "StateAccessor", "source" to "ACCOUNT", "key" to "k"))
        assertThat(triggerPrice(SequenceAccessor("seq", null, "ts")))
            .isEqualTo(mapOf("type" to "SequenceAccessor", "sequence" to "seq", "stage" to null, "field" to "ts"))
        assertThat(triggerPrice(StackEntryRef)).isEqualTo(mapOf("type" to "StackEntryRef"))
        assertThat(triggerPrice(EntryQty)).isEqualTo(mapOf("type" to "EntryQty"))
        assertThat(triggerPrice(ExitRef(ExitField.PRICE))).isEqualTo(mapOf("type" to "ExitRef", "field" to "PRICE"))
        assertThat(triggerPrice(LastTradingDayOfMonth)).isEqualTo(mapOf("type" to "LastTradingDayOfMonth"))
    }

    @Test
    fun `calendar and session windows record their bounds`() {
        assertThat(triggerPrice(CalendarWindow(1, 2, 3, 4)))
            .isEqualTo(
                mapOf("type" to "CalendarWindow", "startMonth" to 1, "startDay" to 2, "endMonth" to 3, "endDay" to 4),
            )
        assertThat(triggerPrice(SessionWindow(8, 30, 16, 45)))
            .isEqualTo(
                mapOf(
                    "type" to "SessionWindow",
                    "startHour" to 8,
                    "startMinute" to 30,
                    "endHour" to 16,
                    "endMinute" to 45,
                ),
            )
    }

    @Test
    fun `operator expressions record their operands recursively`() {
        val numOne = mapOf("type" to "NumLit", "value" to BigDecimal.ONE)
        val numTwo = mapOf("type" to "NumLit", "value" to BigDecimal("2"))
        assertThat(triggerPrice(IndicatorCall("ema", listOf(one))))
            .isEqualTo(mapOf("type" to "IndicatorCall", "name" to "ema", "args" to listOf(numOne)))
        assertThat(triggerPrice(UnaryOp(UnOp.NEG, one)))
            .isEqualTo(mapOf("type" to "UnaryOp", "op" to "NEG", "arg" to numOne))
        assertThat(triggerPrice(CmpOp(Cmp.GT, one, two)))
            .isEqualTo(mapOf("type" to "CmpOp", "op" to "GT", "lhs" to numOne, "rhs" to numTwo))
        assertThat(triggerPrice(Between(one, one, two)))
            .isEqualTo(mapOf("type" to "Between", "value" to numOne, "lower" to numOne, "upper" to numTwo))
        assertThat(triggerPrice(InList(one, listOf(one, two))))
            .isEqualTo(mapOf("type" to "InList", "value" to numOne, "members" to listOf(numOne, numTwo)))
        assertThat(triggerPrice(Crosses(CrossDir.ABOVE, one, two)))
            .isEqualTo(mapOf("type" to "Crosses", "direction" to "ABOVE", "lhs" to numOne, "rhs" to numTwo))
        assertThat(triggerPrice(CaseWhen(listOf(BoolLit(false) to one), two)))
            .isEqualTo(
                mapOf(
                    "type" to "CaseWhen",
                    "branches" to listOf(mapOf("condition" to mapOf("type" to "BoolLit", "value" to false), "result" to numOne)),
                    "else" to numTwo,
                ),
            )
        assertThat(triggerPrice(IsNull(one, negated = true)))
            .isEqualTo(mapOf("type" to "IsNull", "expr" to numOne, "negated" to true))
    }

    @Test
    fun `aggregates record their function series and window`() {
        assertThat(triggerPrice(Aggregate(AggFn.MAX, one, SinceOpen))["window"])
            .isEqualTo(mapOf("type" to "SinceOpen"))
        assertThat(triggerPrice(Aggregate(AggFn.MEAN, one, SinceTPast(3))))
            .containsEntry("function", "MEAN")
            .containsEntry("window", mapOf("type" to "SinceTPast", "n" to 3))
    }

    @Test
    fun `json renders every expression variant`() {
        val stackJson =
            OrderRequestEvidence.toJson(
                OrderRequest.Stack(
                    id = "s",
                    symbol = "XAUUSD",
                    side = Side.SELL,
                    quantity = BigDecimal.ONE,
                    plan =
                        StackPlan(
                            layers =
                                listOf(
                                    LayerSpec(
                                        0,
                                        SizeQty(Aggregate(AggFn.SUM, Ref("v", SnapshotOpen), SinceOpen)),
                                        com.qkt.dsl.ast.Market,
                                        Immediate,
                                        BigDecimal.ONE,
                                    ),
                                ),
                            outerBracket = null,
                            withinMillis = null,
                        ),
                    timeInForce = TimeInForce.GTC,
                    timestamp = TS,
                ),
            )
        assertThat(stackJson)
            .contains("\"sizing\":{\"type\":\"SizeQty\",\"expr\":{\"type\":\"Aggregate\",\"function\":\"SUM\"")
            .contains("\"snapshot\":{\"type\":\"Open\"}")
            .contains("\"window\":{\"type\":\"SinceOpen\"}")
            .contains("\"trigger\":{\"type\":\"Immediate\"}")
    }
}
