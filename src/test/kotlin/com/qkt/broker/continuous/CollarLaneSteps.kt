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

/** A short rolled: its close filled, its open part filled (0.004 of 0.010) then ended, its unwind out. */
internal fun ScriptedLaneFixture.partOpened() {
    hold(Side.SELL)
    rolled()
    last(leg(":close").id, sep, Side.BUY, "0.010", "63010")
    val open = leg(":open").id
    slice(open, dec, Side.SELL, "0.004", "0.004", "63790")
    cancelled(open)
}

/** The contract the one holding is on, as saved. */
internal fun ScriptedLaneFixture.heldOn() = saved().holdings.single().contract

/** The signed quantity of the one holding, as saved. */
internal fun ScriptedLaneFixture.heldQuantity() = saved().holdings.single().quantity

/** The request the venue was asked to take back under [id]. */
internal fun ScriptedLaneFixture.recovered(id: String) = venue.recovered.single { it.id == id }.request
