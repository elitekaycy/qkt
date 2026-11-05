package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.observe.OrderJournal
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
}
