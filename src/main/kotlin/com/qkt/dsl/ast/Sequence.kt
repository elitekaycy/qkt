package com.qkt.dsl.ast

/**
 * Linear multi-step setup evaluated on one stream's candle cadence.
 *
 * Stages advance in order. Each stage condition is an ordinary DSL expression and fires
 * on its own false-to-true edge.
 */
data class SequenceDecl(
    val name: String,
    val stream: String,
    val stages: List<SequenceStageDecl>,
) {
    init {
        require(name.isNotBlank()) { "SequenceDecl.name must not be blank" }
        require(stream.isNotBlank()) { "SequenceDecl.stream must not be blank" }
        require(stages.size in 2..8) { "SEQUENCE '$name' must declare 2-8 stages, got ${stages.size}" }
    }
}

/** One stage in a [SequenceDecl]. [within] is measured from the prior stage's fire time. */
data class SequenceStageDecl(
    val name: String,
    val within: DurationAst?,
    val condition: ExprAst,
) {
    init {
        require(name.isNotBlank()) { "SequenceStageDecl.name must not be blank" }
    }
}

/** DSL accessor such as `SEQUENCE.setup.complete` or `SEQUENCE.setup.swept.price`. */
data class SequenceAccessor(
    val sequence: String,
    val stage: String?,
    val field: String,
) : ExprAst
