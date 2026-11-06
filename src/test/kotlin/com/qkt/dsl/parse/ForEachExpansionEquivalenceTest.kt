package com.qkt.dsl.parse

import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A `FOR EACH s IN [btc]` rule expands to exactly the rule written out by hand for `btc`: every
 * expression, sizing, order type, TIF, bracket, OCO, stack, STACK_AT, LATCH and RESIZE position
 * that names the loop variable is rewritten, and every other stream is left alone.
 */
class ForEachExpansionEquivalenceTest {
    private fun rules(body: String): List<Pair<Any, Any>> {
        val source =
            "STRATEGY s VERSION 1\nSYMBOLS\n    btc = BACKTEST:BTCUSDT EVERY 1h\n" +
                "    eth = BACKTEST:ETHUSDT EVERY 1h\n" +
                "RULES\n$body\n"
        val ast = (Dsl.parse(source) as ParseResult.Success).value
        return ast.rules.map { (it as WhenThen).cond to it.action }
    }

    private fun assertExpandsLikeHandWritten(
        cond: String,
        action: String,
    ) {
        val expanded = rules("    FOR EACH s IN [btc] DO\n      WHEN $cond\n      THEN $action")
        val handWritten = rules("    WHEN ${cond.forBtc()}\n    THEN ${action.forBtc()}")

        assertThat(expanded).isEqualTo(handWritten)
        assertThat(expanded.toString()).doesNotContain("stream=s,").doesNotContain("stream=s)")
    }

    private fun String.forBtc(): String = replace(Regex("\\bs\\b"), "btc")

    @Test
    fun `range, membership, case and null tests are rewritten`() {
        assertExpandsLikeHandWritten(
            "s.close BETWEEN eth.low AND s.high AND s.close IN [s.open, eth.close, 3] AND " +
                "CASE WHEN s.close > eth.close THEN s.high ELSE eth.low END > 0 AND " +
                "-s.close < 0 AND NOT (s.open > 1) AND ema(s.close, 5) IS NOT NULL AND " +
                "max(s.close, eth.close) > 0 AND POSITION.s.pnl >= POSITION.eth.pnl AND " +
                "POSITION.s = POSITION.eth AND s.close CROSSES ABOVE eth.close AND " +
                "sum(s.volume) SINCE T-3 > 0 AND STREAK.wins >= 0 AND TRADES.today >= 0 AND COOLDOWN.remaining_s >= 0",
            "CLOSE s ; CANCEL s ; CLOSE eth ; CANCEL eth",
        )
    }

    @Test
    fun `every sizing form is rewritten`() {
        assertExpandsLikeHandWritten(
            "s.close > 0",
            "BUY s SIZING 1 PCT RISK OF BOOK BRACKET { STOP_LOSS BY s.close * 0.01, TAKE_PROFIT RR 2 } ; " +
                "SELL s SIZING RISK $ s.close BRACKET { STOP_LOSS AT s.high, TAKE_PROFIT BY 2 PCT } ; " +
                "SELL eth SIZING POSITION.s ; SELL s SIZING POSITION.eth ; " +
                "BUY s SIZING 2 PCT OF BALANCE ; BUY s SIZING 2 PCT OF EQUITY ; BUY s SIZING s.close USD",
        )
    }

    @Test
    fun `every order type and a good-till-date are rewritten`() {
        assertExpandsLikeHandWritten(
            "s.close > 0",
            "BUY s SIZING 1 ORDER_TYPE = STOP AT s.high ; " +
                "BUY s SIZING 1 ORDER_TYPE = STOP AT s.high LIMIT AT s.high + 1 TIF GTD s.timestamp + 60000 ; " +
                "BUY s SIZING 1 ORDER_TYPE = TRAILING BY s.close * 0.01 TIF IOC ; " +
                "SELL s SIZING 1 ORDER_TYPE = TRAILING PCT s.close / 1000 ; " +
                "BUY s SIZING 1 ORDER_TYPE = MARKET OCO { STOP AT s.low, LIMIT AT s.high }",
        )
    }

    @Test
    fun `exit-hook relative orders and an armed trailing stop are rewritten`() {
        assertExpandsLikeHandWritten(
            "s.close > 0",
            "BUY s SIZING 1 BRACKET { STOP LOSS TRAILING s.close * 0.01 AFTER MFE >= s.close * 0.02, " +
                "TAKE_PROFIT BY 9 } " +
                "ON_STOP { SELL s SIZING 1 ORDER_TYPE = LIMIT AGAINST s.close * 0.01 } " +
                "ON_TP { BUY s SIZING 1 ORDER_TYPE = STOP WITH s.close * 0.01 } " +
                "ON_CLOSE { CLOSE s } ON_FILL { SELL s SIZING 1 }",
        )
    }

    @Test
    fun `layered stacks and STACK_AT tiers are rewritten`() {
        assertExpandsLikeHandWritten(
            "s.close > 0",
            "BUY s STACK [ 0.1, 0.2 AT s.close + 100, 0.3 LIMIT AT s.close + 200 ] ; " +
                "BUY s SIZING 0.2 BRACKET { STOP LOSS BY 25, TAKE PROFIT BY 60 } " +
                "STACK_AT MAE >= s.close * 0.01 RECOVER s.close * 0.005 WITHIN 1h " +
                "SIZING ENTRY_QTY * 0.5 BRACKET { STOP LOSS BY s.close * 0.01, TAKE PROFIT BY 60 } TIMES 2",
        )
    }

    @Test
    fun `a latch with a stop entry and bracket, and a resize, are rewritten`() {
        assertExpandsLikeHandWritten(
            "s.close > 0",
            "LATCH s OFFSET s.close * 0.001 FROM s.high ARM 10m CONFIRM RETEST_HOLD 2 WITHIN 5m { " +
                "ENTER STOP WITH s.close * 0.001 SIZING 0.5 " +
                "BRACKET { STOP LOSS AGAINST s.close * 0.002, TAKE PROFIT WITH 20 } ; " +
                "ENTER ON eth MARKET SIZING 0.5 } ; " +
                "RESIZE s TO 0.01 / atr(s, 14) MIN_STEP s.close * 0.0001 ; RESIZE eth TO 1",
        )
    }

    @Test
    fun `entries on other streams, one-sided brackets and OCO entries are rewritten`() {
        assertExpandsLikeHandWritten(
            "s.close > 0",
            "BUY eth SIZING 1 PCT RISK BRACKET { STOP_LOSS BY s.close * 0.01, TAKE_PROFIT BY 9 } ; " +
                "BUY s SIZING 0.2 BRACKET { STOP LOSS BY 25, TAKE PROFIT BY 60 } " +
                "STACK_AT MFE >= s.close * 0.01 WITHIN 1h SIZING 0.1 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 } ; " +
                "OCO_ENTRY { BUY s SIZING 1 ORDER_TYPE = STOP AT s.high, " +
                "SELL eth SIZING 1 ORDER_TYPE = STOP AT s.low } ; " +
                "LATCH eth OFFSET s.close * 0.001 ARM 10m " +
                "{ ENTER ON s LIMIT AGAINST 5 SIZING 0.5 BRACKET { STOP LOSS AGAINST 8 } }",
        )
    }
}
