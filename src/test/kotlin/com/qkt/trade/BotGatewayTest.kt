package com.qkt.trade

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5Client
import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.broker.mt5.SymbolPolicy
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BotGatewayTest {
    private lateinit var server: HttpServer
    private lateinit var gateway: BotGateway
    private val responses = mutableMapOf<String, Pair<Int, String>>()
    private val requests = mutableListOf<Pair<String, String>>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().decodeToString()
            requests.add("${exchange.requestMethod} $path" to body)
            val (status, payload) = responses[path] ?: (404 to "{}")
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        val profile =
            MT5BrokerProfile(
                name = "exness",
                gatewayUrl = url,
                symbolPolicy = SymbolPolicy(suffix = "m"),
                magic = 777,
                httpTimeoutMs = 2000,
                retryAttempts = 0,
            )
        val client =
            MT5Client(
                gatewayUrl = url,
                serverTimeZone = MT5ServerTimeZone.UTC,
                httpTimeoutMs = 2000,
                retryAttempts = 0,
            )
        gateway = BotGateway(profile, client)
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun market(qty: String = "0.5") =
        OrderRequest.Market(
            id = "bot-1",
            symbol = "EXNESS:XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal(qty),
            timeInForce = TimeInForce.GTC,
            timestamp = 1_000L,
        )

    @Test
    fun `place maps symbol attaches exits and parses success`() {
        responses["/order"] =
            200 to """{"result":{"retcode":10009,"order":123,"deal":456,"price":2650.5,"comment":"done"}}"""
        val result =
            gateway.place(
                CompiledBotOrder(
                    request = market(),
                    stopLoss = BigDecimal("2620.50"),
                    takeProfit = BigDecimal("2710.50"),
                ),
            )
        assertThat(result.ok).isTrue
        assertThat(result.ticket).isEqualTo(123L)
        assertThat(result.deal).isEqualTo(456L)
        assertThat(result.price).isEqualByComparingTo("2650.5")
        val body = requests.single { it.first == "POST /order" }.second
        assertThat(body).contains("\"symbol\":\"XAUUSDm\"")
        assertThat(body).contains("\"type\":\"BUY\"")
        assertThat(body).contains("\"volume\":0.5")
        assertThat(body).contains("\"sl\":2620.50")
        assertThat(body).contains("\"tp\":2710.50")
        assertThat(body).contains("\"magic\":777")
        assertThat(body).contains("\"comment\":\"bot-1\"")
    }

    @Test
    fun `place surfaces venue rejection reason`() {
        responses["/order"] =
            200 to """{"result":{"retcode":10019,"order":0,"deal":0,"price":0,"comment":"No money"}}"""
        val result = gateway.place(CompiledBotOrder(market(), null, null))
        assertThat(result.ok).isFalse
        assertThat(result.error).contains("No money")
    }

    @Test
    fun `bracket keeps translator attached exits`() {
        responses["/order"] =
            200 to """{"result":{"retcode":10009,"order":9,"deal":0,"price":0,"comment":""}}"""
        val bracket =
            OrderRequest.Bracket(
                id = "bot-2",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.5"),
                entry = market(),
                takeProfit = BigDecimal("2710.50"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("2620.50")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            )
        gateway.place(CompiledBotOrder(bracket, BigDecimal("2620.50"), BigDecimal("2710.50")))
        val body = requests.single { it.first == "POST /order" }.second
        assertThat(body).contains("\"sl\":2620.50")
        assertThat(body).contains("\"tp\":2710.50")
    }

    @Test
    fun `close modify and cancel round trip`() {
        responses["/close_position"] =
            200 to """{"result":{"retcode":10009,"order":0,"deal":77,"price":2651,"comment":""}}"""
        responses["/modify_sl_tp"] =
            200 to """{"result":{"retcode":10009,"order":0,"deal":0,"price":0,"comment":""}}"""
        responses["/orders/55"] = 200 to """{"ok":true}"""
        assertThat(gateway.close(42L, BigDecimal("0.2")).ok).isTrue
        assertThat(gateway.modify(42L, BigDecimal("2600"), BigDecimal("2700")).ok).isTrue
        assertThat(gateway.cancel(55L)).isTrue
        assertThat(requests.single { it.first == "POST /close_position" }.second).contains("\"ticket\":42")
        assertThat(requests.single { it.first == "POST /modify_sl_tp" }.second).contains("\"sl\":2600")
        assertThat(requests.map { it.first }).contains("DELETE /orders/55")
    }

    @Test
    fun `quote context reads tick symbol info and account`() {
        responses["/symbol_info_tick/XAUUSDm"] = 200 to """{"bid":2650.0,"ask":2650.5,"time":1}"""
        responses["/symbol_info/XAUUSDm"] =
            200 to
            """{"ask":2650.5,"bid":2650.0,"digits":2,"point":0.01,"trade_stops_level":0,
                |"volume_min":0.01,"volume_step":0.01,"trade_contract_size":100}
            """.trimMargin()
        responses["/account"] =
            200 to """{"balance":9800,"equity":10000,"currency":"USD","leverage":200,"margin_mode":2}"""
        val ctx = gateway.quoteContext("EXNESS:XAUUSD", "EUR")
        assertThat(ctx.bid).isEqualByComparingTo("2650.0")
        assertThat(ctx.ask).isEqualByComparingTo("2650.5")
        assertThat(ctx.equity).isEqualByComparingTo("10000")
        assertThat(ctx.contractSize).isEqualByComparingTo("100")
        assertThat(ctx.accountCurrency).isEqualTo("USD")
        assertThat(ctx.quoteCurrency).isEqualTo("USD")
        assertThat(ctx.digits).isEqualTo(2)
    }

    @Test
    fun `quote context fails closed when the venue has no quote`() {
        assertThatThrownBy { gateway.quoteContext("EXNESS:XAUUSD", "USD") }
            .hasMessageContaining("quote")
    }
}
