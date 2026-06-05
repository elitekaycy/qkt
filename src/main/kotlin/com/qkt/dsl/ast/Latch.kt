package com.qkt.dsl.ast

/**
 * A directional-trigger entry. Arms two price trip-wires (`ref ± offset`); the
 * first one the market crosses sets a direction (BUY on the up-wire, SELL on the
 * down-wire) and an anchor price `O`. The [entries] are then placed relative to
 * `O` and that direction. e.g. an up-break at O=2000.5 turns `RETRACE 4` into a
 * BUY LIMIT at 1996.5.
 */
data class Latch(
    val stream: String,
    val sensor: LatchSensor,
    val armWindow: DurationAst,
    val name: String?,
    val entries: List<LatchEntry>,
) : ActionAst

/** How a latch decides it has tripped. Sealed so future sensors add as members. */
sealed interface LatchSensor

/** Trip when price crosses [reference] ± [offset]. [reference] null => `<stream>.close`. */
data class BreakOffset(
    val reference: ExprAst?,
    val offset: ExprAst,
) : LatchSensor

/** One entry placed when the latch trips, written relative to the break. */
data class LatchEntry(
    val order: LatchOrder,
    val bracket: LatchBracket? = null,
    val sizing: SizingAst? = null,
    val expire: DurationAst? = null,
)

sealed interface LatchOrder

data object LatchMarket : LatchOrder

data class LatchLimit(
    val price: DirRel,
) : LatchOrder

data class LatchStop(
    val price: DirRel,
) : LatchOrder

/** WITH = with the break (O + dir*d); AGAINST = against it (O - dir*d). RETRACE parses to AGAINST. */
enum class DirSense { WITH, AGAINST }

data class DirRel(
    val sense: DirSense,
    val dist: ExprAst,
)

data class LatchBracket(
    val stopLoss: DirRel? = null,
    val takeProfit: DirRel? = null,
)
