package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.marketdata.Tick
import com.qkt.observe.OrderJournal
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderJournalWiringTest {
    @Test
    fun `every partial fill slice is journaled, so the journal's fills add up to what the venue executed`(
        @TempDir tmp: Path,
    ) {
        // #1646: a 0.03 stop filled 0.02 + 0.01 on the venue; the journal only held the final 0.01.
        val clock = FixedClock(1_793_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = OrderJournal(tmp, clock)
        OrderJournalWiring(emptyList()).wire(bus, journal)
        val id = "dsl-soak_o10_bybit--5-sl"
        val symbol = "BYBIT_LINEAR:ETHUSDT"

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = id,
                brokerOrderId = "b-1",
                symbol = symbol,
                side = Side.BUY,
                price = BigDecimal("2235.51"),
                quantity = BigDecimal("0.02"),
                cumulativeFilled = BigDecimal("0.02"),
                strategyId = "soak_o10_bybit",
                venueCosts = BigDecimal("0.0245"),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                id,
                "b-1",
                symbol,
                Side.BUY,
                BigDecimal("2235.52"),
                BigDecimal("0.01"),
                strategyId = "soak_o10_bybit",
            ),
        )
        journal.close()

        val day = Files.list(tmp.resolve("soak_o10_bybit")).use { files -> files.toList().single() }
        val lines = Files.readAllLines(day)
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains(
            "\"kind\":\"partially-filled\"",
            "\"id\":\"$id\"",
            "\"broker\":\"b-1\"",
            "\"symbol\":\"$symbol\"",
            "\"side\":\"BUY\"",
            "\"price\":\"2235.51\"",
            "\"qty\":\"0.02\"",
            "\"cumulative\":\"0.02\"",
            "\"venueCosts\":\"0.0245\"",
        )
        assertThat(lines[1]).contains("\"kind\":\"filled\"", "\"qty\":\"0.01\"")
    }

    private fun lines(
        tmp: Path,
        dir: String,
    ): List<String> = Files.list(tmp.resolve(dir)).use { files -> Files.readAllLines(files.toList().single()) }

    private fun protection(strategyId: String) =
        BrokerEvent.PositionProtectionChanged(
            broker = "mt5",
            symbol = "XAUUSD",
            ticket = "77",
            oldStopLoss = BigDecimal("1900"),
            newStopLoss = BigDecimal("1890"),
            oldTakeProfit = BigDecimal("2000"),
            newTakeProfit = BigDecimal("2010"),
            strategyId = strategyId,
        )

    @Test
    fun `rejections cancels and venue protection changes are journaled under their owner`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(1_793_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = OrderJournal(tmp, clock)
        val owner =
            "owner" to
                object : Strategy {
                    override fun onTick(
                        tick: Tick,
                        ctx: StrategyContext,
                        emit: (Signal) -> Unit,
                    ) {}
                }
        OrderJournalWiring(listOf(owner)).wire(bus, journal)

        bus.publish(BrokerEvent.OrderRejected("o1", null, "no margin", strategyId = "owner"))
        bus.publish(BrokerEvent.OrderCancelled("o2", "b-2", "user", strategyId = "owner"))
        bus.publish(protection(strategyId = ""))
        bus.publish(protection(strategyId = "named"))
        journal.close()

        val owned = lines(tmp, "owner")
        assertThat(owned).hasSize(3)
        assertThat(owned[0]).contains("\"kind\":\"rejected\"", "\"id\":\"o1\"", "\"reason\":\"no margin\"")
        assertThat(owned[1]).contains("\"kind\":\"cancelled\"", "\"id\":\"o2\"", "\"reason\":\"user\"")
        assertThat(owned[2]).contains(
            "\"kind\":\"position-protection-changed\"",
            "\"broker\":\"mt5\"",
            "\"ticket\":\"77\"",
            "\"oldSl\":\"1900\"",
            "\"newSl\":\"1890\"",
            "\"oldTp\":\"2000\"",
            "\"newTp\":\"2010\"",
        )
        assertThat(lines(tmp, "named").single()).contains("\"kind\":\"position-protection-changed\"")
    }

    @Test
    fun `unowned protection changes and account wide halts land in the unattributed journal`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(1_793_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = OrderJournal(tmp, clock)
        OrderJournalWiring(emptyList()).wire(bus, journal)

        bus.publish(protection(strategyId = ""))
        bus.publish(RiskEvent.Halted(reason = "account dd", strategyId = null))
        bus.publish(RiskEvent.Resumed(strategyId = null))
        bus.publish(RiskEvent.Halted(reason = "own dd", strategyId = "s1"))
        bus.publish(RiskEvent.Resumed(strategyId = "s1"))
        journal.close()

        val unattributed = lines(tmp, "_unattributed")
        assertThat(unattributed.map { it.substringAfter("\"kind\":\"").substringBefore('"') })
            .containsExactly("position-protection-changed", "halted", "resumed")
        assertThat(unattributed[1]).contains("\"reason\":\"account dd\"")
        assertThat(lines(tmp, "s1")).hasSize(2)
    }
}
