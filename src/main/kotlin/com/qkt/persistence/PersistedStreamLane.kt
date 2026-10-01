package com.qkt.persistence

import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * One continuous futures stream's lane as a restart resumes it: the [contract] it last traded (null
 * before its first), each strategy on the stream, the engine orders working on
 * it, the contract positions its venue account holds, the roll's own venue orders still out ([legs], and
 * the resting orders whose cancel is awaited, by venue id, in [cancelling]), and the [roll] still in
 * flight (null when none). A roll's leg can outlive its roll: an unwind is still out after the roll ended.
 */
data class PersistedStreamLane(
    val stream: String,
    val contract: String?,
    val strategies: List<PersistedStreamStrategy>,
    val orders: List<PersistedStreamOrder>,
    val holdings: List<PersistedContractHolding>,
    val legs: List<PersistedRollLeg>,
    val cancelling: List<String>,
    val roll: PersistedStreamRoll?,
)

/** A roll's market [leg] still out at the venue, with the [slices] of it filled so far, oldest first. */
data class PersistedRollLeg(
    val leg: OrderRequest.Market,
    val slices: List<BrokerEvent.OrderFilled>,
)

/** A strategy's signed [position] on the stream, and why it may place no further orders there ([stopped], null if it may). */
data class PersistedStreamStrategy(
    val strategyId: String,
    val position: BigDecimal,
    val stopped: String?,
)

/**
 * An engine order working on the stream: the engine's [request] in continuous space, the id it works
 * under at the venue, the [contract] it works on, how many times a roll re-placed it, the quantity its
 * venue order was [placed] for, and how much of the engine's order has [filled] across every venue order
 * it worked under.
 */
data class PersistedStreamOrder(
    val request: OrderRequest,
    val venueId: String,
    val contract: String,
    val replacements: Int,
    val placed: BigDecimal,
    val filled: BigDecimal,
)

/** One strategy's signed [quantity] on one [contract] at its average entry price, as the lane's venue account holds it. */
data class PersistedContractHolding(
    val strategyId: String,
    val contract: String,
    val quantity: BigDecimal,
    val avgPrice: BigDecimal,
    val openedAt: Long?,
)

/**
 * A roll in flight: its contracts [from] and [to], the roll as measured (instant and both contracts' reference prices),
 * the reason that prefixes a holder's stop, the [resting] orders pulled off the old contract to re-place,
 * the [holders] in carry order, and each holder's step so far (a holder not reached yet has none).
 */
data class PersistedStreamRoll(
    val from: String,
    val to: String,
    val atMs: Long,
    val fromPrice: BigDecimal,
    val toPrice: BigDecimal,
    val stopped: String,
    val resting: List<PersistedStreamOrder>,
    val holders: List<PersistedRollHolder>,
    val steps: List<PersistedCarryStep>,
)

/** A strategy carried by a roll, with its signed position on the stream when the roll began. */
data class PersistedRollHolder(
    val strategyId: String,
    val quantity: BigDecimal,
)

/** Where one holder's carry stands: the venue leg it waits for, or how it ended. */
sealed interface PersistedCarryStep {
    val strategyId: String
    val quantity: BigDecimal

    /** Waiting for the closing [leg] on the old contract. */
    data class Closing(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val leg: OrderRequest.Market,
    ) : PersistedCarryStep

    /** The old contract [close]d; waiting for the opening [leg] on the new one. */
    data class Opening(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val close: BrokerEvent.OrderFilled,
        val leg: OrderRequest.Market,
    ) : PersistedCarryStep

    /** Carried: the old contract's [close] and the new contract's [open]. */
    data class Carried(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val close: BrokerEvent.OrderFilled,
        val open: BrokerEvent.OrderFilled,
    ) : PersistedCarryStep

    /** Not carried, for [reason]; [close] is the position's close on the stream, null when nothing closed. */
    data class Stopped(
        override val strategyId: String,
        override val quantity: BigDecimal,
        val reason: String,
        val close: BrokerEvent.OrderFilled?,
    ) : PersistedCarryStep
}
