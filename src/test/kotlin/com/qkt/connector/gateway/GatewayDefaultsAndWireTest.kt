package com.qkt.connector.gateway

import com.qkt.broker.PositionAccountingMode
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.Print
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Defaults and wire shapes of the gateway connector that no running account reaches. */
class GatewayDefaultsAndWireTest {
    private val instrument = WireInstrument("BTC_USDC-PERPETUAL", "perpetual", "USDC", "1", "0.5", "0.0001", "0.0001")

    @Test
    fun `wire records keep their fields through copy, destructuring and equality`() {
        val kill = WireKillSwitch(all = true)
        assertThat(kill.symbols).isEmpty()
        assertThat(kill.copy(symbols = listOf("A")).symbols).containsExactly("A")
        val health = WireHealth("vgp1", "fake", "1.0", "7", "demo", true, kill, 5L, "s", 9L)
        val (protocol, adapter, version, login, mode, connected, killSwitch, time, stream, seq, capabilities) = health
        assertThat(
            listOf(protocol, adapter, version, login, mode, stream),
        ).containsExactly("vgp1", "fake", "1.0", "7", "demo", "s")
        assertThat(
            listOf(connected, killSwitch, time, seq, capabilities),
        ).containsExactly(true, kill, 5L, 9L, emptyList<String>())
        assertThat(health.copy()).isEqualTo(health).hasSameHashCodeAs(health).hasToString(health.toString())
        val body = WireErrorBody(WireError("c", "m"))
        assertThat(body.copy().error.message).isEqualTo("m")
        assertThat(body.component1()).isEqualTo(WireError("c", "m"))
        val print = WirePrint("1", 2L, "3", "4", "buy")
        assertThat(print.copy().component5()).isEqualTo("buy")
        assertThat(WireTrades(listOf(print)).copy().next).isNull()
        val depth = WireDepthSnapshot(1L, listOf(listOf("1", "2")), emptyList())
        assertThat(depth.copy()).isEqualTo(depth)
        assertThat(WireDepth(listOf(depth)).copy().next).isNull()
        val point = WireOpenInterestPoint(1L, "5")
        assertThat(point.copy()).isEqualTo(point).hasSameHashCodeAs(point)
        assertThat(WireOpenInterest(listOf(point)).copy().next).isNull()
    }

    @Test
    fun `a fresh account state reports no equity, unknown accounting and no positions`() {
        val state = GatewayAccountState()
        assertThat(state.equity).isNull()
        assertThat(state.accounting).isEqualTo(PositionAccountingMode.UNKNOWN)
        assertThat(state.positions).isEmpty()
    }

    @Test
    fun `holders built with the defaults hold no refusal`() {
        val holders =
            GatewayHolders(
                GatewaySymbols("X:"),
                GatewayRouting(),
                GatewayAccountState(),
                Any(),
                { emptySet() },
                clock = FixedClock(1L),
            )
        assertThat(holders.riskRefused).isNull()
        assertThat(holders.refusal()).isNull()
    }

    @Test
    fun `a margin floor gap is judged without reading a version for an adapter that needs none`() {
        assertThat(marginFloorGap("paper")).contains("paper")
        assertThat(marginFloorGap("deribit")).isNull()
    }

    @Test
    fun `a funding record with a position that is not a number is a protocol error`() {
        val funding =
            GatewayFunding(
                GatewayClient("http://127.0.0.1:1", "k"),
                GatewaySymbols("X:"),
                FixedClock(1L),
                GatewayRouting(),
                Any(),
            )
        val bad = WireFunding("f", "BTC_USDC-PERPETUAL", "1", "USDC", "oops", 1L)
        assertThatThrownBy { funding.charged(bad) }
            .isInstanceOf(GatewayProtocolException::class.java)
            .hasMessageContaining("funding position 'oops'")
    }

    @Test
    fun `an account built without a state root closes cleanly and finishes its deploys with nothing opened`() {
        val config =
            AccountConfig(
                "gw",
                "gateway",
                mapOf(
                    "gateway_url" to "http://127.0.0.1:1",
                    "api_key" to "k",
                    "expected_adapter" to "fake",
                    "expected_account_login" to "7",
                    "expected_trade_mode" to "demo",
                ),
            )
        val settings = GatewaySettings.of(config, ConnectorContext(null, emptyMap(), FixedClock(1L)))
        val account =
            GatewayTradingAccount(
                config,
                settings,
                FixedClock(1L),
                { emptySet() },
                GatewayChainRecording(null, 1_000L),
                null,
                { false },
            )
        account.deploysFinished()
        account.close()
    }

    @Test
    fun `a market source with default readers serves flow of its own symbols and no bars`() {
        val source = GatewayMarketSource("DERIBIT:", "http://127.0.0.1:1", "k", listing = { listOf(instrument) })
        assertThat(source.tradeFlowFor("DERIBIT:BTC_USDC_PERPETUAL")).isNotNull()
        assertThat(source.tradeFlowFor("OTHER:X")).isNull()
        val range = TimeRange(Instant.ofEpochSecond(0), Instant.ofEpochSecond(3_600))
        assertThat(source.bars("DERIBIT:BTC_USDC_PERPETUAL", TimeWindow.ONE_MINUTE, range).toList()).isEmpty()
    }

    @Test
    fun `trade flow with its own scheduler reads a settled window in the background`() {
        val minute = 60_000L
        val read = CountDownLatch(1)
        val flow =
            GatewayTradeFlow(
                "DERIBIT:",
                { listOf("trades") },
                { _, _, _, _ ->
                    read.countDown()
                    emptyList<Print>()
                },
                { 100 * minute + 2_000L },
            )
        flow.listed(listOf(instrument))
        assertThat(flow.window("DERIBIT:BTC_USDC_PERPETUAL", FlowKind.TRADES, minute, 98 * minute)).isNull()
        assertThat(read.await(5, TimeUnit.SECONDS)).isTrue()
    }
}
