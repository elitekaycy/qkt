package com.qkt.connector.mt5

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Account, symbol and snapshot reads against a fake gateway: every missing, null and unparseable field. */
class MT5AccountReadsTest {
    private val gateway = Mt5RoutedGateway()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    @AfterEach
    fun stop() = gateway.close()

    private fun reads(cache: MT5ReadCache? = null): MT5AccountReads =
        MT5AccountReads(
            http,
            gateway.url,
            null,
            json,
            MT5GatewayReads(http, gateway.url, null, 0, cache),
            MT5SnapshotParser(json, MT5VenueTime(MT5ServerTimeZone.UTC)),
            cache,
        )

    private fun allKeys(
        keys: List<String>,
        literal: String,
    ): String = keys.joinToString(",", "{", "}") { "\"$it\":$literal" }

    @Test
    fun `readiness is true only for a successful answer whose status is ready`() {
        gateway.on("/health/ready", """{"status":"ready"}""", """{"status":"starting"}""", "{}", "not json")
        val reads = reads()

        assertThat(List(4) { reads.isReady() }).containsExactly(true, false, false, false)
        gateway.fail("/health/ready", 503)
        assertThat(reads.isReady()).isFalse()
    }

    @Test
    fun `positions are every position without a magic and the venue's filter with one`() {
        val rows = "[${Mt5RoutedGateway.position(
            1,
            "0.1",
            magic = 7,
        )},${Mt5RoutedGateway.position(2, "0.2", magic = 8)}]"
        gateway.on("/get_positions", rows)
        val reads = reads()

        assertThat(reads.getPositions()!!.map { it.ticket }).containsExactly(1L, 2L)
        assertThat(reads.getPositions(7)!!.map { it.ticket }).containsExactly(1L, 2L)
        assertThat(gateway.calls("/get_positions")).containsExactly("GET /get_positions", "GET /get_positions?magic=7")
    }

    @Test
    fun `with a shared read cache a magic is filtered locally from the one whole snapshot`() {
        val rows = "[${Mt5RoutedGateway.position(
            1,
            "0.1",
            magic = 7,
        )},${Mt5RoutedGateway.position(2, "0.2", magic = 8)}]"
        gateway.on("/get_positions", rows)
        gateway.on("/orders", """{"orders":[{"ticket":5,"magic":7},{"ticket":6,"magic":8}]}""")
        val reads = reads(MT5ReadCache(60_000L))

        assertThat(reads.getPositions(8)!!.map { it.ticket }).containsExactly(2L)
        assertThat(reads.getPositions()!!.map { it.ticket }).containsExactly(1L, 2L)
        assertThat(reads.getPendingOrders(7)!!.map { it.ticket }).containsExactly(5L)
        assertThat(reads.getPendingOrders()!!.map { it.ticket }).containsExactly(5L, 6L)
        assertThat(gateway.requests.map { it.first }).containsExactly("GET /get_positions", "GET /orders")
    }

    @Test
    fun `pending orders are read with and without a magic and a failed read is unknown`() {
        gateway.on("/orders", """[{"ticket":5,"magic":7}]""")
        val reads = reads()

        assertThat(reads.getPendingOrders()!!.map { it.ticket }).containsExactly(5L)
        assertThat(reads.getPendingOrders(7)!!.map { it.ticket }).containsExactly(5L)
        assertThat(gateway.calls("/orders")).containsExactly("GET /orders", "GET /orders?magic=7")
        gateway.fail("/orders")
        gateway.fail("/get_positions")
        assertThat(reads.getPendingOrders()).isNull()
        assertThat(reads.getPositions()).isNull()
    }

    @Test
    fun `the account is read field by field and defaults what the gateway leaves out`() {
        val keys =
            listOf(
                "balance",
                "equity",
                "currency",
                "leverage",
                "margin_mode",
                "margin_free",
                "margin_level",
                "margin",
                "profit",
                "login",
                "server",
                "name",
                "trade_mode",
            )
        gateway.on(
            "/account",
            """{"balance":"100","equity":"101","currency":"USD","leverage":500,"margin_mode":2,"margin_free":"90",
            "margin_level":"1010","margin":"10","profit":"1","login":77,"server":"Exness-MT5","name":"n","trade_mode":0}""",
            "{}",
            allKeys(keys, "null"),
        )
        val reads = reads()

        val full = reads.getAccount()!!
        assertThat(full.balance).isEqualByComparingTo("100")
        assertThat(full.equity).isEqualByComparingTo("101")
        assertThat(listOf(full.currency, full.server, full.name)).containsExactly("USD", "Exness-MT5", "n")
        assertThat(listOf(full.leverage, full.marginMode, full.tradeMode)).containsExactly(500, 2, 0)
        assertThat(listOf(full.marginFree, full.marginLevel, full.margin, full.profit).map { it!!.toPlainString() })
            .containsExactly("90", "1010", "10", "1")
        assertThat(full.login).isEqualTo(77L)
        val empty = reads.getAccount()!!
        assertThat(empty).isEqualTo(reads.getAccount())
        assertThat(empty.balance).isEqualByComparingTo("0")
        assertThat(listOf(empty.currency, empty.server, empty.name)).containsOnly("")
        assertThat(
            listOf(empty.leverage, empty.marginMode, empty.tradeMode),
        ).containsExactly(0, MARGIN_MODE_UNKNOWN, -1)
        assertThat(listOf(empty.marginFree, empty.marginLevel, empty.margin, empty.profit)).containsOnlyNulls()
        gateway.fail("/account")
        assertThat(reads.getAccount()).isNull()
    }
}
