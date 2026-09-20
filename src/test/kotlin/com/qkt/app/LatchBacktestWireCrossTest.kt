package com.qkt.app

import com.qkt.events.SignalSuppressedEvent
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchBacktestWireCrossTest : LatchBacktestFixtures() {
    @Test
    fun `latch arms on candle close, up-wire cross places limit, pullback fills it, TP exits with positive PnL`() {
        val h = harness()
        // Tick 1: open a 1-min candle at 2000.00 (t=0, window [0, 60000))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))

        // Tick 2: cross into the next 1-min window (t=60001) → closes the first candle (close=2000.00)
        //         → hub fires → latch arms with up=2000.50, down=1999.50
        //         clock.now()=0 → expires at 300_000ms; ticks 3-5 are well under that
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))

        // Tick 3: price crosses up-wire 2000.50 → LatchManager fires → places BUY LIMIT at 1996.50
        //         bracket: TP=2005.50, SL=1988.50
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_002L))

        // Tick 4: pullback to 1996.40 ≤ 1996.50 (BUY LIMIT fills) → position opens at ~1996.40
        h.pipeline.ingest(Tick(symbol, BigDecimal("1996.40"), 60_003L))

        // Tick 5: rally to 2005.60 ≥ 2005.50 (TP fills) → position closes at ~2005.50
        h.pipeline.ingest(Tick(symbol, BigDecimal("2005.60"), 60_004L))

        // PnL: (2005.50 - 1996.40) * qty=1 ≈ +9.10 — just assert it's positive
        val realized = h.strategyPnL.realizedFor(strategyId)
        assertThat(realized)
            .withFailMessage("expected positive realized PnL after TP fill, got $realized")
            .isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `no wire cross means no position opened`() {
        val h = harness()

        // Tick 1: open candle at 2000.00
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))

        // Tick 2: crosses 1-min candle boundary → arms latch (up=2000.50, down=1999.50)
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))

        // Ticks 3-5: price moves but never reaches 2000.50 or drops to 1999.50
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.10"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.20"), 60_003L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.30"), 60_004L))

        val realized = h.strategyPnL.realizedFor(strategyId)
        assertThat(realized)
            .withFailMessage("expected zero realized PnL with no wire cross, got $realized")
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `latch fire routes through the strategy gate and suppression event`() {
        val enabled = AtomicBoolean(true)
        val h = harness(gate = enabled::get)
        val suppressed = mutableListOf<SignalSuppressedEvent>()
        h.bus.subscribe<SignalSuppressedEvent> { suppressed.add(it) }

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))
        enabled.set(false)
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("1996.40"), 60_003L))

        assertThat(h.strategyPositions.positionFor(strategyId, symbol)).isNull()
        assertThat(suppressed).hasSize(1)
        assertThat(suppressed.single().signal).isInstanceOf(Signal.Submit::class.java)
        assertThat(suppressed.single().strategyId).isEqualTo(strategyId)
    }
}
