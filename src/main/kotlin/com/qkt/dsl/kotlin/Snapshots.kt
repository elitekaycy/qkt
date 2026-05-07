package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotKind
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast

val atBuy: SnapshotKind = SnapshotBuy
val atSell: SnapshotKind = SnapshotSell
val atOpen: SnapshotKind = SnapshotOpen

fun atT(n: Int): SnapshotKind = SnapshotTPast(n)

infix fun ExprAst.at(snapshot: SnapshotKind): ExprAst {
    require(this is Ref) {
        "Snapshots only apply to LET references; got ${this::class.simpleName}"
    }
    return Ref(this.name, snapshot)
}
