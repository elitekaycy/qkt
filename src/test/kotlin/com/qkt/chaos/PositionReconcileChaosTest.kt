package com.qkt.chaos

import com.qkt.broker.bybit.FakeBybitClient
import com.qkt.broker.bybit.linear.BybitLinearStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Chaos framing for linear state recovery: a venue position the engine never tracked must re-sync
 * when the transport reconnects. Mirrors `BybitLinearStateRecoveryTest` but drives `reconcile()`
 * through the reconnect callback rather than calling it directly.
 */
class PositionReconcileChaosTest {
    private fun bus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private val emptyOk = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private class FixedPositionProvider(
        private val map: Map<String, Position>,
    ) : PositionProvider {
        override fun positionFor(symbol: String): Position? = map[symbol]

        override fun allPositions(): Map<String, Position> = map
    }

    @Test
    fun `a venue position the engine does not know re-syncs on reconnect`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Buy","size":"0.5","avgPrice":"80000","category":"linear"}]}}"""

        val bus = bus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        val rec =
            BybitLinearStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(0L),
                positionProvider = FixedPositionProvider(emptyMap()),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 0L },
                seenExecIds = mutableSetOf(),
            )
        client.onReconnect { rec.reconcile() }

        client.fireOnReconnect()

        assertThat(events.map { it.symbol }).containsExactly("BYBIT_LINEAR:BTCUSDT")
    }
}
