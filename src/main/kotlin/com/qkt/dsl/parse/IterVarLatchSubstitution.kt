package com.qkt.dsl.parse

import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchConfirm
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchOrder
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchSensor
import com.qkt.dsl.ast.LatchStop

/*
 * Iteration-variable substitution for LATCH actions: the sensor, the entries with their orders
 * and brackets, and the confirm clause. See [substituteIterVar].
 */
internal fun subst(
    c: LatchConfirm,
    v: String,
    alias: String,
): LatchConfirm =
    when (c) {
        is LatchRetestHold -> c.copy(distance = subst(c.distance, v, alias))
        else -> c
    }

internal fun subst(
    s: LatchSensor,
    v: String,
    alias: String,
): LatchSensor =
    when (s) {
        is BreakOffset ->
            s.copy(
                reference = s.reference?.let { subst(it, v, alias) },
                offset = subst(s.offset, v, alias),
            )
    }

internal fun subst(
    e: LatchEntry,
    v: String,
    alias: String,
): LatchEntry =
    e.copy(
        order = subst(e.order, v, alias),
        bracket = e.bracket?.let { subst(it, v, alias) },
        sizing = e.sizing?.let { subst(it, v, alias) },
        stream = if (e.stream == v) alias else e.stream,
    )

internal fun subst(
    o: LatchOrder,
    v: String,
    alias: String,
): LatchOrder =
    when (o) {
        is LatchMarket -> o
        is LatchLimit -> o.copy(price = subst(o.price, v, alias))
        is LatchStop -> o.copy(price = subst(o.price, v, alias))
    }

internal fun subst(
    rel: DirRel,
    v: String,
    alias: String,
): DirRel = rel.copy(dist = subst(rel.dist, v, alias))

internal fun subst(
    b: LatchBracket,
    v: String,
    alias: String,
): LatchBracket =
    b.copy(
        stopLoss = b.stopLoss?.let { subst(it, v, alias) },
        takeProfit = b.takeProfit?.let { subst(it, v, alias) },
    )
