package com.qkt.app

import com.qkt.cli.daemon.routes.renderEngineStallFields
import com.qkt.events.RiskEvent
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A message that holds the engine thread a second or more is counted and named, so a timed-out read has a cause. */
class EngineStallWatchTest {
    private val ms = 1_000_000L

    @Test
    fun `holds under the threshold are not counted`() {
        val watch = EngineStallWatch(thresholdMs = 1_000)

        watch.held(Inbound.Heartbeat(0), 999 * ms)

        assertThat(watch.snapshot()).isEqualTo(EngineStalls.NONE)
    }

    @Test
    fun `every long hold counts and the longest is named`() {
        val watch = EngineStallWatch(thresholdMs = 1_000)

        watch.held(Inbound.Heartbeat(0), 1_200 * ms)
        watch.held(Inbound.Query {}, 5_400 * ms)
        watch.held(Inbound.Flatten, 2_000 * ms)

        assertThat(watch.snapshot()).isEqualTo(EngineStalls(3, 5_400, "query"))
    }

    @Test
    fun `health reports the count and the worst hold`() {
        assertThat(renderEngineStallFields(EngineStalls.NONE)).isEqualTo(""""engineStalls":0,"engineWorstHold":null,""")
        assertThat(renderEngineStallFields(EngineStalls(2, 1_500, "event OrderFilled")))
            .isEqualTo(""""engineStalls":2,"engineWorstHold":{"ms":1500,"what":"event OrderFilled"},""")
    }

    @Test
    fun `every engine message names itself for the stall log`() {
        val tick = Tick("XAUUSD", BigDecimal.ONE, 0L)

        assertThat(Inbound.FeedTick(tick).kind()).isEqualTo("tick XAUUSD")
        assertThat(Inbound.BusEvent(RiskEvent.Resumed(null)).kind()).isEqualTo("event Resumed")
        assertThat(Inbound.PersistenceHealthCheck.kind()).isEqualTo("persistence health check")
        assertThat(Inbound.FeedEnded(unexpected = true, reason = "eof").kind()).isEqualTo("feed end")
        assertThat(Inbound.GracefulStop(deadlineNanos = 0L).kind()).isEqualTo("graceful stop")
    }
}
