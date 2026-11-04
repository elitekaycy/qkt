package com.qkt.connector.mt5

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Reproduction of #1096(b): a venue comment must only match the order it names. */
class Mt5CommentMatchTest {
    @Test
    fun `an order id is not matched by a comment for a different order sharing its prefix`() {
        assertThat(
            matchesOrderComment(stored = "dsl-eurusd_rsi_fade--26", orderId = "dsl-eurusd_rsi_fade--2"),
        ).isFalse()
        assertThat(
            matchesOrderComment(stored = "dsl-eurusd_rsi_fade--26", orderId = "dsl-eurusd_rsi_fade--26"),
        ).isTrue()
    }

    @Test
    fun `a venue-truncated comment still matches its own order and only its own order`() {
        // MT5 keeps ~16 characters of the submitted comment.
        assertThat(matchesOrderComment(stored = "dsl-eurusd_rsi_f", orderId = "dsl-eurusd_rsi_fade--26")).isTrue()
        assertThat(
            matchesOrderComment(stored = "dsl-eurusd_rsi_fade--2", orderId = "dsl-eurusd_rsi_fade--26"),
        ).isFalse()
    }

    @Test
    fun `a cut inside a digit of the strategy name still matches its order`() {
        // The venue kept 25 characters of "dsl-xauusd_novel_accel_240m--9": the cut fell between "24" and "0m",
        // and the filled position was never a candidate, so an unknown outcome resolved as "not placed".
        assertThat(
            matchesOrderComment(stored = "dsl-xauusd_novel_accel_24", orderId = "dsl-xauusd_novel_accel_240m--9"),
        ).isTrue()
        assertThat(
            matchesOrderComment(stored = "dsl-xauusd_novel_accel_24", orderId = "dsl-xauusd_novel_accel_240m--"),
        ).isTrue()
        // the trailing number keeps its guard
        assertThat(
            matchesOrderComment(stored = "dsl-xauusd_novel_accel_240m--2", orderId = "dsl-xauusd_novel_accel_240m--26"),
        ).isFalse()
    }
}
