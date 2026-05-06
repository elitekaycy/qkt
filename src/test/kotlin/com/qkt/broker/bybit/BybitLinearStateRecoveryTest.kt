package com.qkt.broker.bybit

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitLinearStateRecoveryTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private val emptyOk = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private fun seedAllEmpty(client: FakeBybitClient) {
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] = emptyOk
    }

    private class FixedPositionProvider(
        private val map: Map<String, Position>,
    ) : PositionProvider {
        override fun positionFor(symbol: String): Position? = map[symbol]

        override fun allPositions(): Map<String, Position> = map
    }

    @Test
    fun `reconcile emits PositionReconciled when broker has a position engine doesn't know`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Buy","size":"0.5","avgPrice":"80000","category":"linear"}]}}"""

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        BybitLinearStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            positionProvider = FixedPositionProvider(emptyMap()),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(events).hasSize(1)
        val e = events.single()
        assertThat(e.symbol).isEqualTo("BYBIT_LINEAR:BTCUSDT")
        assertThat(e.oldQty).isNull()
        assertThat(e.newQty).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(e.newAvgPx).isEqualByComparingTo(BigDecimal("80000"))
        assertThat(e.source).isEqualTo("BYBIT_LINEAR")
    }

    @Test
    fun `reconcile applies sign convention - Sell side becomes negative qty`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Sell","size":"0.3","avgPrice":"80000","category":"linear"}]}}"""

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        BybitLinearStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            positionProvider = FixedPositionProvider(emptyMap()),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(events.single().newQty).isEqualByComparingTo(BigDecimal("-0.3"))
    }

    @Test
    fun `reconcile emits flat event for engine positions broker no longer reports`() {
        val client = FakeBybitClient()
        seedAllEmpty(client)

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        val enginePositions =
            mapOf(
                "BYBIT_LINEAR:BTCUSDT" to
                    Position("BYBIT_LINEAR:BTCUSDT", BigDecimal("0.5"), BigDecimal("80000")),
            )

        BybitLinearStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            positionProvider = FixedPositionProvider(enginePositions),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(events).hasSize(1)
        assertThat(events.single().newQty).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(events.single().reason).contains("flat")
    }

    @Test
    fun `reconcile does NOT emit when engine and broker positions match within tolerance`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk
        client.responses["/v5/execution/list"] = emptyOk
        client.responses["/v5/account/wallet-balance"] = emptyOk
        client.responses["/v5/position/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"Buy","size":"0.5","avgPrice":"80000","category":"linear"}]}}"""

        val bus = newBus()
        val events = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { events.add(it) }

        val matched =
            mapOf(
                "BYBIT_LINEAR:BTCUSDT" to
                    Position("BYBIT_LINEAR:BTCUSDT", BigDecimal("0.5"), BigDecimal("80000")),
            )

        BybitLinearStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            positionProvider = FixedPositionProvider(matched),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(events).isEmpty()
    }
}
