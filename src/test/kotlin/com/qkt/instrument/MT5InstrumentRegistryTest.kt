package com.qkt.instrument

import com.qkt.broker.mt5.MT5Broker
import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5InstrumentRegistryTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path == "/symbol_info/XAUUSDm" ->
                            MockResponse().setBody(
                                """{"ask":4561.818,"bid":4561.51,"digits":3,"point":0.001,""" +
                                    """"trade_stops_level":0,"volume_min":0.01,"volume_step":0.01,""" +
                                    """"trade_contract_size":100.0}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val clock = FixedClock(time = 1L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
            )
        broker = MT5Broker(profile, bus, clock)
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    @Test
    fun `lookup resolves a qkt-prefixed symbol via the broker's symbol_info cache`() {
        val registry = MT5InstrumentRegistry(broker)
        val meta = registry.require("EXNESS:XAUUSD")
        assertThat(meta.qktSymbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(meta.contractSize).isEqualByComparingTo("100")
        assertThat(meta.volumeStep).isEqualByComparingTo("0.01")
        assertThat(meta.digits).isEqualTo(3)
        assertThat(meta.pointSize).isEqualByComparingTo("0.001")
    }

    @Test
    fun `lookup returns null for an unknown broker symbol`() {
        val registry = MT5InstrumentRegistry(broker)
        assertThat(registry.lookup("EXNESS:UNKNOWN")).isNull()
    }
}
