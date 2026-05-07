package com.qkt.dsl.ast

sealed interface SnapshotKind

data object SnapshotBuy : SnapshotKind

data object SnapshotSell : SnapshotKind

data object SnapshotOpen : SnapshotKind

data class SnapshotTPast(
    val n: Int,
) : SnapshotKind {
    init {
        require(n > 0) { "SnapshotTPast.n must be > 0: $n" }
    }
}
