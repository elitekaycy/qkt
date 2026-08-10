package com.qkt.observe

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EngineAuditJournalTest {
    @Test
    fun `audit journal records structured market data and fills for golden capture`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(time = 1_700_000_000_000L)
        val journal = EngineAuditJournal(tmp, "alpha", clock)
        journal.append(
            TickEvent(
                Tick(
                    symbol = "XAUUSD",
                    price = Money.of("2000"),
                    timestamp = clock.now(),
                    bid = Money.of("1999"),
                    ask = Money.of("2001"),
                ),
                timestamp = clock.now(),
            ),
        )
        journal.append(
            WarmupTickEvent(
                Tick(
                    symbol = "XAUUSD",
                    price = Money.of("1998"),
                    timestamp = clock.now() - 60_000L,
                    volume = Money.of("2"),
                    bid = Money.of("1997"),
                    ask = Money.of("1999"),
                    bidVolume = Money.of("3"),
                    askVolume = Money.of("4"),
                ),
                timestamp = clock.now(),
            ),
        )
        journal.append(
            CandleEvent(
                Candle(
                    symbol = "XAUUSD",
                    open = Money.of("1990"),
                    high = Money.of("2005"),
                    low = Money.of("1985"),
                    close = Money.of("2000"),
                    volume = Money.of("10"),
                    startTime = clock.now() - 60_000L,
                    endTime = clock.now(),
                    bid = Money.of("1999"),
                    ask = Money.of("2001"),
                ),
                timestamp = clock.now(),
            ),
        )
        journal.append(
            BrokerEvent.OrderFilled(
                clientOrderId = "o-1",
                brokerOrderId = "b-1",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = Money.of("2001"),
                quantity = Money.of("0.1"),
                strategyId = "alpha",
                timestamp = clock.now(),
            ),
        )
        journal.close()

        val text = Files.readString(tmp.resolve("alpha/audit-2023-11-14.jsonl"))
        assertThat(text)
            .contains("\"tick\":{\"timestampMs\":1700000000000")
            .contains("\"bid\":\"1999.00000000\"")
            .contains("\"eventType\":\"com.qkt.events.WarmupTickEvent\"")
            .contains("\"timestampMs\":1699999940000")
            .contains("\"bidVolume\":\"3.00000000\"")
            .contains("\"eventType\":\"com.qkt.events.CandleEvent\"")
            .contains("\"candle\":{\"startTimeMs\":1699999940000,\"endTimeMs\":1700000000000")
            .contains("\"open\":\"1990.00000000\"")
            .contains("\"volume\":\"10.00000000\"")
            .contains("\"fill\":{\"side\":\"BUY\"")
            .contains("\"brokerOrderId\":\"b-1\"")
    }

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
