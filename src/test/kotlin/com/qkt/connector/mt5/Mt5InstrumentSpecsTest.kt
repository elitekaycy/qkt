package com.qkt.connector.mt5

import com.qkt.instrument.UnreportedCost
import java.time.DayOfWeek
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class Mt5InstrumentSpecsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MT5Client
    private val exness =
        MT5BrokerProfile(
            name = "exness",
            gatewayUrl = "http://localhost:5002",
            symbolPolicy = SymbolPolicy(suffix = "m"),
            magic = 123,
        )

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client =
            MT5Client(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                serverTimeZone = MT5ServerTimeZone.UTC,
                retryAttempts = 0,
            )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun symbolInfo(swap: String) {
        server.enqueue(
            MockResponse().setBody(
                """{"ask":4561.818,"bid":4561.51,"digits":3,"point":0.001,"trade_stops_level":0,""" +
                    """"volume_min":0.01,"volume_step":0.01,"volume_max":200,"trade_contract_size":100.0$swap}""",
            ),
        )
    }

    @Test
    fun `swap quoted in points is taken with its triple day`() {
        symbolInfo(""","swap_mode":1,"swap_long":-560.0,"swap_short":0.0,"swap_rollover3days":3""")

        val spec = client.instrumentSpec(exness, "EXNESS:XAUUSD")!!

        assertThat(server.takeRequest().path).isEqualTo("/symbol_info/XAUUSDm")
        assertThat(spec.meta.swapLongPoints).isEqualByComparingTo("-560")
        assertThat(spec.meta.swapShortPoints).isEqualByComparingTo("0")
        assertThat(spec.meta.swapTripleDay).isEqualTo(DayOfWeek.WEDNESDAY)
        assertThat(spec.unreported.keys).containsExactly(UnreportedCost.COMMISSION)
    }

    @Test
    fun `swap disabled at the venue is a known zero`() {
        symbolInfo(""","swap_mode":0,"swap_long":0,"swap_short":0,"swap_rollover3days":3""")

        val spec = client.instrumentSpec(exness, "EXNESS:XAUUSD")!!

        assertThat(spec.meta.swapLongPoints).isEqualByComparingTo("0")
        assertThat(spec.unreported).doesNotContainKey(UnreportedCost.SWAP)
    }

    @Test
    fun `swap in another mode is reported unset with its reason`() {
        symbolInfo(""","swap_mode":2,"swap_long":-7.5,"swap_short":1.2,"swap_rollover3days":3""")

        val spec = client.instrumentSpec(exness, "EXNESS:XAUUSD")!!

        assertThat(spec.meta.swapLongPoints).isEqualByComparingTo("0")
        assertThat(spec.unreported[UnreportedCost.SWAP]).isEqualTo("swap_mode 2 is not points")
    }

    @Test
    fun `a triple day outside the trading week leaves swap unset`() {
        symbolInfo(""","swap_mode":1,"swap_long":-10,"swap_short":-4,"swap_rollover3days":7""")

        val spec = client.instrumentSpec(exness, "EXNESS:USOIL")!!

        assertThat(spec.meta.swapLongPoints).isEqualByComparingTo("0")
        assertThat(spec.unreported[UnreportedCost.SWAP]).isEqualTo("swap_rollover3days 7 is not Monday to Friday")
    }

    @Test
    fun `a gateway that reports no swap leaves it unset`() {
        symbolInfo("")

        val spec = client.instrumentSpec(exness, "EXNESS:XAUUSD")!!

        assertThat(spec.unreported[UnreportedCost.SWAP]).isEqualTo("the gateway reports no swap")
    }

    @Test
    fun `a venue tick coarser than a point is the price step, points stay a point wide`() {
        // An index CFD quoted in 0.01 points that trades in 0.25 steps (#1455).
        symbolInfo(""","trade_tick_size":0.25""")

        val meta = client.instrumentSpec(exness, "EXNESS:XAUUSD")!!.meta

        assertThat(meta.pointSize).isEqualByComparingTo("0.001")
        assertThat(meta.tickSize).isEqualByComparingTo("0.25")
        assertThat(meta.priceStep).isEqualByComparingTo("0.25")
    }

    @Test
    fun `a tick equal to the point leaves the price step at the point`() {
        symbolInfo(""","trade_tick_size":0.001""")

        val meta = client.instrumentSpec(exness, "EXNESS:XAUUSD")!!.meta

        assertThat(meta.priceStep).isEqualByComparingTo("0.001")
    }

    @Test
    fun `a stock CFD carries its folder, its trade mode, 100-share lots and a Friday triple swap`() {
        server.enqueue(
            MockResponse().setBody(
                """{"ask":622.9,"bid":622.54,"digits":2,"point":0.01,"trade_stops_level":0,"volume_min":0.01,""" +
                    """"volume_step":0.01,"volume_max":10,"trade_contract_size":100.0,"trade_tick_size":0.01,""" +
                    """"swap_mode":1,"swap_long":-6.1,"swap_short":-5.3,"swap_rollover3days":5,""" +
                    """"path":"Standard\\Stocks\\FBm","trade_mode":3}""",
            ),
        )

        val meta = client.instrumentSpec(exness, "EXNESS:FB")!!.meta

        assertThat(meta.contractSize).isEqualByComparingTo("100")
        assertThat(meta.volumeMin).isEqualByComparingTo("0.01")
        assertThat(meta.category).isEqualTo("Stocks")
        assertThat(meta.tradeMode).isEqualTo(com.qkt.instrument.TradeMode.CLOSE_ONLY)
        assertThat(meta.swapTripleDay).isEqualTo(DayOfWeek.FRIDAY)
    }

    @Test
    fun `a gateway that reports no folder or trade mode leaves the symbol uncategorised and fully tradable`() {
        symbolInfo(""","swap_mode":1,"swap_long":-560.0,"swap_short":0.0,"swap_rollover3days":3""")

        val meta = client.instrumentSpec(exness, "EXNESS:XAUUSD")!!.meta

        assertThat(meta.category).isNull()
        assertThat(meta.tradeMode).isEqualTo(com.qkt.instrument.TradeMode.FULL)
    }
}
