package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnaryOp

data class SnapshotPlan(
    val captureOnBuy: List<String>,
    val captureOnSell: List<String>,
    val captureOnOpen: List<String>,
    val rollingMaxN: Map<String, Int>,
) {
    companion object {
        fun scan(rules: List<ExprAst>): SnapshotPlan {
            val onBuy = mutableSetOf<String>()
            val onSell = mutableSetOf<String>()
            val onOpen = mutableSetOf<String>()
            val rolling = mutableMapOf<String, Int>()
            for (e in rules) walk(e, onBuy, onSell, onOpen, rolling)
            return SnapshotPlan(
                captureOnBuy = onBuy.toList(),
                captureOnSell = onSell.toList(),
                captureOnOpen = onOpen.toList(),
                rollingMaxN = rolling,
            )
        }

        private fun walk(
            expr: ExprAst,
            onBuy: MutableSet<String>,
            onSell: MutableSet<String>,
            onOpen: MutableSet<String>,
            rolling: MutableMap<String, Int>,
        ) {
            when (expr) {
                is Ref ->
                    when (val k = expr.snapshot) {
                        null -> {}
                        SnapshotBuy -> onBuy.add(expr.name)
                        SnapshotSell -> onSell.add(expr.name)
                        SnapshotOpen -> onOpen.add(expr.name)
                        is SnapshotTPast -> {
                            val cur = rolling[expr.name] ?: 0
                            if (k.n > cur) rolling[expr.name] = k.n
                        }
                    }
                is BinaryOp -> {
                    walk(expr.lhs, onBuy, onSell, onOpen, rolling)
                    walk(expr.rhs, onBuy, onSell, onOpen, rolling)
                }
                is UnaryOp -> walk(expr.arg, onBuy, onSell, onOpen, rolling)
                is CmpOp -> {
                    walk(expr.lhs, onBuy, onSell, onOpen, rolling)
                    walk(expr.rhs, onBuy, onSell, onOpen, rolling)
                }
                is Between -> {
                    walk(expr.v, onBuy, onSell, onOpen, rolling)
                    walk(expr.lo, onBuy, onSell, onOpen, rolling)
                    walk(expr.hi, onBuy, onSell, onOpen, rolling)
                }
                is InList -> {
                    walk(expr.v, onBuy, onSell, onOpen, rolling)
                    expr.members.forEach { walk(it, onBuy, onSell, onOpen, rolling) }
                }
                is Crosses -> {
                    walk(expr.lhs, onBuy, onSell, onOpen, rolling)
                    walk(expr.rhs, onBuy, onSell, onOpen, rolling)
                }
                is CaseWhen -> {
                    expr.branches.forEach {
                        walk(it.first, onBuy, onSell, onOpen, rolling)
                        walk(it.second, onBuy, onSell, onOpen, rolling)
                    }
                    walk(expr.elseExpr, onBuy, onSell, onOpen, rolling)
                }
                is IndicatorCall -> expr.args.forEach { walk(it, onBuy, onSell, onOpen, rolling) }
                is Aggregate -> walk(expr.series, onBuy, onSell, onOpen, rolling)
                is FuncCall -> expr.args.forEach { walk(it, onBuy, onSell, onOpen, rolling) }
                is NumLit, is BoolLit, is StreamFieldRef, is AccountRef, is PositionRef, is StateAccessor -> {}
            }
        }
    }
}
