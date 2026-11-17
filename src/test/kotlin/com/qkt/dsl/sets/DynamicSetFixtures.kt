package com.qkt.dsl.sets

import com.qkt.cli.DynamicSetRuns
import com.qkt.cli.SymbolSetResolution
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.EventContracts
import com.qkt.instrument.universe.MembershipChange
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * A `DYNAMIC` set over three event contracts that list one after another (A at t0, B at t0+2m, C at t0+4m), ranked
 * `TOP 2 BY symbol DESC` at each 1m close: A joins, B joins, then C pushes A out of the ranking while A is held, so A
 * sticks, closes on its own exit rule, and is released at the next close.
 */
internal object DynamicSetFixtures {
    const val T0: Long = 1_700_000_040_000L
    const val MINUTE: Long = 60_000L

    val source: String =
        """
        STRATEGY rotation VERSION 1
        SYMBOLS
            s = BACKTEST:* WHERE kind = event TOP 2 BY symbol DESC DYNAMIC EVERY 1m
        RULES
            FOR EACH m IN s DO
              WHEN m.close > 100 AND POSITION.m = 0 THEN BUY m SIZING 1
            FOR EACH m IN s DO
              WHEN m.close < 100 AND POSITION.m > 0 THEN CLOSE m
        """.trimIndent()

    /** Contract [code] of venue BACKTEST, listed [openMinute] minutes after [T0] and open for a day. */
    fun contract(
        code: String,
        openMinute: Int,
    ) = EventContracts.meta(
        "BACKTEST:$code",
        EventContracts.terms().copy(
            openAtMs = T0 + openMinute * MINUTE,
            closeAtMs = T0 + 1_440 * MINUTE,
            expiryMs = T0 + 1_440 * MINUTE,
        ),
    )

    val rows = listOf(contract("A", 0), contract("B", 2), contract("C", 4))

    fun ast(text: String = source): StrategyAst = (Dsl.parse(text) as ParseResult.Success).value

    /** The run plan over [T0, T0 + [minutes]m] from [rows]. */
    fun plan(
        text: String = source,
        minutes: Int = 9,
    ): DynamicSetRuns.Plan =
        requireNotNull(
            DynamicSetRuns.plan(
                ast(text),
                SymbolSetResolution.FileCatalog(rows),
                T0,
                T0 + minutes * MINUTE,
                survivorshipAccepted = false,
                note = {},
            ),
        )

    /** Ticks every 10s for A, B and C over [minutes] minutes: A at 101 until t0+5m then 99, B at 101, C at 99. */
    fun tape(minutes: Int = 9): List<Tick> =
        (0 until minutes * 6).flatMap { i ->
            val ts = T0 + i * 10_000L
            listOf(
                Tick("BACKTEST:A", BigDecimal(if (ts < T0 + 5 * MINUTE) "101" else "99"), ts),
                Tick("BACKTEST:B", BigDecimal("101"), ts),
                Tick("BACKTEST:C", BigDecimal("99"), ts),
            )
        }

    /** One journal line as the parity check compares it: unstamped. */
    data class Line(
        val set: String,
        val member: String,
        val change: MembershipChange,
        val rank: Int?,
        val windowEndMs: Long,
    )

    fun line(e: SetMembershipChanged) = Line(e.set, e.member, e.change, e.rank, e.windowEndMs)

    /** The journal the scenario produces, in order. */
    val expectedJournal =
        listOf(
            Line("s", "BACKTEST:A", MembershipChange.JOIN, 1, T0),
            Line("s", "BACKTEST:B", MembershipChange.JOIN, 1, T0 + 2 * MINUTE),
            Line("s", "BACKTEST:A", MembershipChange.STICK, null, T0 + 4 * MINUTE),
            Line("s", "BACKTEST:C", MembershipChange.JOIN, 1, T0 + 4 * MINUTE),
            Line("s", "BACKTEST:A", MembershipChange.RELEASE, null, T0 + 7 * MINUTE),
        )
}
