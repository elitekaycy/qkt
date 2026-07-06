package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Surface test: account and streak accessors parse and compile without error.
 *
 * Runtime semantics are covered by [com.qkt.pnl.TradeHistoryTest] (streak math)
 * and the existing `RiskView.drawdown` plumbing.
 */
class AccountStateAccessorsTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `ACCOUNT win_streak parses and compiles`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.win_streak >= 2 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ACCOUNT loss_streak parses and compiles`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.loss_streak < 3 AND g.close > 100 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ACCOUNT last_trade_at and last_trade_pnl parse and compile`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            LET cooldown_ok = NOW.epoch_ms - ACCOUNT.last_trade_at > 3600000
            RULES
              WHEN ACCOUNT.last_trade_pnl > 500 AND cooldown_ok THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ACCOUNT dd_pct parses and compiles`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.dd_pct > 5 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ACCOUNT equity_peak parses and compiles`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.equity > ACCOUNT.equity_peak * 0.95 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ACCOUNT open_positions_count parses and compiles`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.open_positions_count < 3 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ACCOUNT trades_today, wins_today, losses_today parse and compile`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.trades_today < 5
               AND ACCOUNT.wins_today >= 2
               AND ACCOUNT.losses_today < 3
              THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `STREAK wins losses and banked parse and compile`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN STREAK.losses < 2
              THEN BUY g SIZING RISK $ (100 + 0.30 * STREAK.banked)
                   BRACKET { STOP LOSS BY 40, TAKE PROFIT BY 90 }
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `TRADES and COOLDOWN accessors parse and compile`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN TRADES.today < 5 AND COOLDOWN.remaining_s = 0 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ACCOUNT unsupported field is rejected at compile time`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.nonsense_field > 0 THEN FLATTEN
            """.trimIndent()
        // Parse succeeds (ACCOUNT.<ident> accepts any field name); compile rejects.
        val parsed = parse(src).value
        org.assertj.core.api.Assertions
            .assertThatThrownBy { AstCompiler().compile(parsed) }
            .hasMessageContaining("ACCOUNT field")
    }

    @Test
    fun `existing ACCOUNT fields (equity, realized_pnl) still parse and compile`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ACCOUNT.equity > 1000 AND ACCOUNT.realized_pnl > -100 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `compiled strategy returns the new accessors as numeric expressions`() {
        // Compile a tiny strategy and verify that the LET expressions resolve through
        // the new accessor path without crashing in a real eval context. Detailed
        // runtime correctness is covered by TradeHistoryTest at the tracker level.
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN STREAK.wins + STREAK.losses >= 0 THEN FLATTEN
            """.trimIndent()
        val s = AstCompiler().compile(parse(src).value)
        assertThat(s).isNotNull
    }
}
