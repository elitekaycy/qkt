package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SharedLiveMarketSourceBackfillTest {
    @Test
    fun `a late subscriber starts with the minute's ticks the hub already published, each exactly once`() {
        // 17:03:52 on the day of the fifty-copy run: the first session subscribed at 17:03:49, the
        // other forty-nine at 17:03:52 and built a different 17:03 bar from the ticks after that.
        val at = { hhmmss: String ->
            java.time.Instant
                .parse("2026-09-21T$hhmmss.000Z")
                .toEpochMilli()
        }
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate, clock = FixedClock(time = at("17:03:52")))
        val early = shared.liveTicks(listOf("BTC"))
        val lastMinute = tick("BTC", at("17:02:59"))
        val thisMinute = listOf(tick("BTC", at("17:03:10")), tick("BTC", at("17:03:49")))
        (listOf(lastMinute) + thisMinute).forEach(delegate::emit)
        repeat(3) { early.next() }

        val late = shared.liveTicks(listOf("BTC"))
        val next = tick("BTC", at("17:03:53"))
        delegate.emit(next)

        assertThat(listOf(late.next(), late.next(), late.next())).containsExactlyElementsOf(thisMinute + next)
        assertThat(early.next()).`as`("the early subscriber gets the new tick once, not the backfill").isEqualTo(next)
        early.close()
        late.close()
    }
}
