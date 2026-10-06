package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.marketdata.flow.FlowKind
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** `/v1/trades` and `/v1/liquidations` are read page by page, each print exactly, as the wire spec serves them. */
class GatewayTapeReadsTest {
    private val asked = CopyOnWriteArrayList<String>()

    /** Pages keyed by the path and `from` asked, in the wire's shape (the gateway's own JSON, not a venue's). */
    @Volatile private var pages: Map<String, String> = emptyMap()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val url = request.requestUrl!!
                        val key = "${url.encodedPath}@${url.queryParameter("from")}"
                        asked += "$key..${url.queryParameter("to")}"
                        return pages[key]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    }
                }
            start()
        }
    private val client = GatewayClient(server.url("/").toString().trimEnd('/'), "k", 2_000, 1)

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `every page of the tape is read, each print with its exact size and aggressor side`() {
        pages =
            mapOf(
                "/v1/trades@100" to
                    """{"trades":[{"id":"USDC-1","time":100,"price":"85072.7","size":"0.0002","side":"buy"}],"next":150}""",
                "/v1/trades@150" to
                    """{"trades":[{"id":"USDC-2","time":150,"price":"85070.1","size":"0.0016","side":"sell"}]}""",
            )

        val prints = client.prints("BTC_USDC-PERPETUAL", FlowKind.TRADES, 100, 200)

        assertThat(prints.map { it.id }).containsExactly("USDC-1", "USDC-2")
        assertThat(prints.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(prints.last().size.toPlainString()).isEqualTo("0.0016")
        assertThat(asked).containsExactly("/v1/trades@100..200", "/v1/trades@150..200")
    }

    @Test
    fun `liquidations are read from their own endpoint, with the liquidated side`() {
        pages =
            mapOf(
                "/v1/liquidations@0" to
                    """{"liquidations":[{"id":"USDC-65965358","time":7,"price":"85070.2","size":"0.0011","side":"buy"}]}""",
            )

        val print = client.prints("BTC_USDC-PERPETUAL", FlowKind.LIQUIDATIONS, 0, 3_600_000).single()

        assertThat(print.id).isEqualTo("USDC-65965358")
        assertThat(print.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `a side the wire does not define is refused by name`() {
        pages = mapOf("/v1/trades@0" to """{"trades":[{"id":"x","time":1,"price":"1","size":"1","side":"up"}]}""")

        assertThatThrownBy { client.prints("BTC_USDC-PERPETUAL", FlowKind.TRADES, 0, 10) }
            .hasMessageContaining("side 'up'")
    }
}
