package com.qkt.observe

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EngineAuditJournalTest {
    @Test
    fun `audit journal records stamped bus events as jsonl`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(time = 1_700_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = EngineAuditJournal(tmp, "alpha", clock)
        bus.subscribeAll(journal::append)

        bus.publish(
            OrderEvent(
                OrderRequest.Market(
                    id = "o-1",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = Money.of("0.10"),
                    timeInForce = TimeInForce.GTC,
                    strategyId = "alpha",
                    timestamp = 0L,
                ),
            ),
        )
        journal.close()

        val lines = Files.readAllLines(tmp.resolve("alpha/audit-2023-11-14.jsonl"))
        assertThat(lines).hasSize(1)
        assertThat(lines[0])
            .contains(""""v":1""")
            .contains(""""seq":0""")
            .contains(""""strategyId":"alpha"""")
            .contains(""""orderId":"o-1"""")
            .contains(""""symbol":"XAUUSD"""")
            .contains(""""eventType":"com.qkt.events.OrderEvent"""")
    }

    @Test
    fun `audit journal sanitizes owner path`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(time = 1_700_000_000_000L)
        val journal = EngineAuditJournal(tmp, "prod/eu west", clock)
        journal.append(
            OrderEvent(
                OrderRequest.Market(
                    id = "o-1",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            ),
        )
        journal.close()

        assertThat(tmp.resolve("prod_eu_west/audit-2023-11-14.jsonl")).exists()
    }
}
