package com.qkt.dsl.ast

import java.math.BigDecimal

/** Whether a structure leg buys or sells its option. */
enum class StructureLegSide { BUY, SELL }

/** The right of a structure leg's option. */
enum class StructureLegRight { CALL, PUT }

/**
 * One leg of an option structure: [side] the option of [right] whose |delta| is nearest [delta], in
 * the nearest expiry [minDays]..[maxDays] days out, or (both null) in the first leg's expiry
 * (`SAME EXPIRY`).
 */
data class StructureLegAst(
    val side: StructureLegSide,
    val right: StructureLegRight,
    val delta: BigDecimal,
    val minDays: Int?,
    val maxDays: Int?,
)

/**
 * `OPEN <alias> = OPTIONS ON <VENUE>:<ROOT> { leg, … } SIZING …`: open a multi-leg option position on
 * [root], its contracts chosen from the chain when the rule fires, sized by [sizing] (contracts per
 * leg, or a risk fraction of equity over the structure's maximum loss).
 */
data class OpenStructure(
    val alias: String,
    val root: String,
    val legs: List<StructureLegAst>,
    val sizing: SizingAst,
) : ActionAst
