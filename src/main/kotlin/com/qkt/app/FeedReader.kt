package com.qkt.app

import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataLifecycleFeed

/**
 * Feed reader: turn the blocking tick feed into queue messages so the engine loop stays a
 * pure single consumer rather than blocking on the feed itself. When the feed ends it closes
 * it and tells the loop to drain then stop, e.g. a finite replay source ends quietly while a
 * live feed that ran out of reconnects ends as `unexpected` with its failure reason.
 */
internal class FeedReader(
    private val feed: TickFeed,
    private val mailbox: EngineMailbox,
    private val insights: InsightsLifecycle,
) {
    private val running = mailbox.running
    private val control = mailbox.control

    /** The `qkt-live-feed` daemon thread that runs this reader; the caller starts it. */
    fun newThread(): Thread = Thread({ run() }, "qkt-live-feed").apply { isDaemon = true }

    private fun run() {
        try {
            while (running.get()) {
                val tick = feed.next() ?: break
                mailbox.postTick(Inbound.FeedTick(tick))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            val lifecycleFeed = feed as? MarketDataLifecycleFeed
            val unexpected = running.get() && lifecycleFeed?.expectsContinuousDelivery == true
            val failureReason =
                lifecycleFeed?.terminalFailureReason()
                    ?: "live market-data feed exceeded its reconnect budget"
            runCatching { feed.close() }
            insights.feedEnded()
            // Non-blocking: tell the consumer the feed is done so it drains-then-stops.
            control.offer(
                Inbound.FeedEnded(
                    unexpected = unexpected,
                    reason = failureReason,
                ),
            )
        }
    }
}
