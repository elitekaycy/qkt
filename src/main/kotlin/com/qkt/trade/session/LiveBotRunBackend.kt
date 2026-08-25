package com.qkt.trade.session

import com.qkt.app.LiveSessionHandle
import com.qkt.backtest.BacktestResult
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.math.BigDecimal

/**
 * Live backend over a running [LiveSessionHandle]: `next` waits for the real feed's
 * bar close (the recorder strategy fills [BarHistory] as candles publish), equity is
 * venue truth (read via the gateway, not modeled — null here), and finishing stops
 * the session with no report (insights is the live record).
 */
class LiveBotRunBackend(
    private val handle: LiveSessionHandle,
    private val identities: Set<String>,
    private val clock: Clock = SystemClock(),
    private val pollMs: Long = 200L,
    /**
     * Fires once per [awaitNextBar], after the caller has captured `before` and this backend
     * is about to wait on the feed. Gives a feed-driving harness a deterministic "now
     * waiting for bar N" signal instead of a timing guess (the parity tests, #1078).
     */
    private val onAwaitingBar: (symbol: String, before: Long) -> Unit = { _, _ -> },
) : BotRunBackend {
    override fun awaitNextBar(
        symbol: String,
        before: Long,
        history: BarHistory,
    ): Boolean {
        onAwaitingBar(symbol, before)
        while (handle.running) {
            if (history.countFor(symbol) > before) return true
            Thread.sleep(pollMs)
        }
        return history.countFor(symbol) > before
    }

    override fun nowMs(): Long = clock.now()

    override fun equity(): BigDecimal? = null

    override fun positions(): Map<String, com.qkt.positions.Position> =
        identities
            .flatMap { id -> handle.positionsFor(id) }
            .associateBy { it.symbol }

    override fun finish(): BacktestResult? {
        handle.stop()
        return null
    }
}
