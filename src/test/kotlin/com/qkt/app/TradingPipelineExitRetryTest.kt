package com.qkt.app

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An exit the venue ends without filling in full (#1359): a `CLOSE` market order Deribit rested at
 * its price band and cancelled with nothing filled left the position open for good, because the
 * rule's condition stayed true and so never rose again. The rule now re-arms and fires on its next
 * bar, at most once a bar, sends only what is still held, and alerts after repeated failures.
 * Entries are never resent.
 */
class TradingPipelineExitRetryTest {
    private fun enteredLong(exit: String = "CLOSE btc"): ExitRetryHarness {
        val h = ExitRetryHarness(ExitRetryHarness.source(exit))
        h.closeBar()
        val entry = h.last()
        assertThat(entry.side).isEqualTo(Side.BUY)
        h.fill(entry)
        assertThat(h.position()).isEqualByComparingTo("1")
        return h
    }

    @Test
    fun `a close the venue cancels unfilled is sent again on the next bar`() {
        val h = enteredLong()
        h.closeBar()
        val close = h.last()
        assertThat(close.side).isEqualTo(Side.SELL)
        h.cancel(close)

        h.closeBar()

        assertThat(h.broker.submits).hasSize(3)
        val retry = h.last()
        assertThat(retry.id).isNotEqualTo(close.id)
        assertThat(retry.side).isEqualTo(Side.SELL)
        assertThat(retry.quantity).isEqualByComparingTo("1")
        h.fill(retry)
        h.closeBar()
        assertThat(h.position()).isEqualByComparingTo("0")
        assertThat(h.broker.submits).hasSize(3)
        assertThat(h.alerts).isEmpty()
    }

    @Test
    fun `a retry waits for the next bar and is sent once per bar`() {
        val h = enteredLong()
        h.closeBar()
        h.cancel(h.last())
        assertThat(h.broker.submits).hasSize(2)

        h.closeBar()
        h.closeBar()

        // The retry is still working at the venue on the second bar: nothing more goes out.
        assertThat(h.broker.submits).hasSize(3)
    }

    @Test
    fun `three consecutive unfilled closes raise an operator alert and retrying continues`() {
        val h = enteredLong()
        repeat(3) {
            h.closeBar()
            h.cancel(h.last())
        }

        assertThat(h.alerts).hasSize(1)
        val (strategyId, message) = h.alerts.single()
        assertThat(strategyId).isEqualTo(ExitRetryHarness.STRATEGY)
        assertThat(message).contains(h.symbol).contains("3").contains("position 1")
        h.closeBar()
        assertThat(h.broker.submits).hasSize(5)
        assertThat(h.last().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `alerts repeat at each doubling of the failure count, not every bar`() {
        val h = enteredLong()
        repeat(12) {
            h.closeBar()
            h.cancel(h.last())
        }

        assertThat(h.alerts.map { it.second.substringAfter("after ").substringBefore(" ") })
            .containsExactly("3", "6", "12")
    }

    @Test
    fun `a close the venue rejects is retried and counts toward the alert`() {
        val h = enteredLong()
        repeat(3) {
            h.closeBar()
            h.reject(h.last())
        }

        assertThat(h.broker.submits.drop(1)).hasSize(3).allMatch { it.side == Side.SELL }
        assertThat(h.alerts).hasSize(1)
    }

    @Test
    fun `a close filled in part then cancelled retries only the remainder`() {
        val h = enteredLong()
        h.closeBar()
        val close = h.last()
        h.partFill(close, BigDecimal("0.4"))
        h.cancel(close)
        assertThat(h.position()).isEqualByComparingTo("0.6")

        h.closeBar()

        assertThat(h.last().quantity).isEqualByComparingTo("0.6")
        assertThat(h.last().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `a sell used as an exit and filled in part retries only what is still held`() {
        val h = enteredLong("SELL btc SIZING 1")
        h.closeBar()
        val exit = h.last()
        h.partFill(exit, BigDecimal("0.4"))
        h.cancel(exit)

        h.closeBar()

        assertThat(h.last().quantity).isEqualByComparingTo("0.6")
        h.fill(h.last())
        h.closeBar()
        assertThat(h.position()).isEqualByComparingTo("0")
        assertThat(h.broker.submits).hasSize(3)
    }

    @Test
    fun `an entry the venue cancels unfilled is not sent again`() {
        val h = ExitRetryHarness(ExitRetryHarness.source("CLOSE btc"))
        h.closeBar()
        h.cancel(h.last())

        repeat(3) { h.closeBar() }

        assertThat(h.broker.submits).hasSize(1)
        assertThat(h.alerts).isEmpty()
    }
}
