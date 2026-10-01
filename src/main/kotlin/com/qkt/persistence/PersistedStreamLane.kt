package com.qkt.persistence

import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * One continuous futures stream's lane as a restart resumes it: the contract it last traded
 * ([contractIndex], null before its first), each strategy on the stream, the engine orders working on
 * it, the contract positions its venue account holds, and the [roll] still in flight (null when none).
 */
data class PersistedStreamLane(
    val stream: String,
    val contractIndex: Int?,
    val strategies: List<PersistedStreamStrategy>,
    val orders: List<PersistedStreamOrder>,
    val holdings: List<PersistedContractHolding>,
    val roll: PersistedStreamRoll?,
)

/** A strategy's signed [position] on the stream, and why it may place no further orders there ([stopped], null if it may). */
data class PersistedStreamStrategy(
    val strategyId: String,
    val position: BigDecimal,
    val stopped: String?,
)

/**
 * An engine order working on the stream: the engine's [request] in continuous space, the id it works
 * under at the venue, the contract it works on, and how many times a roll re-placed it.
 */
data class PersistedStreamOrder(
    val request: OrderRequest,
    val venueId: String,
    val contractIndex: Int,
    val replacements: Int,
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
 * A roll in flight: its contracts, the roll as measured (instant and both contracts' reference prices),
 * the reason that prefixes a holder's stop, the [resting] orders pulled off the old contract to re-place,
 * the [holders] in carry order, and each holder's step so far (a holder not reached yet has none).
 */
data class PersistedStreamRoll(
    val fromIndex: Int,
    val toIndex: Int,
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
