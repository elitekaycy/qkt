package com.qkt.app

import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchCloseBeyond
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.NumLit
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchBacktestCloseBeyondTest : LatchBacktestFixtures() {
    @Test
    fun `CLOSE_BEYOND fakeout spike does not fire until a candle closes beyond the wire`() {
        val ast =
            Latch(
                stream = streamAlias,
                sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
                armWindow = DurationAst(300_000L),
                name = null,
                entries = listOf(LatchEntry(order = LatchMarket)),
                confirm = LatchCloseBeyond,
            )
        val h = harness(ast)

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))

        h.pipeline.ingest(Tick(symbol, BigDecimal("2001.00"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.40"), 60_003L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.40"), 120_001L))
        assertThat(h.strategyPositions.positionFor(strategyId, symbol)).isNull()

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 120_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 180_001L))
        assertThat(h.strategyPositions.positionFor(strategyId, symbol)).isNotNull
    }

    @Test
    fun `gold CLOSE_BEYOND can enter silver from silver price snapshot`() {
        val ast =
            Latch(
                stream = streamAlias,
                sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
                armWindow = DurationAst(300_000L),
                name = null,
                entries = listOf(LatchEntry(order = LatchMarket, stream = silverAlias)),
                confirm = LatchCloseBeyond,
            )
        val h = harness(ast, mapOf(streamAlias to hubKey, silverAlias to silverHubKey))

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))
        h.pipeline.ingest(Tick(silverSymbol, BigDecimal("30.00"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_003L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 120_001L))

        val silver = h.strategyPositions.positionFor(strategyId, silverSymbol)
        assertThat(silver).isNotNull
        assertThat(silver!!.quantity).isEqualByComparingTo("1")
        assertThat(silver.avgEntryPrice).isEqualByComparingTo("30.00")
    }
}
