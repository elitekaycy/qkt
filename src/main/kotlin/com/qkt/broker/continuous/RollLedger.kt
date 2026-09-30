package com.qkt.broker.continuous

import java.math.BigDecimal

/**
 * One strategy's position carried across one futures roll: closed on [from] at [fromFill] and
 * reopened on [to] at [toFill]. [quantity] is the carried position, positive long and negative
 * short; [fromReference] and [toReference] are the roll's reference prices, the ones the continuous
 * series was adjusted by; [fees] are both legs' fees in the contract's currency.
 */
data class RollEntry(
    val atMs: Long,
    val stream: String,
    val strategyId: String,
    val from: String,
    val to: String,
    val quantity: BigDecimal,
    val multiplier: BigDecimal,
    val fromFill: BigDecimal,
    val toFill: BigDecimal,
    val fromReference: BigDecimal,
    val toReference: BigDecimal,
    val fees: BigDecimal,
) {
    /**
     * What the roll cost against its reference prices, in the contract's currency; negative when
     * the fills beat them. The continuous series is continuous at the references, so the engine's
     * continuous-space P&L minus this cost is the P&L of the actual contract legs. e.g. long 0.01,
     * old leg sold 0.2 under its reference, new leg bought 0.2 over its reference, 0.5 fees:
     * 0.01 × 0.4 + 0.5 = 0.504.
     */
    val cost: BigDecimal
        get() {
            val slip = fromFill.subtract(fromReference).subtract(toFill.subtract(toReference))
            return quantity
                .multiply(multiplier)
                .multiply(slip)
                .negate()
                .add(fees)
        }
}

/** Every roll a run carried, in order; the source of the roll report. */
class RollLedger {
    private val recorded = mutableListOf<RollEntry>()

    /** Record [entry]. */
    fun record(entry: RollEntry) {
        recorded += entry
    }

    /** The rolls recorded so far, oldest first. */
    val entries: List<RollEntry> get() = recorded.toList()
}
