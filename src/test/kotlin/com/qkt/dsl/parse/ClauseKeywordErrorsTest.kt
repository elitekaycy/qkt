package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A clause keyword followed by a word it does not accept is a parse error naming what was expected. */
class ClauseKeywordErrorsTest {
    private fun strategyErrors(body: String): List<String> {
        val source = "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\n$body\n"
        return (Dsl.parse(source) as ParseResult.Failure).errors.map { it.message }
    }

    private fun ruleErrors(then: String) = strategyErrors("RULES\n    WHEN b.close > 0 THEN $then")

    private fun portfolioErrors(body: String): List<String> {
        val source = "PORTFOLIO p VERSION 1 CAPITAL 1000\nSYMBOLS\n    btc = BACKTEST:BTCUSDT EVERY 1m\nIMPORT 'a.qkt' AS a\n$body\n"
        return (Dsl.parseAny(source) as ParseResult.Failure).errors.map { it.message }
    }

    @Test
    fun `expression and sizing keywords`() {
        assertThat(strategyErrors("RULES\n    WHEN b.close CROSSES b.open THEN CLOSE b"))
            .contains("expected ABOVE or BELOW after CROSSES, got 'b'")
        assertThat(ruleErrors("BUY b SIZING 5 PCT OF CASH")).contains("expected EQUITY or BALANCE after % OF, got 'CASH'")
        assertThat(ruleErrors("BUY b SIZING 1 STACK 2.5 SPACING 1")).contains("STACK count must be a positive integer, got '2.5'")
        assertThat(ruleErrors("OCO_ENTRY { CLOSE b, SELL b SIZING 1 ORDER_TYPE = STOP AT 1 }"))
            .contains("OCO_ENTRY legs must be BUY or SELL, got Close")
    }

    @Test
    fun `bracket and OCO keywords`() {
        assertThat(ruleErrors("BUY b SIZING 1 BRACKET { STOP_LOSS BY 1, FOO 2 }"))
            .contains("expected STOP LOSS or TAKE PROFIT in BRACKET, got 'FOO'")
        assertThat(ruleErrors("BUY b SIZING 1 OCO { STOP AT 1, MARKET AT 2 }"))
            .contains("expected STOP AT or LIMIT AT in OCO, got 'MARKET'")
        assertThat(ruleErrors("BUY b SIZING 1 BRACKET { STOP LOSS BY 5 STEP TO NOWHERE AFTER MFE >= 3, TAKE PROFIT BY 9 }"))
            .contains("expected BREAKEVEN or ENTRY after STEP TO")
    }

    @Test
    fun `latch keywords`() {
        assertThat(ruleErrors("LATCH b OFFSET 1 ARM 5m CONFIRM SOON { ENTER MARKET }"))
            .contains("expected CLOSE_BEYOND, TIME_IN_BREACH, or RETEST_HOLD after CONFIRM")
        assertThat(ruleErrors("LATCH b OFFSET 1 ARM 5m { ENTER SOON }")).contains("expected MARKET/LIMIT/STOP after ENTER, got 'SOON'")
        assertThat(ruleErrors("LATCH b OFFSET 1 ARM 5m { ENTER MARKET BRACKET { SOON 2 } }"))
            .contains("expected STOP LOSS or TAKE PROFIT in BRACKET, got 'SOON'")
    }

    @Test
    fun `declaration keywords`() {
        val series = (Dsl.parse("STRATEGY s VERSION 1\nSYMBOLS\n    eq = SERIES WALLET EVERY 1h\n") as ParseResult.Failure).errors
        assertThat(series.map { it.message }).contains("expected ACCOUNT.EQUITY after SERIES, got 'WALLET'")
        assertThat(strategyErrors("SCHEDULE EVERY MONTH THEN CLOSE b")).contains("expected HOUR, DAY, or WEEKDAY after EVERY, got 'MONTH'")
        val structure = "OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { %s CALL DELTA 0.3 DTE %s TO 14 } SIZING 1"
        assertThat(ruleErrors(structure.format("HOLD", "7"))).contains("expected BUY or SELL to start a leg")
        assertThat(ruleErrors(structure.format("BUY", "7.5"))).contains("expected a whole number of days, got '7.5'")
    }

    @Test
    fun `portfolio regime and allocation keywords`() {
        val regimes = "REGIMES\n    NAME r\n    STATE x %s\n"
        val allocate = "ALLOCATE\n    METHOD %s\n    x -> %s 1.0\nRULES\n    RUN a"

        assertThat(portfolioErrors(regimes.format("SOMETIMES") + "RULES\n    RUN a")).contains("expected WHEN or DEFAULT after STATE 'x'")
        assertThat(portfolioErrors(regimes.format("DEFAULT") + allocate.format("equal", "a"))).contains("unknown allocation method 'equal'")
        assertThat(portfolioErrors(regimes.format("DEFAULT") + allocate.format("regime_weighted", "5")))
            .contains("expected alias or CASH in allocate entry")
    }
}
