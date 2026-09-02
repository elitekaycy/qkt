package com.qkt.broker.mt5

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
}
