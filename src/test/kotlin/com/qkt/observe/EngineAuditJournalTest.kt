package com.qkt.observe

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.StrategyCandleEvaluatedEvent
import com.qkt.events.StreamCandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
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
                sourceTimeframeMs = 300_000L,
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
            StreamCandleEvent(
                broker = "EXNESS",
                timeframe = "5m",
                candle =
                    Candle(
                        symbol = "EXNESS:XAUUSD",
                        open = Money.of("1990"),
                        high = Money.of("2005"),
                        low = Money.of("1985"),
                        close = Money.of("2000"),
                        volume = Money.of("10"),
                        startTime = clock.now() - 300_000L,
                        endTime = clock.now(),
                    ),
                timestamp = clock.now(),
            ),
        )
        journal.append(
            StrategyCandleEvaluatedEvent(
                strategyId = "alpha",
                alias = "gold5",
                broker = "EXNESS",
                timeframe = "5m",
                rulesEvaluated = 1,
                candle =
                    Candle(
                        symbol = "EXNESS:XAUUSD",
                        open = Money.of("1990"),
                        high = Money.of("2005"),
                        low = Money.of("1985"),
                        close = Money.of("2000"),
                        volume = Money.of("10"),
                        startTime = clock.now() - 300_000L,
                        endTime = clock.now(),
                    ),
                timestamp = clock.now(),
            ),
        )
        val decisionCandle =
            Candle(
                symbol = "EXNESS:XAUUSD",
                open = Money.of("1990"),
                high = Money.of("2005"),
                low = Money.of("1985"),
                close = Money.of("2000"),
                volume = Money.of("10"),
                startTime = clock.now() - 300_000L,
                endTime = clock.now(),
            )
        journal.append(
            RuleDecisionEvent(
                strategyId = "alpha",
                decisionId = "alpha:gold5:1700000000000:abc",
                ruleId = "gold5#0",
                strategyFingerprint = "b".repeat(64),
                ruleFingerprint = "c".repeat(64),
                conditionFingerprint = "a".repeat(64),
                conditionResult = true,
                alias = "gold5",
                broker = "EXNESS",
                timeframe = "5m",
                signalCount = 1,
                candle = decisionCandle,
                timestamp = clock.now(),
            ),
        )
        journal.append(
            DecisionOrderLinkedEvent(
                strategyId = "alpha",
                decisionId = "alpha:gold5:1700000000000:abc",
                ruleId = "gold5#0",
                signalIndex = 0,
                orderId = "o-1",
                timestamp = clock.now(),
            ),
        )
        journal.append(
            FillAccountedEvent(
                orderId = "o-1",
                strategyId = "alpha",
                symbol = "EXNESS:XAUUSD",
                fillSliceId = "o-1:41",
                sourceFillSequenceId = 41L,
                cumulativeFilled = null,
                modeledCommissionAccount = Money.of("0.10"),
                venueCostsAccount = Money.of("0.20"),
                totalCostsAccount = Money.of("0.30"),
                accountNativeRealized = Money.of("2.00"),
                strategyNativeRealized = Money.of("2.00"),
                nativeCurrency = "USD",
                grossAccountRealized = Money.of("2.00"),
                grossStrategyAccountRealized = Money.of("2.00"),
                accountCurrency = "USD",
                netAccountRealized = Money.of("1.70"),
                netStrategyAccountRealized = Money.of("1.70"),
                conversionRate = null,
                conversionTimestampMs = null,
                conversionSource = null,
                contractSize = Money.of("100"),
                accountPositionBefore = Position("EXNESS:XAUUSD", Money.of("0.1"), Money.of("1990")),
                accountPositionAfter = null,
                strategyPositionBefore = Position("EXNESS:XAUUSD", Money.of("0.1"), Money.of("1990")),
                strategyPositionAfter = null,
                reducedExposure = true,
                partial = false,
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
            .contains("\"sourceTimeframeMs\":300000")
            .contains("\"timestampMs\":1699999940000")
            .contains("\"bidVolume\":\"3.00000000\"")
            .contains("\"eventType\":\"com.qkt.events.CandleEvent\"")
            .contains("\"candle\":{\"startTimeMs\":1699999940000,\"endTimeMs\":1700000000000")
            .contains("\"open\":\"1990.00000000\"")
            .contains("\"volume\":\"10.00000000\"")
            .contains("\"eventType\":\"com.qkt.events.StreamCandleEvent\"")
            .contains("\"broker\":\"EXNESS\",\"timeframe\":\"5m\"")
            .contains("\"eventType\":\"com.qkt.events.StrategyCandleEvaluatedEvent\"")
            .contains("\"strategyId\":\"alpha\"")
            .contains("\"alias\":\"gold5\",\"broker\":\"EXNESS\",\"timeframe\":\"5m\",\"rulesEvaluated\":1")
            .contains("\"eventType\":\"com.qkt.events.RuleDecisionEvent\"")
            .contains("\"decisionId\":\"alpha:gold5:1700000000000:abc\"")
            .contains("\"ruleId\":\"gold5#0\"")
            .contains("\"strategyFingerprint\":\"${"b".repeat(64)}\"")
            .contains("\"conditionResult\":true")
            .contains("\"signalCount\":1")
            .contains("\"eventType\":\"com.qkt.events.DecisionOrderLinkedEvent\"")
            .contains("\"orderId\":\"o-1\"")
            .contains("\"eventType\":\"com.qkt.events.FillAccountedEvent\"")
            .contains("\"fillSliceId\":\"o-1:41\"")
            .contains("\"totalCostsAccount\":\"0.30000000\"")
            .contains("\"netAccountRealized\":\"1.70000000\"")
            .contains("\"netStrategyAccountRealized\":\"1.70000000\"")
            .contains("\"kind\":\"EXECUTION\"")
            .contains("\"legId\":")
            .contains("\"legAction\":")
            .contains("\"strategyPositionBefore\":{\"symbol\":\"EXNESS:XAUUSD\",\"quantity\":\"0.10000000\"")
            .contains("\"fill\":{\"side\":\"BUY\"")
            .contains("\"brokerOrderId\":\"b-1\"")
        val lines = text.lines().filter { it.isNotBlank() }
        // Ticks and candles carry a complete structured block, so the stringified payload is
        // omitted for them; every other event still records it.
        for (line in lines) {
            val structured =
                listOf(
                    "TickEvent",
                    "WarmupTickEvent",
                    "CandleEvent",
                    "StreamCandleEvent",
                    "StrategyCandleEvaluatedEvent",
                ).any { line.contains("\"eventType\":\"com.qkt.events.$it\"") }
            if (structured) {
                assertThat(
                    line,
                ).doesNotContain("\"payload\":")
            } else {
                assertThat(line).contains("\"payload\":")
            }
        }
    }

    @Test
    fun `audit journal records stamped bus events as jsonl`(
        @TempDir tmp: Path,
    ) {
        val clock = FixedClock(time = 1_700_000_000_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val journal = EngineAuditJournal(tmp, "alpha", clock)
        bus.subscribeAllFirst(journal::append)

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
        bus.publish(
            RiskRejectedEvent(
                request =
                    OrderRequest.Stop(
                        id = "o-2",
                        symbol = "XAUUSD",
                        side = Side.SELL,
                        quantity = Money.of("0.10"),
                        stopPrice = Money.of("1990"),
                        timeInForce = TimeInForce.GTC,
                        strategyId = "alpha",
                        timestamp = 0L,
                    ),
                reason = "risk limit",
            ),
        )
        journal.close()

        val lines = Files.readAllLines(tmp.resolve("alpha/audit-2023-11-14.jsonl"))
        assertThat(lines).hasSize(2)
        assertThat(lines[0])
            .contains(""""v":1""")
            .contains(""""seq":0""")
            .contains(""""strategyId":"alpha"""")
            .contains(""""orderId":"o-1"""")
            .contains(""""symbol":"XAUUSD"""")
            .contains(""""eventType":"com.qkt.events.OrderEvent"""")
            .contains(""""orderSchemaVersion":1""")
            .contains(""""order":{"orderId":"o-1","orderType":"Market"""")
        assertThat(lines[1])
            .contains(""""eventType":"com.qkt.events.RiskRejectedEvent"""")
            .contains(""""orderId":"o-2"""")
            .contains(""""symbol":"XAUUSD"""")
            .contains(""""reason":"risk limit"""")
            .contains(""""order":{"orderId":"o-2","orderType":"Stop"""")
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
