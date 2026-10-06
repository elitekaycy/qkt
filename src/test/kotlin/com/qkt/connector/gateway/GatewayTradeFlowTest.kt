package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.Print
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Live flow is read from the gateway in the background, a window once it has settled, and served from then on;
 * a read never waits on the gateway.
 */
class GatewayTradeFlowTest {
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val code = "BTC_USDC-PERPETUAL"
    private val minute = 60_000L
    private var now = 100 * minute + 500
    private val fetched = mutableListOf<Pair<Long, Long>>()
    private val queued = mutableListOf<Pair<Long, () -> Unit>>()
    private var tape = mutableListOf<Print>()
    private var failing = false
    private var declared = listOf("trades")

    private val flow =
        GatewayTradeFlow(
            "DERIBIT:",
            { declared },
            { _, _, from, to ->
                check(!failing) { "gateway unreachable" }
                fetched += from to to
                tape.filter { it.timeMs in from until to }
            },
            { now },
        ) { delay, task -> queued += delay to task }.apply {
            listed(listOf(WireInstrument(code, "perpetual", "USDC", "1", "0.5", "0.0001", "0.0001")))
        }

    private fun print(
        time: Long,
        size: String,
        side: Side,
    ) = Print("p$time", time, BigDecimal("85000"), BigDecimal(size), side)

    private fun runQueued() {
        val tasks = queued.toList()
        queued.clear()
        tasks.forEach { (delay, task) ->
            now += delay
            task()
        }
    }

    @Test
    fun `an unread window is unknown, read once settled, then served with zero for a quiet one`() {
        tape += print(98 * minute + 1, "0.5", Side.BUY)
        tape += print(98 * minute + 2, "0.2", Side.SELL)

        assertThat(flow.window(symbol, FlowKind.TRADES, minute, 98 * minute)).isNull()
        assertThat(queued.single().first).describedAs("waits for the newest window to settle").isEqualTo(1_500L)
        runQueued()

        assertThat(fetched.single()).isEqualTo(98 * minute to 100 * minute)
        val sums = flow.window(symbol, FlowKind.TRADES, minute, 98 * minute)!!
        assertThat(sums.buy).isEqualByComparingTo("0.5")
        assertThat(sums.sell).isEqualByComparingTo("0.2")
        assertThat(flow.window(symbol, FlowKind.TRADES, minute, 99 * minute)!!.buy.signum()).isZero
    }

    @Test
    fun `each read queues the windows closed since, so the next bar's window is known before a rule reads it`() {
        flow.window(symbol, FlowKind.TRADES, minute, 98 * minute)
        runQueued()
        now = 101 * minute + 300
        tape += print(100 * minute + 5, "1", Side.BUY)

        flow.window(symbol, FlowKind.TRADES, minute, 99 * minute)
        runQueued()

        assertThat(fetched.last()).isEqualTo(100 * minute to 101 * minute)
        assertThat(flow.window(symbol, FlowKind.TRADES, minute, 100 * minute)!!.buy).isEqualByComparingTo("1")
    }

    @Test
    fun `a failed read leaves the window unknown and is tried again on the next read`() {
        failing = true
        flow.window(symbol, FlowKind.TRADES, minute, 98 * minute)
        runQueued()
        assertThat(flow.window(symbol, FlowKind.TRADES, minute, 98 * minute)).isNull()

        failing = false
        runQueued()

        assertThat(flow.window(symbol, FlowKind.TRADES, minute, 98 * minute)).isNotNull
    }

    @Test
    fun `a series the gateway does not declare is a problem naming the capability, and unlisted codes have none`() {
        assertThat(flow.problem(symbol, FlowKind.TRADES)).isNull()
        assertThat(flow.problem(symbol, FlowKind.LIQUIDATIONS)).contains("capability 'liquidations'")
        assertThat(flow.window("DERIBIT:ETH_USDC_PERPETUAL", FlowKind.TRADES, minute, 0)).isNull()
    }
}
