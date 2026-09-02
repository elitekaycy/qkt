package com.qkt.execution

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExitRef
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotKind
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.Window
import java.math.BigDecimal

/**
 * Stable structural evidence for a normalized [OrderRequest].
 *
 * The payload uses fixed type labels rather than reflection names and recursively records
 * composites and retained DSL plans. Maps preserve insertion order. [toJson] retains nulls,
 * renders decimals with [BigDecimal.toPlainString], and fails on unsupported value types.
 */
object OrderRequestEvidence {
    /** Version of the canonical order-request evidence schema. */
    const val SCHEMA_VERSION: Int = 1

    /** Build the ordered, recursively structured evidence payload for [request]. */
    fun payload(request: OrderRequest): Map<String, Any?> {
        val base =
            linkedMapOf<String, Any?>(
                "orderId" to request.id,
                "orderType" to orderTypeLabel(request),
                "symbol" to request.symbol,
                "side" to request.side.name,
                "qty" to request.quantity,
                "strategyId" to request.strategyId.takeIf { it.isNotBlank() },
                "timeInForce" to request.timeInForce.name,
                "createdTs" to request.timestamp,
                "expiresAt" to request.expiresAt,
                "legIntent" to legIntentPayload(request.legIntent),
            )
        when (request) {
            is OrderRequest.Market -> {
                base["closesTicket"] = request.closesTicket
                base["closesLegId"] = request.closesLegId
                base["partialClose"] = request.partialClose
            }
            is OrderRequest.Limit -> base["limitPrice"] = request.limitPrice
            is OrderRequest.Stop -> base["stopPrice"] = request.stopPrice
            is OrderRequest.StopLimit -> {
                base["stopPrice"] = request.stopPrice
                base["limitPrice"] = request.limitPrice
            }
            is OrderRequest.IfTouched -> {
                base["triggerPrice"] = request.triggerPrice
                base["onTrigger"] = request.onTrigger.name
                base["limitPrice"] = request.limitPrice
                base["closesTicket"] = request.closesTicket
                base["partialClose"] = request.partialClose
            }
            is OrderRequest.TrailingStop -> {
                base["trailAmount"] = request.trailAmount
                base["trailMode"] = request.trailMode.name
            }
            is OrderRequest.ArmedTrailingStop -> {
                base["entryPrice"] = request.entryPrice
                base["trailDistance"] = request.trailDistance
                base["mfeThreshold"] = request.mfeThreshold
            }
            is OrderRequest.SteppedStop -> {
                base["entryPrice"] = request.entryPrice
                base["initialDistance"] = request.initialDistance
                base["steps"] = request.steps.map(::stopStepPayload)
            }
            is OrderRequest.TimeTighteningStop -> {
                base["entryPrice"] = request.entryPrice
                base["initialDistance"] = request.initialDistance
                base["tightenBy"] = request.tightenBy
                base["intervalMs"] = request.intervalMs
                base["floorDistance"] = request.floorDistance
            }
            is OrderRequest.TrailingStopLimit -> {
                base["trailAmount"] = request.trailAmount
                base["trailMode"] = request.trailMode.name
                base["limitOffset"] = request.limitOffset
            }
            is OrderRequest.StandaloneOCO -> {
                base["leg1"] = payload(request.leg1)
                base["leg2"] = payload(request.leg2)
            }
            is OrderRequest.OTO -> {
                base["parent"] = payload(request.parent)
                base["children"] = request.children.map(::payload)
            }
            is OrderRequest.Bracket -> {
                base["entry"] = payload(request.entry)
                base["takeProfit"] = request.takeProfit
                base["stopLoss"] = stopLossPayload(request.stopLoss)
                base["takeProfitAst"] = childPricePayload(request.takeProfitAst)
                base["stopLossAst"] = childPricePayload(request.stopLossAst)
            }
            is OrderRequest.ScaleOut -> {
                base["basis"] = payload(request.basis)
                base["legs"] =
                    request.legs.map {
                        linkedMapOf(
                            "priceTarget" to it.priceTarget,
                            "fraction" to it.fraction,
                        )
                    }
            }
            is OrderRequest.TimeExit -> {
                base["target"] = payload(request.target)
                base["deadline"] = request.deadline.toEpochMilli()
                base["onExpiry"] = request.onExpiry.name
            }
            is OrderRequest.Stack -> {
                base["layers"] = request.plan.layers.size
                base["stackLayers"] = request.plan.layers.map(::stackLayerPayload)
                base["withinMillis"] = request.plan.withinMillis
                base["hasOuterBracket"] = request.plan.outerBracket != null
                base["outerBracket"] =
                    request.plan.outerBracket?.let {
                        linkedMapOf(
                            "takeProfit" to childPricePayload(it.takeProfit),
                            "stopLoss" to childPricePayload(it.stopLoss),
                        )
                    }
            }
        }
        return base
    }

    /** Render [request] as deterministic JSON using the same structure as [payload]. */
    fun toJson(request: OrderRequest): String =
        buildString {
            appendJsonValue(this, payload(request))
        }

    /** Structural intent record; `null` while unplanned so pre-intent captures stay comparable. */
    private fun legIntentPayload(intent: LegIntent): Map<String, Any?>? =
        when (intent) {
            LegIntent.Unplanned -> null
            LegIntent.Net -> linkedMapOf("kind" to "Net")
            is LegIntent.Open ->
                linkedMapOf(
                    "kind" to "Open",
                    "legId" to intent.legId,
                    "role" to intent.role.name,
                    "parentLegId" to intent.parentLegId,
                )
            is LegIntent.Close ->
                linkedMapOf(
                    "kind" to "Close",
                    "legId" to intent.legId,
                    "ticket" to intent.ticket,
                    "partial" to intent.partial,
                )
        }

    private fun orderTypeLabel(request: OrderRequest): String =
        when (request) {
            is OrderRequest.Market -> "Market"
            is OrderRequest.Limit -> "Limit"
            is OrderRequest.Stop -> "Stop"
            is OrderRequest.StopLimit -> "StopLimit"
            is OrderRequest.IfTouched -> "IfTouched"
            is OrderRequest.TrailingStop -> "TrailingStop"
            is OrderRequest.ArmedTrailingStop -> "ArmedTrailingStop"
            is OrderRequest.SteppedStop -> "SteppedStop"
            is OrderRequest.TimeTighteningStop -> "TimeTighteningStop"
            is OrderRequest.TrailingStopLimit -> "TrailingStopLimit"
            is OrderRequest.StandaloneOCO -> "StandaloneOCO"
            is OrderRequest.OTO -> "OTO"
            is OrderRequest.Bracket -> "Bracket"
            is OrderRequest.ScaleOut -> "ScaleOut"
            is OrderRequest.TimeExit -> "TimeExit"
            is OrderRequest.Stack -> "Stack"
        }

    private fun stackLayerPayload(layer: LayerSpec): Map<String, Any?> =
        linkedMapOf(
            "index" to layer.index,
            "sizing" to sizingPayload(layer.sizing),
            "orderType" to dslOrderTypePayload(layer.orderType),
            "trigger" to layerTriggerPayload(layer.trigger),
            "resolvedQuantity" to layer.resolvedQuantity,
        )

    private fun layerTriggerPayload(trigger: LayerTrigger): Map<String, Any?> =
        when (trigger) {
            Immediate -> linkedMapOf("type" to "Immediate")
            is At ->
                linkedMapOf(
                    "type" to "At",
                    "price" to exprPayload(trigger.price),
                    "direction" to trigger.direction.name,
                )
        }

    private fun sizingPayload(sizing: SizingAst): Map<String, Any?> =
        when (sizing) {
            is SizeQty -> linkedMapOf("type" to "SizeQty", "expr" to exprPayload(sizing.expr))
            is SizeNotional -> linkedMapOf("type" to "SizeNotional", "usd" to exprPayload(sizing.usd))
            is SizePctEquity -> linkedMapOf("type" to "SizePctEquity", "frac" to exprPayload(sizing.frac))
            is SizePctBalance -> linkedMapOf("type" to "SizePctBalance", "frac" to exprPayload(sizing.frac))
            is SizeRiskFrac -> linkedMapOf("type" to "SizeRiskFrac", "frac" to exprPayload(sizing.frac))
            is SizeRiskFracOfBook -> linkedMapOf("type" to "SizeRiskFracOfBook", "frac" to exprPayload(sizing.frac))
            is SizeRiskAbs -> linkedMapOf("type" to "SizeRiskAbs", "usd" to exprPayload(sizing.usd))
            is SizePositionFull -> linkedMapOf("type" to "SizePositionFull", "stream" to sizing.stream)
        }

    private fun dslOrderTypePayload(orderType: com.qkt.dsl.ast.OrderTypeAst): Map<String, Any?> =
        when (orderType) {
            com.qkt.dsl.ast.Market -> linkedMapOf("type" to "Market")
            is com.qkt.dsl.ast.Limit ->
                linkedMapOf("type" to "Limit", "price" to exprPayload(orderType.price))
            is com.qkt.dsl.ast.ExitRelativeLimit ->
                linkedMapOf(
                    "type" to "ExitRelativeLimit",
                    "sense" to orderType.price.sense.name,
                    "distance" to exprPayload(orderType.price.dist),
                )
            is com.qkt.dsl.ast.Stop ->
                linkedMapOf("type" to "Stop", "price" to exprPayload(orderType.price))
            is com.qkt.dsl.ast.ExitRelativeStop ->
                linkedMapOf(
                    "type" to "ExitRelativeStop",
                    "sense" to orderType.price.sense.name,
                    "distance" to exprPayload(orderType.price.dist),
                )
            is com.qkt.dsl.ast.StopLimit ->
                linkedMapOf(
                    "type" to "StopLimit",
                    "stopPrice" to exprPayload(orderType.stopPrice),
                    "limitPrice" to exprPayload(orderType.limitPrice),
                )
            is com.qkt.dsl.ast.TrailingBy ->
                linkedMapOf("type" to "TrailingBy", "distance" to exprPayload(orderType.distance))
            is com.qkt.dsl.ast.TrailingPct ->
                linkedMapOf("type" to "TrailingPct", "percent" to exprPayload(orderType.percent))
        }

    private fun childPricePayload(child: ChildPriceAst?): Map<String, Any?>? =
        when (child) {
            null -> null
            is ChildAt -> linkedMapOf("type" to "At", "price" to exprPayload(child.price))
            is ChildBy ->
                linkedMapOf(
                    "type" to "By",
                    "distance" to exprPayload(child.distance),
                    "ratchet" to stopRatchetPayload(child.ratchet),
                )
            is ChildPct -> linkedMapOf("type" to "Pct", "percent" to exprPayload(child.percent))
            is ChildRr -> linkedMapOf("type" to "Rr", "multiplier" to exprPayload(child.multiplier))
            is ChildArmedTrail ->
                linkedMapOf(
                    "type" to "ArmedTrail",
                    "trailDistance" to exprPayload(child.trailDistance),
                    "mfeThreshold" to exprPayload(child.mfeThreshold),
                )
        }

    private fun stopRatchetPayload(ratchet: com.qkt.dsl.ast.StopRatchetAst?): Map<String, Any?>? =
        when (ratchet) {
            null -> null
            is com.qkt.dsl.ast.SteppedStopAst ->
                linkedMapOf(
                    "type" to "Stepped",
                    "steps" to
                        ratchet.steps.map {
                            linkedMapOf(
                                "mfeThreshold" to exprPayload(it.mfeThreshold),
                                "profitDistance" to exprPayload(it.profitDistance),
                            )
                        },
                )
            is com.qkt.dsl.ast.TimeTightenAst ->
                linkedMapOf(
                    "type" to "TimeTighten",
                    "tightenBy" to exprPayload(ratchet.tightenBy),
                    "intervalMs" to ratchet.interval.millis,
                    "floorDistance" to exprPayload(ratchet.floorDistance),
                )
        }

    private fun stopLossPayload(stopLoss: StopLossSpec): Map<String, Any?> =
        when (stopLoss) {
            is StopLossSpec.Fixed ->
                linkedMapOf(
                    "type" to "Fixed",
                    "price" to stopLoss.price,
                )
            is StopLossSpec.ArmedTrail ->
                linkedMapOf(
                    "type" to "ArmedTrail",
                    "trailDistance" to stopLoss.trailDistance,
                    "mfeThreshold" to stopLoss.mfeThreshold,
                )
            is StopLossSpec.SteppedStop ->
                linkedMapOf(
                    "type" to "SteppedStop",
                    "initialDistance" to stopLoss.initialDistance,
                    "steps" to stopLoss.steps.map(::stopStepPayload),
                )
            is StopLossSpec.TimeTighten ->
                linkedMapOf(
                    "type" to "TimeTighten",
                    "initialDistance" to stopLoss.initialDistance,
                    "tightenBy" to stopLoss.tightenBy,
                    "intervalMs" to stopLoss.intervalMs,
                    "floorDistance" to stopLoss.floorDistance,
                )
        }

    private fun stopStepPayload(step: StopLossSpec.Step): Map<String, Any?> =
        linkedMapOf(
            "mfeThreshold" to step.mfeThreshold,
            "profitDistance" to step.profitDistance,
        )

    private fun exprPayload(expr: ExprAst): Map<String, Any?> =
        when (expr) {
            is NumLit -> linkedMapOf("type" to "NumLit", "value" to expr.value)
            is BoolLit -> linkedMapOf("type" to "BoolLit", "value" to expr.value)
            is StringLit -> linkedMapOf("type" to "StringLit", "value" to expr.value)
            is Ref ->
                linkedMapOf(
                    "type" to "Ref",
                    "name" to expr.name,
                    "snapshot" to snapshotPayload(expr.snapshot),
                )
            is StreamFieldRef ->
                linkedMapOf("type" to "StreamFieldRef", "stream" to expr.stream, "field" to expr.field)
            is NowAccessor -> linkedMapOf("type" to "NowAccessor", "field" to expr.field.name)
            is CalendarWindow ->
                linkedMapOf(
                    "type" to "CalendarWindow",
                    "startMonth" to expr.startMonth,
                    "startDay" to expr.startDay,
                    "endMonth" to expr.endMonth,
                    "endDay" to expr.endDay,
                )
            is SessionWindow ->
                linkedMapOf(
                    "type" to "SessionWindow",
                    "startHour" to expr.startHour,
                    "startMinute" to expr.startMinute,
                    "endHour" to expr.endHour,
                    "endMinute" to expr.endMinute,
                )
            LastTradingDayOfMonth -> linkedMapOf("type" to "LastTradingDayOfMonth")
            is IndicatorCall ->
                linkedMapOf("type" to "IndicatorCall", "name" to expr.name, "args" to expr.args.map(::exprPayload))
            is BinaryOp ->
                linkedMapOf(
                    "type" to "BinaryOp",
                    "op" to expr.op.name,
                    "lhs" to exprPayload(expr.lhs),
                    "rhs" to exprPayload(expr.rhs),
                )
            is UnaryOp ->
                linkedMapOf("type" to "UnaryOp", "op" to expr.op.name, "arg" to exprPayload(expr.arg))
            is CmpOp ->
                linkedMapOf(
                    "type" to "CmpOp",
                    "op" to expr.op.name,
                    "lhs" to exprPayload(expr.lhs),
                    "rhs" to exprPayload(expr.rhs),
                )
            is Between ->
                linkedMapOf(
                    "type" to "Between",
                    "value" to exprPayload(expr.v),
                    "lower" to exprPayload(expr.lo),
                    "upper" to exprPayload(expr.hi),
                )
            is InList ->
                linkedMapOf(
                    "type" to "InList",
                    "value" to exprPayload(expr.v),
                    "members" to expr.members.map(::exprPayload),
                )
            is Crosses ->
                linkedMapOf(
                    "type" to "Crosses",
                    "direction" to expr.direction.name,
                    "lhs" to exprPayload(expr.lhs),
                    "rhs" to exprPayload(expr.rhs),
                )
            is CaseWhen ->
                linkedMapOf(
                    "type" to "CaseWhen",
                    "branches" to
                        expr.branches.map { (condition, result) ->
                            linkedMapOf(
                                "condition" to exprPayload(condition),
                                "result" to exprPayload(result),
                            )
                        },
                    "else" to exprPayload(expr.elseExpr),
                )
            is Aggregate ->
                linkedMapOf(
                    "type" to "Aggregate",
                    "function" to expr.fn.name,
                    "series" to exprPayload(expr.series),
                    "window" to windowPayload(expr.window),
                )
            is AccountRef -> linkedMapOf("type" to "AccountRef", "field" to expr.field)
            is StreakRef -> linkedMapOf("type" to "StreakRef", "field" to expr.field)
            is TradesRef -> linkedMapOf("type" to "TradesRef", "field" to expr.field)
            is CooldownRef -> linkedMapOf("type" to "CooldownRef", "field" to expr.field)
            is PositionRef -> linkedMapOf("type" to "PositionRef", "stream" to expr.stream)
            is SequenceAccessor ->
                linkedMapOf(
                    "type" to "SequenceAccessor",
                    "sequence" to expr.sequence,
                    "stage" to expr.stage,
                    "field" to expr.field,
                )
            is StateAccessor ->
                linkedMapOf("type" to "StateAccessor", "source" to expr.source.name, "key" to expr.key)
            is FuncCall ->
                linkedMapOf("type" to "FuncCall", "name" to expr.name, "args" to expr.args.map(::exprPayload))
            StackEntryRef -> linkedMapOf("type" to "StackEntryRef")
            EntryQty -> linkedMapOf("type" to "EntryQty")
            is ExitRef -> linkedMapOf("type" to "ExitRef", "field" to expr.field.name)
            is IsNull ->
                linkedMapOf("type" to "IsNull", "expr" to exprPayload(expr.expr), "negated" to expr.negated)
        }

    private fun snapshotPayload(snapshot: SnapshotKind?): Map<String, Any?>? =
        when (snapshot) {
            null -> null
            SnapshotBuy -> linkedMapOf("type" to "Buy")
            SnapshotSell -> linkedMapOf("type" to "Sell")
            SnapshotOpen -> linkedMapOf("type" to "Open")
            is SnapshotTPast -> linkedMapOf("type" to "TPast", "n" to snapshot.n)
        }

    private fun windowPayload(window: Window): Map<String, Any?> =
        when (window) {
            SinceOpen -> linkedMapOf("type" to "SinceOpen")
            is SinceTPast -> linkedMapOf("type" to "SinceTPast", "n" to window.n)
        }

    private fun appendJsonValue(
        out: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> out.append("null")
            is String -> appendJsonString(out, value)
            is Boolean -> out.append(value)
            is BigDecimal -> out.append(value.toPlainString())
            is Byte, is Short, is Int, is Long -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    appendJsonString(out, entry.key as? String ?: error("evidence map key must be a string"))
                    out.append(':')
                    appendJsonValue(out, entry.value)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    appendJsonValue(out, element)
                }
                out.append(']')
            }
            else -> error("unsupported order evidence value: ${value::class.qualifiedName}")
        }
    }

    private fun appendJsonString(
        out: StringBuilder,
        value: String,
    ) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        out.append("\\u00")
                        out.append(HEX[char.code ushr 4])
                        out.append(HEX[char.code and 0x0f])
                    } else {
                        out.append(char)
                    }
                }
            }
        }
        out.append('"')
    }

    private const val HEX = "0123456789abcdef"
}
