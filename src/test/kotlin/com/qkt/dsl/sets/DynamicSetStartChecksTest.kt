package com.qkt.dsl.sets

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A session's start checks see what a moving set's member rules ask for, not only the strategy's own rules. */
class DynamicSetStartChecksTest {
    private fun host(rule: String): DynamicSetHost {
        val text =
            "STRATEGY rotation VERSION 1\nSYMBOLS\n" +
                "    s = BACKTEST:* WHERE kind = event TOP 2 BY symbol DESC DYNAMIC EVERY 1m\n" +
                "RULES\n    FOR EACH m IN s DO\n      $rule"
        return DynamicSetFixtures.plan(text).strategy(emptyMap(), live = true) as DynamicSetHost
    }

    @Test
    fun `plain member rules ask for nothing more`() {
        val plain = host("WHEN m.close > 100 THEN BUY m SIZING 1")
        assertThat(plain.volumeRequiringSymbols).isEmpty()
        assertThat(plain.markSymbols).isEmpty()
        assertThat(plain.usesBookSizing).isFalse()
        assertThat(plain.unsupportedLive()).isNull()
    }

    @Test
    fun `a member rule's volume indicator, mark read and book sizing reach the start checks`() {
        assertThat(host("WHEN m.close > vwap(m.tick, 3) THEN BUY m SIZING 1").volumeRequiringSymbols)
            .containsExactly("BACKTEST:A")
        assertThat(host("WHEN m.mark - m.index > 5 THEN BUY m SIZING 1").markSymbols).containsExactly("BACKTEST:A")
        val booked =
            host(
                "WHEN m.close > 100 THEN BUY m SIZING 1 PCT RISK OF BOOK " +
                    "BRACKET { STOP LOSS PCT 10, TAKE PROFIT AT 1000 }",
            )
        assertThat(booked.usesBookSizing).isTrue()
    }

    @Test
    fun `stacked entries and exit hooks in a member rule are named as not run live`() {
        val stacked =
            host(
                "WHEN m.close > 0 THEN BUY m SIZING 0.1\n        STACK_AT MFE >= 50 WITHIN 30m SIZING 0.05\n" +
                    "          BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }",
            )
        assertThat(stacked.unsupportedLive()).isEqualTo("STACK_AT")
        val hooked =
            host(
                "WHEN m.close > 0 THEN BUY m SIZING 0.1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 } " +
                    "ON_STOP { SELL m SIZING 0.1 }",
            )
        assertThat(hooked.unsupportedLive()).isEqualTo("an exit hook")
    }
}
