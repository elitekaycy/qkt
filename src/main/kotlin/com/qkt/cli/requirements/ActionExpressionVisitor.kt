package com.qkt.cli.requirements

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchSensor
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen

/** Visits every expression evaluated by a strategy's rules and actions, including latches and exit hooks. */
internal class ActionExpressionVisitor(
    private val walk: (ExprAst?) -> Unit,
) {
    private val orders = OrderExpressionVisitor(walk)

    /** Visits a rule's condition and its action. */
    fun walkRule(rule: RuleAst) {
        when (rule) {
            is WhenThen -> {
                walk(rule.cond)
                walkAction(rule.action)
            }
        }
    }

    /** Visits every expression an action (and any nested or hooked action) evaluates. */
    fun walkAction(action: ActionAst) {
        when (action) {
            is Buy -> walkOpts(action.opts)
            is Sell -> walkOpts(action.opts)
            is Latch -> walkLatch(action)
            is Resize -> {
                orders.walkSizing(action.target)
                walk(action.minStep)
            }
            is Log -> action.fields.values.forEach(walk)
            is Block -> action.actions.forEach { walkAction(it) }
            is OcoEntry -> {
                walkAction(action.leg1)
                walkAction(action.leg2)
            }
            is Cancel,
            CancelAll,
            is Close,
            CloseAll,
            -> Unit
        }
    }

    private fun walkOpts(opts: ActionOpts) {
        orders.walkSizing(opts.sizing)
        orders.walkOrder(opts.orderType)
        orders.walkTif(opts.tif)
        orders.walkBracket(opts.bracket)
        orders.walkOco(opts.oco)
        orders.walkStack(opts.stack)
        opts.stackAts.forEach(orders::walkStackAt)
        opts.onFill.forEach { walkAction(it) }
        (opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose)
            .forEach { walkAction(it) }
    }

    private fun walkLatch(latch: Latch) {
        walkLatchSensor(latch.sensor)
        if (latch.confirm is LatchRetestHold) walk(latch.confirm.distance)
        latch.entries.forEach(::walkLatchEntry)
    }

    private fun walkLatchEntry(entry: LatchEntry) {
        when (val order = entry.order) {
            is LatchLimit -> walkDirRel(order.price)
            is LatchStop -> walkDirRel(order.price)
            LatchMarket -> Unit
        }
        walkLatchBracket(entry.bracket)
        orders.walkSizing(entry.sizing)
    }

    private fun walkLatchSensor(sensor: LatchSensor) {
        when (sensor) {
            is BreakOffset -> {
                walk(sensor.reference)
                walk(sensor.offset)
            }
        }
    }

    private fun walkLatchBracket(bracket: LatchBracket?) {
        if (bracket == null) return
        walkDirRel(bracket.stopLoss)
        walkDirRel(bracket.takeProfit)
    }

    private fun walkDirRel(dirRel: DirRel?) {
        if (dirRel != null) walk(dirRel.dist)
    }
}
