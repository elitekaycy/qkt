package com.qkt.observe

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EngineAuditJournalLossTest {
    private val clock = FixedClock(time = 1_700_000_000_000L)

    private fun tick(i: Int) =
        TickEvent(
            Tick(symbol = "DERIBIT:BTC_USDC_31OCT26_80000_C", price = Money.of("1500"), timestamp = clock.now() + i),
            timestamp = clock.now() + i,
        )

    private val order =
        OrderEvent(
            OrderRequest.Market(
                id = "o-1",
                symbol = "DERIBIT:BTC_USDC_31OCT26_80000_C",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                timeInForce = TimeInForce.GTC,
                strategyId = "alpha",
                timestamp = clock.now(),
            ),
            timestamp = clock.now(),
        )

    private val fill =
        BrokerEvent.OrderFilled(
            clientOrderId = "o-1",
            brokerOrderId = "b-1",
            symbol = "DERIBIT:BTC_USDC_31OCT26_80000_C",
            side = Side.BUY,
            price = Money.of("1510"),
            quantity = Money.of("0.01"),
            strategyId = "alpha",
            timestamp = clock.now(),
        )

    @Test
    fun `a quote flood past the queue sheds only market data and keeps the order and its fill`(
        @TempDir tmp: Path,
    ) {
        val journal = EngineAuditJournal(tmp, "alpha", clock, marketDataCapacity = 0)
        repeat(20_000) { journal.append(tick(it)) }
        journal.append(order)
        journal.append(fill)
        repeat(20_000) { journal.append(tick(it)) }
        journal.close()

        val lines = Files.readAllLines(tmp.resolve("alpha/audit-2023-11-14.jsonl"))
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("\"eventType\":\"com.qkt.events.OrderEvent\"")
        assertThat(lines[1]).contains("\"eventType\":\"com.qkt.events.BrokerEvent.OrderFilled\"")
        val health = journal.health()
        assertThat(health.droppedMarketData).isEqualTo(40_000L)
        assertThat(health.lostDecisionEvents).isZero()
        assertThat(health.written).isEqualTo(2L)
        assertThat(Files.readString(tmp.resolve("alpha/audit-2023-11-14.dropped")).trim()).isEqualTo("40000")
    }

    @Test
    fun `every record of a burst the queue can hold is written in bus order`(
        @TempDir tmp: Path,
    ) {
        val journal = EngineAuditJournal(tmp, "alpha", clock)
        repeat(3_000) { journal.append(tick(it)) }
        journal.append(fill)
        journal.close()

        val lines = Files.readAllLines(tmp.resolve("alpha/audit-2023-11-14.jsonl"))
        assertThat(lines).hasSize(3_001)
        assertThat(lines.last()).contains("OrderFilled")
        assertThat(journal.health().written).isEqualTo(3_001L)
        assertThat(journal.health().lossy).isFalse()
        assertThat(tmp.resolve("alpha/audit-2023-11-14.dropped")).doesNotExist()
    }

    @Test
    fun `a decision event a failed write loses is counted as lost, not silently gone`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("alpha"), "a file where the journal directory should be")
        val journal = EngineAuditJournal(tmp, "alpha", clock)
        journal.append(order)
        journal.append(tick(1))
        journal.close()

        val health = journal.health()
        assertThat(health.lostDecisionEvents).isEqualTo(1L)
        assertThat(health.droppedMarketData).isEqualTo(1L)
        assertThat(health.lossy).isTrue()
    }
}
