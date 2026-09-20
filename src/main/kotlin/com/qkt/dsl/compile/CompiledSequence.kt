package com.qkt.dsl.compile

import java.math.BigDecimal

/**
 * Compiled representation of one `SEQUENCE` stage.
 *
 * [withinMs] is measured from the previous completed stage. A null value means
 * the stage can complete any time after it becomes current.
 */
data class CompiledSequenceStage(
    val name: String,
    val withinMs: Long?,
    val condition: CompiledExpr,
)

/**
 * Compiled runtime metadata for a DSL `SEQUENCE` declaration.
 *
 * [referencedAliases] includes the sequence's `ON` stream and any aliases read by
 * stage conditions. The runtime uses it to avoid advancing a sequence before all
 * referenced streams are warmed.
 */
data class CompiledSequence(
    val name: String,
    val streamAlias: String,
    val streamSymbol: String,
    val stages: List<CompiledSequenceStage>,
    val referencedAliases: Set<String>,
)

/** Snapshot captured when a sequence stage completes. */
data class SequenceSnapshot(
    val stage: String,
    val price: BigDecimal,
    val timeMs: Long,
)
