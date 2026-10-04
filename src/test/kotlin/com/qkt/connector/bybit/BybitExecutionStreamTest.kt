package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A Bybit broker's private `execution` stream: only its own category's `Trade` executions are fills. */
class BybitExecutionStreamTest {
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val funded = mutableListOf<JsonObject>()
    private val lastFill = AtomicLong(0L)
    private val stream =
        BybitExecutionStream(
            "linear",
            bus,
            FixedClock(7_000L),
            boundedExecIdSet(),
            lastFill,
            mapOf("c1" to "s1")::get,
        ) {
            funded += it
        }

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
    }

    private fun frame(vararg entries: String) =
        Json.parseToJsonElement("""{"topic":"execution","data":[${entries.joinToString(",")}]}""").jsonObject

    private fun execution(
        execType: String,
        execId: String,
        category: String = "linear",
    ) = """{"category":"$category","symbol":"BTCUSDT","orderLinkId":"c1","orderId":"o1","side":"Buy",""" +
        """"execPrice":"95900.1","execQty":"0.5","execFee":"26.3725275","execId":"$execId","execType":"$execType"}"""

    @Test
    fun `a trade execution becomes one fill attributed to its strategy, with its fee`() {
        stream.onFrame(frame(execution("Trade", "e1")))
        stream.onFrame(frame(execution("Trade", "e1")))

        assertThat(fills).hasSize(1)
        val fill = fills.single()
        assertThat(fill.symbol).isEqualTo("BYBIT_LINEAR:BTCUSDT")
        assertThat(fill.strategyId).isEqualTo("s1")
        assertThat(fill.venueCosts).isEqualByComparingTo("26.3725275")
        assertThat(lastFill.get()).isEqualTo(7_000L)
    }

    @Test
    fun `a funding execution goes to the funding handler and is never a fill`() {
        stream.onFrame(frame(execution("Funding", "f1")))

        assertThat(fills).isEmpty()
        assertThat(funded).hasSize(1)
    }

    @Test
    fun `liquidation, auto-deleverage and delivery executions are not fills`() {
        stream.onFrame(frame(execution("BustTrade", "b1"), execution("AdlTrade", "a1"), execution("Delivery", "d1")))

        assertThat(fills).isEmpty()
        assertThat(funded).isEmpty()
    }

    @Test
    fun `an execution of the sibling category on the shared login is skipped`() {
        stream.onFrame(frame(execution("Trade", "s1", category = "spot")))

        assertThat(fills).isEmpty()
    }
}
