package com.qkt.observe

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.StrategyCandleEvaluatedEvent
import com.qkt.events.StreamCandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.positions.Position

/** Appends one of every event kind a golden capture needs, in capture order. */
internal fun appendGoldenCaptureEvents(
    journal: EngineAuditJournal,
    clock: FixedClock,
) {
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
}
