package com.qkt.dsl.compile

import java.math.BigDecimal

/** Read-only view exposed to compiled DSL expressions for `SEQUENCE.*` accessors. */
interface SequenceStateView {
    /** Zero-based current stage index, or the stage count while completion is pulsing. */
    fun stage(sequence: String): Int

    /** True only for the rule pass immediately after the final stage completes. */
    fun complete(sequence: String): Boolean

    /** Close price captured when [stage] completed, or null if it has not completed. */
    fun stagePrice(
        sequence: String,
        stage: String,
    ): BigDecimal?

    /** Candle close time captured when [stage] completed, or null if it has not completed. */
    fun stageTime(
        sequence: String,
        stage: String,
    ): Long?
}

/** Empty sequence state used when a compiled expression is evaluated outside a DSL runtime. */
object NoopSequenceStateView : SequenceStateView {
    override fun stage(sequence: String): Int = 0

    override fun complete(sequence: String): Boolean = false

    override fun stagePrice(
        sequence: String,
        stage: String,
    ): BigDecimal? = null

    override fun stageTime(
        sequence: String,
        stage: String,
    ): Long? = null
}
