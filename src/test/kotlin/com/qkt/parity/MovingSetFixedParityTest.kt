package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.cli.SymbolSetResolution
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.marketdata.Tick
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A moving set whose members never change trades exactly like the same set fixed at start: its cross-member
 * functions read the same values at every close, even when the members' bars close one after another at the same
 * instant (A's bar, A's rules, then B's bar, ...).
 */
class MovingSetFixedParityTest {
    private val balance = BigDecimal("1000000")
    private val rows = listOf("A", "B", "C").map { DynamicSetFixtures.contract(it, 0) }

    // Each member in the top two buys SUM_OF - RANK_OF contracts when flat and closes at the next close, so every
    // entry's size records both functions as that member read them at that close.
    private val source =
        """
        STRATEGY ranked VERSION 1
        SYMBOLS
            s = BACKTEST:* WHERE kind = event TOP 3 BY symbol DYNAMIC EVERY 1m
        RULES
            FOR EACH m IN s DO
              WHEN POSITION.m = 0 AND RANK_OF(m.close, s) <= 2
              THEN BUY m SIZING SUM_OF(m.close, s) - RANK_OF(m.close, s)
            FOR EACH m IN s DO
              WHEN POSITION.m > 0 THEN CLOSE m
        """.trimIndent()

    /** Every 10s, A, B then C at one instant; per minute m: A at 10 + 2m, B at 18, C at 1 + 3m. */
    private val tape: List<Tick> =
        (0 until 8 * 6).flatMap { i ->
            val ts = T0 + i * 10_000L
            val m = (i / 6).toLong()
            listOf("A" to 10 + 2 * m, "B" to 18L, "C" to 1 + 3 * m).map { (code, price) ->
                Tick("BACKTEST:$code", BigDecimal(price), ts)
            }
        }

    private fun trades(strategy: Strategy): List<List<Any>> =
        Backtest(
            strategies = listOf("ranked" to strategy),
            ticks = tape,
            candleWindow = TimeWindow.ONE_MINUTE,
            initialTimestamp = T0,
            startingBalance = balance,
            startingBalances = mapOf("ranked" to balance),
        ).run()
            .trades
            .map { listOf(it.trade.symbol, it.trade.side.name, it.trade.quantity.toInt(), it.trade.timestamp) }

    @Test
    fun `a moving set that never changes members reads and trades exactly like the fixed set`() {
        val fixedAst =
            SymbolSetResolution.resolve(
                DynamicSetFixtures.ast(source.replace(" DYNAMIC", "")),
                SymbolSetResolution.FileCatalog(rows),
                T0,
                survivorshipAccepted = false,
                note = {},
            )
        val fixed = trades(AstCompiler().compile(fixedAst))
        val moving =
            trades(
                requireNotNull(
                    com.qkt.cli.DynamicSetRuns.plan(
                        DynamicSetFixtures.ast(source),
                        SymbolSetResolution.FileCatalog(rows),
                        T0,
                        T0 + 8 * MINUTE,
                        survivorshipAccepted = false,
                        note = {},
                    ),
                ).strategy(emptyMap()),
            )

        assertThat(moving).containsExactlyElementsOf(fixed)
        val entries = fixed.filter { it[1] == "BUY" }.map { Triple(it[0], it[2], ((it[3] as Long) - T0) / MINUTE) }
        // At 1m only A has a bar when its rule runs (10 - rank 1); B, closing next, reads
        // 10 + 18 and ranks first (28 - 1). C reads all three but ranks third.
        assertThat(entries.take(2)).containsExactly(Triple("BACKTEST:A", 9, 1L), Triple("BACKTEST:B", 27, 1L))
        assertThat(entries.size).isGreaterThan(6)
    }
}
