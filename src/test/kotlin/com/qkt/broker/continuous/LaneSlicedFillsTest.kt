package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A live f.venue fills in slices and may end an order part-filled; the stream books every slice and rolls safely. */
class LaneSlicedFillsTest {
    private val f = ScriptedLaneFixture()

    @Test
    fun `an order filled in slices reaches the engine slice by slice, and the roll carries the whole of it`() {
        f.holdIntoRoll()

        assertThat(f.engine.take(2).map { it::class.simpleName to (it as BrokerEvent.OrderEvent).clientOrderId })
            .containsExactly("OrderPartiallyFilled" to "entry", "OrderFilled" to "entry")
        assertThat((f.engine[0] as BrokerEvent.OrderPartiallyFilled).symbol).isEqualTo(f.front)
        assertThat(f.leg(":close").quantity).isEqualByComparingTo("0.010")
    }

    @Test
    fun `an opening leg filled in slices carries the whole position at the slices' weighted price`() {
        f.holdIntoRoll()
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "63000")
        f.slice(f.leg(":open").id, f.dec, Side.BUY, "0.004", "0.004", "63800")
        f.last(f.leg(":open").id, f.dec, Side.BUY, "0.006", "63805")

        val entry = f.ledger.entries.single()
        assertThat(entry.quantity).isEqualByComparingTo("0.010")
        assertThat(entry.toFill).isEqualByComparingTo("63803")
        assertThat(f.lanePositions.positionFor(f.dec)!!.quantity).isEqualByComparingTo("0.010")
        assertThat(f.engine.filter { (it as BrokerEvent.OrderEvent).clientOrderId.startsWith("roll:") }).isEmpty()
    }

    @Test
    fun `an opening leg ended part-filled is unwound, and the position closes on the stream at the old leg's fill`() {
        f.holdIntoRoll()
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "63000")
        f.slice(f.leg(":open").id, f.dec, Side.BUY, "0.004", "0.004", "63800")
        f.cancelled(f.leg(":open").id)

        val unwind = f.leg(":unwind")
        assertThat(unwind.symbol to unwind.side).isEqualTo(f.dec to Side.SELL)
        assertThat(unwind.quantity).isEqualByComparingTo("0.004")
        val close =
            f.engine.filterIsInstance<BrokerEvent.OrderFilled>().single {
                it.exitReason == ExitReason.ROLL_FAILED
            }
        assertThat(close.quantity).isEqualByComparingTo("0.010")
        assertThat(
            f.broker
                .submit(
                    OrderRequest.Market(
                        "again",
                        f.front,
                        Side.BUY,
                        BigDecimal("0.010"),
                        TimeInForce.GTC,
                        f.clock.time,
                        "s",
                    ),
                ).accepted,
        ).isFalse()
    }

    @Test
    fun `a closing leg ended part-filled closes only that part on the stream, and no opening leg is sent`() {
        f.holdIntoRoll()
        f.slice(f.leg(":close").id, f.sep, Side.SELL, "0.004", "0.004", "63000")
        f.cancelled(f.leg(":close").id)

        val close =
            f.engine.filterIsInstance<BrokerEvent.OrderFilled>().single {
                it.exitReason == ExitReason.ROLL_FAILED
            }
        assertThat(close.quantity).isEqualByComparingTo("0.004")
        assertThat(f.venue.sent.none { it.id.endsWith(":open") }).isTrue()
        assertThat(f.lanePositions.positionFor(f.sep)!!.quantity).isEqualByComparingTo("0.006")
    }

    @Test
    fun `a resting order part-filled before the roll is re-placed on the new contract for what is left of it`() {
        f.broker.submit(
            OrderRequest.Limit(
                "rest",
                f.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            ),
        )
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        f.rollAt("63000")
        f.cancelled("rest")
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.004", "63000")
        f.last(f.leg(":open").id, f.dec, Side.BUY, "0.004", "63800")

        val replaced = f.venue.sent.single { it.id == "rest~r1" }
        assertThat(replaced.symbol).isEqualTo(f.dec)
        assertThat(replaced.quantity).isEqualByComparingTo("0.006")
    }

    @Test
    fun `a slice of a re-placed order reaches the engine with the engine order's fill so far`() {
        f.broker.submit(
            OrderRequest.Limit(
                "rest",
                f.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            ),
        )
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        f.rollAt("63000")
        f.cancelled("rest")
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.004", "63000")
        f.last(f.leg(":open").id, f.dec, Side.BUY, "0.004", "63800")
        f.slice("rest~r1", f.dec, Side.BUY, "0.002", "0.002", "63690")

        val slice = f.engine.filterIsInstance<BrokerEvent.OrderPartiallyFilled>().last()
        assertThat(slice.clientOrderId).isEqualTo("rest")
        assertThat(slice.cumulativeFilled).isEqualByComparingTo("0.006")
    }

    @Test
    fun `a resting order that fills while the roll cancels it is not placed again on the new contract`() {
        f.broker.submit(
            OrderRequest.Limit(
                "rest",
                f.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            ),
        )
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        f.rollAt("63000")
        f.last("rest", f.sep, Side.BUY, "0.006", "62890")
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.004", "63000")
        f.last(f.leg(":open").id, f.dec, Side.BUY, "0.004", "63800")

        assertThat(f.venue.sent.none { it.id.startsWith("rest~") }).isTrue()
    }
}
