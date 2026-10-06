package com.qkt.broker.continuous

import com.qkt.broker.continuous.ContinuousFixture.Companion.ms
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RollFailureTest {
    private val f = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", slippageTicks = 1, refuseOpenLegs = true)
    private val roll = ms("2024-09-19T08:00:00Z")
    private val stopped =
        "BINANCE_UM:BTCUSDT@front stopped: roll BINANCE_UM:BTCUSDT_240927->BINANCE_UM:BTCUSDT_241227 " +
            "at 2024-09-19T08:00:00Z failed"

    @Test
    fun `a refused opening leg closes the engine position at the old leg's fill`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))

        f.tick("63005.0", atMs = roll)

        val close = f.only<BrokerEvent.OrderFilled>().last()
        assertThat(close.exitReason).isEqualTo(ExitReason.ROLL_FAILED)
        assertThat(close.updatesOrderExecution).isFalse()
        assertThat(close.symbol).isEqualTo(f.front)
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo("0.01")
        assertThat(close.price).isEqualByComparingTo("62999.9")
        assertThat(close.strategyId).isEqualTo("s")
        assertThat(f.ledger.entries).isEmpty()
        assertThat(f.costs).isEmpty()
    }

    @Test
    fun `the stopped strategy cannot enter again and learns why`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))
        f.tick("63005.0", atMs = roll)

        val ack = f.broker.submit(f.market("again", Side.BUY))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).startsWith(stopped)
        assertThat(ack.rejectReason).contains("insufficient margin")
    }

    @Test
    fun `its resting exits are cancelled, not carried`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))
        f.broker.submit(f.stop("protect", Side.SELL, "62900.0"))

        f.tick("63005.0", atMs = roll)
        f.tick("62000.0", atMs = roll + 900_000L)

        val cancel = f.only<BrokerEvent.OrderCancelled>().single()
        assertThat(cancel.clientOrderId).isEqualTo("protect")
        assertThat(cancel.reason).startsWith(stopped)
        assertThat(f.only<BrokerEvent.OrderFilled>().map { it.clientOrderId }).doesNotContain("protect")
    }

    @Test
    fun `a strategy that held nothing keeps trading the stream`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))
        f.tick("63005.0", atMs = roll)

        val ack = f.broker.submit(f.market("other", Side.BUY).copy(strategyId = "t"))

        assertThat(ack.accepted).isTrue()
        assertThat(f.only<BrokerEvent.OrderFilled>().last().clientOrderId).isEqualTo("other")
    }

    @Test
    fun `a data gap over a whole contract stops the holder and the exchange settles the expired leg`() {
        val gap = ContinuousFixture(startIso = "2024-07-01T00:00:00Z")
        gap.tick("60000.0")
        gap.broker.submit(gap.market("entry", Side.BUY))

        gap.tick("97100.0", atMs = ms("2024-12-20T00:00:00Z"))

        val settle = gap.only<BrokerEvent.OrderFilled>().last()
        assertThat(settle.exitReason).isEqualTo(ExitReason.EXPIRY)
        assertThat(settle.symbol).isEqualTo(gap.front)
        assertThat(settle.price).isEqualByComparingTo("60000.0")
        assertThat(gap.ledger.entries).isEmpty()
        assertThat(gap.broker.submit(gap.market("again", Side.BUY)).rejectReason).contains("stopped")
    }

    @Test
    fun `a gap past the old contract's expiry settles it instead of failing the run`() {
        val late = ContinuousFixture(startIso = "2024-09-19T07:45:00Z")
        late.tick("63010.0")
        late.broker.submit(late.market("entry", Side.BUY))

        late.tick("63100.0", atMs = ms("2024-09-27T09:00:00Z"))

        val settle = late.only<BrokerEvent.OrderFilled>().last()
        assertThat(settle.exitReason).isEqualTo(ExitReason.EXPIRY)
        assertThat(settle.symbol).isEqualTo(late.front)
        assertThat(late.ledger.entries).isEmpty()
        assertThat(late.broker.submit(late.market("again", Side.BUY)).rejectReason).contains("stopped")
    }

    @Test
    fun `a reaction to the failed roll is refused because the stream is already stopped`() {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))
        val reactions = mutableListOf<com.qkt.broker.SubmitAck>()
        f.bus.subscribe<BrokerEvent.OrderFilled> { e ->
            if (e.exitReason == ExitReason.ROLL_FAILED) reactions += f.broker.submit(f.market("hook", Side.BUY))
        }

        f.tick("63005.0", atMs = roll)

        assertThat(reactions.single().accepted).isFalse()
        assertThat(reactions.single().rejectReason).startsWith(stopped)
    }

    @Test
    fun `a stopped strategy cannot reduce either, since its leg may still sit on the old contract`() {
        val gap = ContinuousFixture(startIso = "2024-07-01T00:00:00Z")
        gap.tick("60000.0")
        gap.broker.submit(gap.market("entry", Side.BUY))

        gap.clock.time = ms("2024-12-20T00:00:00Z")
        val exit = gap.broker.submit(gap.market("exit", Side.SELL))

        assertThat(exit.accepted).isFalse()
        assertThat(exit.rejectReason).contains("stopped")
    }
}
