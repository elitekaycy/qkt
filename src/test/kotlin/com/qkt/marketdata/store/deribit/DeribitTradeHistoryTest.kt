package com.qkt.marketdata.store.deribit

import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Trade records keep the field set of a real 2026-09-01 history-host response (`trade_id` `USDC-55618921`). */
class DeribitTradeHistoryTest {
    private val server = MockWebServer().also { it.start() }
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )
    private val requests = mutableListOf<String>()

    @AfterEach
    fun teardown() = server.shutdown()

    private fun trade(
        id: Int,
        at: Long,
        name: String = "BTC_USDC-2OCT26-92000-C",
        mark: String = "0.14342087",
    ) = """{"trade_seq":$id,"trade_id":"USDC-$id","timestamp":$at,"tick_direction":3,"price":0.14,""" +
        """"mark_price":$mark,"iv":58.04,"instrument_name":"$name","index_price":83551.57,""" +
        """"direction":"sell","contracts":10.0,"amount":10.0}"""

    private fun page(
        hasMore: Boolean,
        vararg trades: String,
    ) = MockResponse().setBody(
        """{"jsonrpc":"2.0","result":{"trades":[${trades.joinToString(",")}],"has_more":$hasMore}}""",
    )

    private fun history(pages: Map<Long, MockResponse>): DeribitTradeHistory {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    val start = request.requestUrl!!.queryParameter("start_timestamp")!!.toLong()
                    return pages[start] ?: MockResponse().setResponseCode(404)
                }
            }
        val base = server.url("/api/v2").toString().trimEnd('/')
        return DeribitTradeHistory(DeribitClient(base, base, pageSize = 3, sleep = {}))
    }

    @Test
    fun `pages forward from the last trade's millisecond, deduplicating, keeping only the root's contracts`() {
        val history =
            history(
                mapOf(
                    1_000L to
                        page(true, trade(1, 1_000), trade(2, 1_500, name = "AVAX_USDC-11SEP26-7d6-C"), trade(3, 2_000)),
                    2_000L to page(false, trade(3, 2_000), trade(4, 2_500, mark = "1e-05")),
                ),
            )

        val trades = history.trades(root, 1_000, 3_000)

        assertThat(trades.map { it.tradeId }).containsExactly("USDC-1", "USDC-3", "USDC-4")
        assertThat(trades.first().markPrice).isEqualTo(BigDecimal("0.14342087"))
        assertThat(trades.last().markPrice).isEqualByComparingTo("0.00001")
        assertThat(trades.first().iv).isEqualTo(BigDecimal("58.04"))
        assertThat(trades.first().indexPrice).isEqualTo(BigDecimal("83551.57"))
        assertThat(trades.first().tradeSeq).isEqualTo(1)
        assertThat(requests.first())
            .contains("get_last_trades_by_currency_and_time")
            .contains("currency=USDC", "kind=option", "end_timestamp=2999", "sorting=asc", "count=3")
    }

    @Test
    fun `a page that cannot advance fails instead of looping or dropping trades`() {
        val full = history(mapOf(1_000L to page(true, trade(1, 1_000), trade(2, 1_000), trade(3, 1_000))))

        assertThatThrownBy {
            full.trades(
                root,
                1_000,
                3_000,
            )
        }.hasMessageContaining("1000").hasMessageContaining("page size")
        val empty = history(mapOf(1_000L to page(true)))
        assertThatThrownBy { empty.trades(root, 1_000, 3_000) }.hasMessageContaining("1000")
    }

    @Test
    fun `a trade without implied volatility keeps it absent and one without a mark fails naming the trade`() {
        val noIv = trade(1, 1_000).replace(""""iv":58.04""", """"iv":null""")
        val noMark = trade(2, 1_000).replace(""""mark_price":0.14342087""", """"mark_price":null""")

        assertThat(history(mapOf(1_000L to page(false, noIv))).trades(root, 1_000, 3_000).single().iv).isNull()
        assertThatThrownBy { history(mapOf(1_000L to page(false, noMark))).trades(root, 1_000, 3_000) }
            .hasMessageContaining("USDC-2")
    }

    @Test
    fun `an empty window is refused`() {
        assertThatThrownBy { history(emptyMap()).trades(root, 5, 5) }.hasMessageContaining("window")
    }
}
