package com.qkt.broker.continuous

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.ExitReason
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A live venue settles an expired contract with a `ContractSettled` on the lane's bus, not a fill: what a
 * stopped strategy still held of it closes on the stream there, as the backtest's exchange settles it.
 */
class LaneSettlementTest {
    private val f = ScriptedLaneFixture()

    /** A roll whose closing leg filled only 0.004 of 0.010: 0.006 stays on September, the strategy stopped. */
    private fun partClosedRoll() {
        f.holdIntoRoll()
        f.slice(f.leg(":close").id, f.sep, Side.SELL, "0.004", "0.004", "63000")
        f.cancelled(f.leg(":close").id)
    }

    private fun settles() =
        f.engine.filterIsInstance<BrokerEvent.OrderFilled>().filter {
            it.exitReason ==
                ExitReason.EXPIRY
        }

    @Test
    fun `what a stopped strategy still held of the expired contract closes on the stream at its settlement`() {
        partClosedRoll()
        val failed =
            f.engine.filterIsInstance<BrokerEvent.OrderFilled>().single {
                it.exitReason ==
                    ExitReason.ROLL_FAILED
            }
        val fee = VenueCost(CostKind.EXCHANGE_FEE, MoneyAmount(BigDecimal("0.3"), "USDT"), f.clock.time)

        f.venueBus.publish(ContractSettled(f.sep, BigDecimal("63500"), listOf(fee), f.clock.time))

        val settle = settles().single()
        assertThat(settle.symbol).isEqualTo(f.front)
        assertThat(settle.side).isEqualTo(Side.SELL)
        assertThat(settle.quantity).isEqualByComparingTo("0.006")
        assertThat(settle.strategyId).isEqualTo("s")
        assertThat(settle.updatesOrderExecution).isFalse()
        assertThat(settle.price.subtract(failed.price)).isEqualByComparingTo("500")
        assertThat(
            settle.typedVenueCosts
                .single()
                .amount.amount,
        ).isEqualByComparingTo("0.3")
        assertThat(f.lanePositions.positionFor(f.sep)?.quantity ?: BigDecimal.ZERO).isEqualByComparingTo("0")
    }

    @Test
    fun `a settlement heard twice, or of a contract nobody holds, closes nothing more`() {
        partClosedRoll()

        f.venueBus.publish(ContractSettled(f.sep, BigDecimal("63500"), timestamp = f.clock.time))
        f.venueBus.publish(ContractSettled(f.sep, BigDecimal("63500"), timestamp = f.clock.time))
        f.venueBus.publish(ContractSettled(f.mar, BigDecimal("64000"), timestamp = f.clock.time))

        assertThat(settles()).hasSize(1)
    }

    @Test
    fun `a settlement saved by the lane is not booked again after a restart`() {
        partClosedRoll()
        f.venueBus.publish(ContractSettled(f.sep, BigDecimal("63500"), timestamp = f.clock.time))

        val g = f.restart()
        g.broker.watchBookedLegs { emptyList() }
        g.venueBus.publish(ContractSettled(f.sep, BigDecimal("63500"), timestamp = g.clock.time))

        assertThat(g.engine.filterIsInstance<BrokerEvent.OrderFilled>().filter { it.exitReason == ExitReason.EXPIRY })
            .isEmpty()
    }
}
