package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/** Enters 0.010 of [side] on September. */
internal fun ScriptedLaneFixture.hold(side: Side) {
    broker.submit(OrderRequest.Market("entry", front, side, BigDecimal("0.010"), TimeInForce.GTC, clock.time, "s"))
    last("entry", sep, side, "0.010", "63000")
}

/** Ticks a normal book at the roll instant, which starts the roll. */
internal fun ScriptedLaneFixture.rolled() {
    clock.time = roll
    quote("63000", "62990", "63010")
}

/** The leg ending in [suffix], as the collared limit it must be. */
internal fun ScriptedLaneFixture.limit(suffix: String) = leg(suffix) as OrderRequest.Limit

/** Tells the lane its session is restored. */
internal fun ScriptedLaneFixture.ready() = broker.watchBookedLegs { emptyList() }

/** Why the one strategy is stopped on the stream, as saved. */
internal fun ScriptedLaneFixture.stop() = saved().strategies.single().stopped

/** A short rolled: its close filled, 0.004 of its open filled and no attempt filled more, its unwind out. */
internal fun ScriptedLaneFixture.partOpened() {
    hold(Side.SELL)
    rolled()
    last(leg(":close").id, sep, Side.BUY, "0.010", "63010")
    val open = leg(":open").id
    slice(open, dec, Side.SELL, "0.004", "0.004", "63790")
    exhaust("open")
}

/** The contract the one holding is on, as saved. */
internal fun ScriptedLaneFixture.heldOn() = saved().holdings.single().contract

/** The signed quantity of the one holding, as saved. */
internal fun ScriptedLaneFixture.heldQuantity() = saved().holdings.single().quantity

/** The request the venue was asked to take back under [id]. */
internal fun ScriptedLaneFixture.recovered(id: String) = venue.recovered.single { it.id == id }.request

/** The attempts of the leg [kind] (`close`, `open`, `unwind`) sent so far, first to last. */
internal fun ScriptedLaneFixture.attempts(kind: String) =
    venue.sent.filter { it.id.startsWith("roll:") && it.id.substringAfterLast(':').substringBefore('-') == kind }

/** The latest attempt of the leg [kind]. */
internal fun ScriptedLaneFixture.attempt(kind: String) = attempts(kind).last()

/** Moves on one retry's spacing and ticks a normal book, which sends what was waiting. */
internal fun ScriptedLaneFixture.nextRetry() {
    clock.time += 15_000
    quote("63000", "62990", "63010")
}

/** The venue ends every attempt of the leg [kind] unfilled, until the lane has none left: five in all. */
internal fun ScriptedLaneFixture.exhaust(kind: String) {
    repeat(4) {
        cancelled(attempt(kind).id)
        nextRetry()
    }
    cancelled(attempt(kind).id)
}
